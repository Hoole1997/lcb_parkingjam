import { JamGame } from './game'
import { generateLevel, LEVEL_COUNT } from './levelgen'
import type { Car, JamLevel } from './types'
import { DX, DY } from './types'
import {
  CAR_COLORS,
  MYSTERY,
  THEMES,
  drawCarSprite,
  drawPerson,
  drawObstacle,
  drawPond,
  drawQueueSign,
  drawQueueRail,
  drawButton,
  drawPill,
  gameSpriteAssetsSettled,
  roundRect,
  hit,
  type Rect,
} from './render'
import { sound } from './audio'
import { gameStrings } from './i18n'
import { loadProgress, saveProgress } from './storage'
import {
  exitToNativeGameHome,
  hostMode,
  notifyNativeFirstFrameRendered,
  notifyNativeLevelCompleted,
  completeNativeRewardedAd,
  requestNativeRewardedAd,
  type RewardedAdPlacement,
} from './native-bridge'

const canvas = document.getElementById('game') as HTMLCanvasElement
const ctx = canvas.getContext('2d')!

// ImageGen 定稿拆出的纯场景底板。动态 UI、乘客、车位和车辆仍由 Canvas 绘制，
// 既能保持生成稿的质感，也不会把关卡数据烘焙进一张不可维护的整屏图片。
const gardenBackground = new Image()
let gardenBackgroundReady = false
let gardenBackgroundSettled = false
gardenBackground.addEventListener('load', () => {
  gardenBackgroundReady = true
  gardenBackgroundSettled = true
})
gardenBackground.addEventListener('error', () => {
  gardenBackgroundSettled = true
})
gardenBackground.src = './game-garden-background-v2.png'

let W = 0
let H = 0

function resize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  W = window.innerWidth
  H = window.innerHeight
  canvas.width = W * dpr
  canvas.height = H * dpr
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}
resize()
window.addEventListener('resize', resize)

const now = () => performance.now() / 1000
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)

// ------------------------------------------------------------------
// 全局状态
// ------------------------------------------------------------------
type Scene = 'menu' | 'select' | 'play'
let scene: Scene = 'menu'
const progress = loadProgress()

let levelIdx = 0
let game: JamGame | null = null
let curLevel: JamLevel | null = null
let over: 'win' | 'lose' | null = null
let overTime = 0
let rewardedAdPending = false

// ------------------------------------------------------------------
// 布局
// ------------------------------------------------------------------
const TILT = 0 // ImageGen 定稿为正交停车场，避免旋转导致可用棋盘面积变小。
const MAX_SLOTS = 7 // 最多车位（5 初始 + 2 可解锁）

const L = {
  sceneY: 0, // 场景带（含顶部 UI 浮层）底部为 sceneBot
  sceneBot: 0,
  parkY: 0,
  parkH: 0,
  roadY: 0,
  roadH: 0,
  jamX: 0,
  jamY: 0,
  jamCx: 0, // 车阵旋转中心
  jamCy: 0,
  cell: 0,
  slotW: 0,
  slotXs: [] as number[],
  slotYc: 0,
  roadYc: 0,
  qFrontX: 0,
  qY: 0,
  qSpacing: 0,
  qMax: 0,
  gate: { x: 0, y: 0, w: 0, h: 0 },
  toolbarY: 0,
}

// 车阵局部坐标（未旋转）→ 屏幕坐标
function jamToScreen(x: number, y: number): { x: number; y: number } {
  const dx = x - L.jamCx
  const dy = y - L.jamCy
  const c = Math.cos(TILT)
  const s = Math.sin(TILT)
  return { x: L.jamCx + dx * c - dy * s, y: L.jamCy + dx * s + dy * c }
}

// 屏幕坐标 → 车阵局部坐标（点击判定用）
function screenToJam(x: number, y: number): { x: number; y: number } {
  const dx = x - L.jamCx
  const dy = y - L.jamCy
  const c = Math.cos(-TILT)
  const s = Math.sin(-TILT)
  return { x: L.jamCx + dx * c - dy * s, y: L.jamCy + dx * s + dy * c }
}

function computeLayout() {
  if (!game) return
  // 以下比例来自 841×1870 的 ImageGen 定稿。使用相对比例而非固定像素，
  // 让 16:9～22:9 设备保持同一视觉层次；棋盘格仍按实际关卡尺寸自适应。
  const sceneH = H * 0.235
  L.sceneY = 0
  L.sceneBot = sceneH
  L.parkY = sceneH
  L.parkH = H * 0.098
  L.roadY = H * 0.333
  L.roadH = H * 0.057
  L.toolbarY = H * 0.885
  const jamTop = H * 0.397
  const jamBottom = H * 0.858
  // ImageGen 稿的停车场左右各留约 5.5% 花园边界，复杂关卡在内部自动缩放。
  const sideInset = Math.max(18, W * 0.055)
  const availW = W - sideInset * 2
  const availH = jamBottom - jamTop
  L.cell = Math.min(availW / game.w, availH / game.h, 46)
  L.jamX = (W - game.w * L.cell) / 2
  L.jamY = jamTop + (availH - game.h * L.cell) / 2
  L.jamCx = L.jamX + (game.w * L.cell) / 2
  L.jamCy = L.jamY + (game.h * L.cell) / 2

  // 车位按最大数量布局（含未解锁），当前已解锁的用 game.slotCount
  const slotGap = Math.max(3, W * 0.009)
  L.slotW = Math.min(58, (W - Math.max(28, W * 0.075) * 2 - slotGap * 6) / MAX_SLOTS)
  const totalW = MAX_SLOTS * L.slotW + (MAX_SLOTS - 1) * slotGap
  const sx = (W - totalW) / 2
  L.slotXs = Array.from({ length: MAX_SLOTS }, (_, i) => sx + i * (L.slotW + slotGap) + L.slotW / 2)
  L.slotYc = L.parkY + L.parkH * 0.48
  L.roadYc = L.roadY + L.roadH / 2

  // 右上车站入口已经存在于背景底板，只保留其坐标作为队列边界。
  L.gate = { x: W * 0.79, y: H * 0.14, w: W * 0.15, h: H * 0.09 }
  L.qFrontX = W * 0.24
  L.qY = H * 0.218
  L.qSpacing = Math.min(20, Math.max(15, W * 0.05))
  L.qMax = Math.max(4, Math.floor((L.gate.x - W * 0.02 - L.qFrontX) / L.qSpacing))
}

// ------------------------------------------------------------------
// 动画状态
// ------------------------------------------------------------------
interface PathAnim {
  pts: { x: number; y: number }[]
  dist: number // 已行进距离
  total: number
  segs: number[] // 每段长度
  angle: number
  kind: 'drive' | 'leave'
}
const carAnims = new Map<number, PathAnim>()
// 碰撞动画：冲出(accelerate) → 撞击(impact: 挤压+火花+顶动被撞车) → 弹回(rebound)
interface Bump {
  carId: number
  freePx: number // 可冲刺距离（到阻挡物贴面）
  start: number
  dir: number
  blockerId?: number // 被撞的车（被顶得晃动）
  impacted: boolean // 撞击瞬间事件是否已触发
}
let bump: Bump | null = null
const BUMP_OUT = 0.16 // 冲刺段时长（加速）
const BUMP_BACK = 0.34 // 回弹段时长（阻尼震荡）

// 冲撞位移曲线：0..1 冲刺（easeIn 加速），1.. 回弹（衰减震荡）
function bumpOffset(el: number, freePx: number): number {
  if (el < BUMP_OUT) {
    const p = el / BUMP_OUT
    return freePx * p * p // 加速冲出
  }
  const p = (el - BUMP_OUT) / BUMP_BACK
  if (p >= 1) return 0
  // 阻尼弹簧回弹：先弹回过头再稳住
  return freePx * Math.exp(-5 * p) * Math.cos(p * Math.PI * 2.2)
}

// 被撞车晃动（撞击后 0.3s 衰减抖动）
function blockerShake(el: number, cell: number): number {
  const p = (el - BUMP_OUT) / 0.3
  if (p < 0 || p >= 1) return 0
  return cell * 0.09 * Math.exp(-4 * p) * Math.sin(p * Math.PI * 4)
}

interface Runner {
  color: number
  x: number
  y: number
  tx: number
  ty: number
  t: number
  dur: number
  carId: number
}
let runners: Runner[] = []
let queueShift = 0 // 队列前移动画偏移
let dispatchTimer = 0

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  color: string
  size: number
  born: number
  life: number
  grav: number
}
let particles: Particle[] = []
let toast: { text: string; until: number } | null = null
let hostPaused = false

function burst(x: number, y: number, color: string, n = 8, speed = 160) {
  for (let i = 0; i < n; i++) {
    const a = (Math.PI * 2 * i) / n + Math.random() * 0.5
    particles.push({
      x,
      y,
      vx: Math.cos(a) * speed * (0.5 + Math.random() * 0.6),
      vy: Math.sin(a) * speed * (0.5 + Math.random() * 0.6),
      color,
      size: 3 + Math.random() * 3,
      born: now(),
      life: 0.4 + Math.random() * 0.3,
      grav: 250,
    })
  }
}

function confetti() {
  const colors = ['#e74c3c', '#3498db', '#f1c40f', '#2ecc71', '#9b59b6', '#e67e22']
  for (let i = 0; i < 100; i++) {
    particles.push({
      x: W / 2 + (Math.random() - 0.5) * W * 0.5,
      y: H * 0.3,
      vx: (Math.random() - 0.5) * 500,
      vy: -Math.random() * 420 - 120,
      color: colors[(Math.random() * colors.length) | 0],
      size: 4 + Math.random() * 6,
      born: now(),
      life: 1.6 + Math.random() * 0.9,
      grav: 850,
    })
  }
}

// ------------------------------------------------------------------
// 关卡流程
// ------------------------------------------------------------------
function startLevel(i: number) {
  levelIdx = i
  curLevel = generateLevel(i)
  game = new JamGame(curLevel)
  over = null
  carAnims.clear()
  bump = null
  runners = []
  particles = []
  queueShift = 0
  dispatchTimer = 0
  computeLayout()
  scene = 'play'
}

// 车阵局部（未旋转）中心坐标
function carCenterLocal(c: Car): { x: number; y: number } {
  const cells = []
  for (let i = 0; i < c.len; i++) {
    cells.push({ x: c.x - DX[c.dir] * i, y: c.y - DY[c.dir] * i })
  }
  const mx = cells.reduce((s, p) => s + p.x, 0) / c.len
  const my = cells.reduce((s, p) => s + p.y, 0) / c.len
  return { x: L.jamX + (mx + 0.5) * L.cell, y: L.jamY + (my + 0.5) * L.cell }
}

// 停车槽里的车辆保持真实长宽比，并按车长缩放到槽内，避免长车被压扁或越界。
function slotCarCell(c: Car): number {
  const widthFit = L.slotW / 0.9
  const heightFit = (L.parkH * 0.76) / (c.len * 0.95)
  return Math.min(L.cell, widthFit, heightFit)
}

// 屏幕坐标（含棋盘旋转）——bump 抖动等少数场景使用
function carCenterScreen(c: Car): { x: number; y: number } {
  const p = carCenterLocal(c)
  return jamToScreen(p.x, p.y)
}
void carCenterScreen

function buildDrivePath(c: Car): { x: number; y: number }[] {
  const c0l = carCenterLocal(c)
  const vx = DX[c.dir]
  const vy = DY[c.dir]
  // 车尾完全离开车阵所需距离（局部坐标计算）
  const gridL = L.jamX
  const gridR = L.jamX + game!.w * L.cell
  const gridT = L.jamY
  const gridB = L.jamY + game!.h * L.cell
  let edgeDist = 0
  if (vx > 0) edgeDist = gridR - c0l.x
  else if (vx < 0) edgeDist = c0l.x - gridL
  else if (vy > 0) edgeDist = gridB - c0l.y
  else edgeDist = c0l.y - gridT
  const d0 = edgeDist + (c.len * L.cell) / 2 + 14
  const p1l = { x: c0l.x + vx * d0, y: c0l.y + vy * d0 }
  // 起点与出场点应用棋盘旋转
  const c0 = jamToScreen(c0l.x, c0l.y)
  const p1 = jamToScreen(p1l.x, p1l.y)

  const slotX = L.slotXs[c.slot]
  const pts = [c0, p1]
  const sideMargin = Math.max(14, W * 0.035)
  const sideX = p1.x < W / 2 ? sideMargin : W - sideMargin

  if (vy < 0) {
    // 向上驶出：直接进路
    pts.push({ x: p1.x, y: L.roadYc })
  } else {
    // 左/右/下驶出：绕边侧廊道上行
    if (vx !== 0) {
      pts.push({ x: vx > 0 ? W - sideMargin : sideMargin, y: p1.y })
      pts.push({ x: vx > 0 ? W - sideMargin : sideMargin, y: L.roadYc })
    } else {
      pts.push({ x: sideX, y: p1.y })
      pts.push({ x: sideX, y: L.roadYc })
    }
  }
  pts.push({ x: slotX, y: L.roadYc })
  pts.push({ x: slotX, y: L.slotYc })
  return dedupe(pts)
}

function buildLeavePath(c: Car): { x: number; y: number }[] {
  const slotX = L.slotXs[c.slot]
  return dedupe([
    { x: slotX, y: L.slotYc },
    { x: slotX, y: L.roadYc },
    { x: W + 100, y: L.roadYc },
  ])
}

function dedupe(pts: { x: number; y: number }[]) {
  return pts.filter((p, i) => i === 0 || Math.hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y) > 2)
}

function startPathAnim(c: Car, kind: 'drive' | 'leave') {
  const pts = kind === 'drive' ? buildDrivePath(c) : buildLeavePath(c)
  const segs = []
  let total = 0
  for (let i = 1; i < pts.length; i++) {
    const d = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    segs.push(d)
    total += d
  }
  const initAngle =
    kind === 'drive' ? (c.dir * Math.PI) / 2 + TILT : Math.PI // 出阵时带棋盘倾角，行驶中平滑转正
  carAnims.set(c.id, { pts, dist: 0, total, segs, angle: initAngle, kind })
}

// 路径上取点
function pathPos(a: PathAnim): { x: number; y: number; segAngle: number } {
  let d = a.dist
  for (let i = 0; i < a.segs.length; i++) {
    if (d <= a.segs[i] || i === a.segs.length - 1) {
      const t = a.segs[i] === 0 ? 1 : Math.min(d / a.segs[i], 1)
      const p0 = a.pts[i]
      const p1 = a.pts[i + 1]
      return {
        x: p0.x + (p1.x - p0.x) * t,
        y: p0.y + (p1.y - p0.y) * t,
        segAngle: Math.atan2(p1.y - p0.y, p1.x - p0.x) + Math.PI / 2,
      }
    }
    d -= a.segs[i]
  }
  const last = a.pts[a.pts.length - 1]
  return { x: last.x, y: last.y, segAngle: a.angle }
}

// ------------------------------------------------------------------
// 每帧逻辑推进
// ------------------------------------------------------------------
function tick(dt: number) {
  if (!game || scene !== 'play') return
  const g = game

  // 车辆路径动画
  for (const [id, a] of [...carAnims]) {
    a.dist += dt * (a.kind === 'drive' ? 560 : 480)
    const p = pathPos(a)
    // 平滑转向
    let diff = p.segAngle - a.angle
    while (diff > Math.PI) diff -= Math.PI * 2
    while (diff < -Math.PI) diff += Math.PI * 2
    a.angle += diff * Math.min(1, dt * 12)
    if (a.dist >= a.total) {
      carAnims.delete(id)
      if (a.kind === 'drive') {
        g.parkCar(id)
        sound.park()
        checkLose()
      } else {
        g.leaveDone(id)
        checkWin()
      }
    }
  }

  // 派乘客
  if (!over) {
    dispatchTimer += dt
    while (dispatchTimer > 0.14) {
      dispatchTimer -= 0.14
      const d = g.dispatchPassenger()
      if (!d) break
      const car = g.carById(d.carId)
      const tx = L.slotXs[car.slot] + (Math.random() - 0.5) * L.slotW * 0.4
      const ty = L.slotYc + (Math.random() - 0.5) * 14
      const fx = L.qFrontX
      const fy = L.qY
      const dur = Math.hypot(tx - fx, ty - fy) / 320
      runners.push({ color: d.color, x: fx, y: fy, tx, ty, t: 0, dur, carId: d.carId })
      queueShift += L.qSpacing
    }
  }
  queueShift = Math.max(0, queueShift - dt * L.qSpacing * 7)

  // 乘客跑动
  runners = runners.filter((r) => {
    r.t += dt
    if (r.t >= r.dur) {
      const res = g.arrivePassenger(r.carId)
      const car = g.carById(r.carId)
      sound.board(car.seats)
      burst(r.tx, r.ty - 10, CAR_COLORS[r.color].body, 5, 90)
      if (res === 'full') {
        startPathAnim(car, 'leave')
        sound.depart()
      }
      checkLose()
      return false
    }
    return true
  })
}

function checkWin() {
  if (game?.won && !over) {
    over = 'win'
    overTime = now()
    progress.done[levelIdx] = true
    progress.unlocked = Math.max(progress.unlocked, Math.min(levelIdx + 2, LEVEL_COUNT))
    saveProgress(progress)
    notifyNativeLevelCompleted(levelIdx + 1)
    confetti()
    sound.win()
  }
}

function checkLose() {
  if (game?.lost && !over) {
    over = 'lose'
    overTime = now()
    sound.lose()
  }
}

// ------------------------------------------------------------------
// 输入
// ------------------------------------------------------------------
let downX = 0
let downY = 0

canvas.addEventListener('pointerdown', (e) => {
  sound.unlock()
  downX = e.clientX
  downY = e.clientY
})

canvas.addEventListener('pointerup', (e) => {
  if (Math.abs(e.clientX - downX) > 14 || Math.abs(e.clientY - downY) > 14) return
  handleTap(e.clientX, e.clientY)
})

function tapJamCar(x: number, y: number) {
  if (!game || over) return
  // 一次只播放一段碰撞反馈；结束后仍可无限次尝试，避免连续点击覆盖动画状态。
  if (bump) return
  // 逆旋转到车阵局部坐标再算格子
  const local = screenToJam(x, y)
  const gx = Math.floor((local.x - L.jamX) / L.cell)
  const gy = Math.floor((local.y - L.jamY) / L.cell)
  const car = game.carAtCell(gx, gy)
  if (!car) return

  if (game.freeSlot() < 0) {
    toast = { text: gameStrings.noParkingSpace, until: now() + 1.2 }
    sound.crash()
    return
  }
  const r = game.tapCar(car.id)
  if (r.kind === 'out') {
    startPathAnim(car, 'drive')
    sound.drive()
  } else if (r.kind === 'bump') {
    // 冲刺到与阻挡物贴面（车头到阻挡格边缘的距离）
    const freePx = Math.max((r.cells - 1) * L.cell + L.cell * 0.1, L.cell * 0.12)
    bump = {
      carId: car.id,
      freePx,
      start: now(),
      dir: car.dir,
      blockerId: r.blockerId,
      impacted: false,
    }
    sound.drive() // 先是引擎冲刺声；撞击声在 impact 帧触发
  }
}

// ---- 激励广告道具 ----
function requestRewardedAction(placement: RewardedAdPlacement, applyReward: () => boolean) {
  if (rewardedAdPending) {
    toast = { text: gameStrings.adLoading, until: now() + 1.2 }
    return
  }
  rewardedAdPending = true
  toast = { text: gameStrings.adLoading, until: now() + 8 }
  const requested = requestNativeRewardedAd(placement, (rewardEarned) => {
    rewardedAdPending = false
    if (!rewardEarned) {
      toast = { text: gameStrings.adNotCompleted, until: now() + 1.6 }
      return
    }
    if (!applyReward()) {
      toast = { text: gameStrings.toolNotNeeded, until: now() + 1.6 }
    }
  })
  if (!requested) {
    rewardedAdPending = false
    toast = { text: gameStrings.adUnavailable, until: now() + 1.6 }
  }
}

function useRefresh() {
  if (!game || over) return
  requestRewardedAction('tool_refresh', () => {
    if (!game || over) return false
    sound.click()
    startLevel(levelIdx)
    return true
  })
}

function useRemove() {
  // 先验证当前一定存在目标，避免用户看完广告却无法得到效果。
  if (!game || over) return
  if (!game.canRemoveBlocker()) {
    toast = { text: gameStrings.noRemovableCar, until: now() + 1.2 }
    return
  }
  requestRewardedAction('tool_remove', () => {
    if (!game || over) return false
    const car = game.removeBlocker()
    if (!car) return false
    startPathAnim(car, 'leave')
    sound.depart()
    return true
  })
}

function useSort() {
  if (!game || over) return
  if (!game.canSortQueue()) {
    toast = { text: gameStrings.noQueueOptimization, until: now() + 1.4 }
    return
  }
  requestRewardedAction('tool_sort', () => {
    if (!game || over || !game.sortQueue()) return false
    sound.click()
    burst(L.qFrontX + 30, L.qY - 20, '#ffd43b', 10, 130)
    return true
  })
}

function unlockSlot(placement: 'slot_unlock' | 'slot_rescue' = 'slot_unlock') {
  if (!game || (over && placement !== 'slot_rescue')) return
  if (game.slotCount >= MAX_SLOTS) return
  requestRewardedAction(placement, () => {
    if (!game || game.slotCount >= MAX_SLOTS) return false
    game.addSlot()
    if (placement === 'slot_rescue' && over === 'lose') over = null
    sound.click()
    burst(L.slotXs[game.slotCount - 1], L.slotYc, '#ffd43b', 12, 150)
    return true
  })
}

const ui: Record<string, Rect> = {}

function handleTap(x: number, y: number) {
  sound.unlock()
  if (scene === 'menu') {
    if (hit(ui.play, x, y)) {
      sound.click()
      scene = 'select'
    }
    return
  }
  if (scene === 'select') {
    if (hit(ui.back, x, y)) {
      sound.click()
      scene = 'menu'
      return
    }
    for (let i = 0; i < LEVEL_COUNT; i++) {
      const r = ui['lv' + i]
      if (r && hit(r, x, y) && i < progress.unlocked) {
        sound.click()
        startLevel(i)
        return
      }
    }
    return
  }
  // play
  if (rewardedAdPending) {
    toast = { text: gameStrings.adLoading, until: now() + 1.2 }
    return
  }
  if (over === 'win') {
    if (hit(ui.next, x, y)) {
      sound.click()
      if (levelIdx + 1 < LEVEL_COUNT) startLevel(levelIdx + 1)
      else if (!exitToNativeGameHome()) scene = 'select'
    } else if (hit(ui.menu, x, y)) {
      sound.click()
      if (!exitToNativeGameHome()) scene = 'select'
    }
    return
  }
  if (over === 'lose') {
    if (ui.rescue && hit(ui.rescue, x, y)) {
      unlockSlot('slot_rescue')
      return
    }
    if (hit(ui.retry, x, y)) {
      sound.click()
      startLevel(levelIdx)
    } else if (hit(ui.menu, x, y)) {
      sound.click()
      if (!exitToNativeGameHome()) scene = 'select'
    }
    return
  }
  if (hit(ui.back, x, y)) {
    sound.click()
    if (!exitToNativeGameHome()) scene = 'select'
    return
  }
  if (ui.restart && hit(ui.restart, x, y)) {
    sound.click()
    startLevel(levelIdx)
    return
  }
  if (ui.soundBtn && hit(ui.soundBtn, x, y)) {
    sound.enabled = !sound.enabled
    sound.click()
    return
  }
  if (ui.refresh && hit(ui.refresh, x, y)) return useRefresh()
  if (ui.remove && hit(ui.remove, x, y)) return useRemove()
  if (ui.sort && hit(ui.sort, x, y)) return useSort()
  if (ui.unlock && hit(ui.unlock, x, y)) return unlockSlot()
  tapJamCar(x, y)
}

// ------------------------------------------------------------------
// 绘制
// ------------------------------------------------------------------
function drawGardenBackground() {
  if (!gardenBackgroundReady) {
    const fallback = ctx.createLinearGradient(0, 0, 0, H)
    fallback.addColorStop(0, '#8edcff')
    fallback.addColorStop(0.35, '#fff1d5')
    fallback.addColorStop(0.88, '#f5e7c8')
    fallback.addColorStop(1, '#8dbd45')
    ctx.fillStyle = fallback
    ctx.fillRect(0, 0, W, H)
    return
  }

  // 等比 cover，绝不非等比拉伸花园、喷泉与车站；超宽屏只裁掉少量上下背景。
  const scale = Math.max(W / gardenBackground.naturalWidth, H / gardenBackground.naturalHeight)
  const dw = gardenBackground.naturalWidth * scale
  const dh = gardenBackground.naturalHeight * scale
  ctx.drawImage(gardenBackground, (W - dw) / 2, (H - dh) / 2, dw, dh)
}

function drawSlotPlanter(cx: number, bottom: number, size: number) {
  const potW = size * 0.44
  const potH = size * 0.28
  ctx.fillStyle = 'rgba(71,48,27,0.2)'
  ctx.beginPath()
  ctx.ellipse(cx + 2, bottom + 2, potW * 0.62, potH * 0.28, 0, 0, Math.PI * 2)
  ctx.fill()
  ctx.fillStyle = '#cda56d'
  ctx.beginPath()
  ctx.moveTo(cx - potW / 2, bottom - potH)
  ctx.lineTo(cx + potW / 2, bottom - potH)
  ctx.lineTo(cx + potW * 0.34, bottom)
  ctx.lineTo(cx - potW * 0.34, bottom)
  ctx.closePath()
  ctx.fill()
  for (const [dx, dy, r, color] of [
    [-0.22, -0.4, 0.27, '#4c9e3f'],
    [0.18, -0.42, 0.3, '#65af46'],
    [0, -0.65, 0.3, '#579f3b'],
  ] as const) {
    ctx.fillStyle = color
    ctx.beginPath()
    ctx.arc(cx + size * dx, bottom + size * dy, size * r, 0, Math.PI * 2)
    ctx.fill()
  }
  for (const [dx, dy, color] of [
    [-0.18, -0.67, '#ff765d'],
    [0.2, -0.55, '#ffd13d'],
    [0.04, -0.82, '#ff8a62'],
  ] as const) {
    ctx.fillStyle = color
    ctx.beginPath()
    ctx.arc(cx + size * dx, bottom + size * dy, size * 0.09, 0, Math.PI * 2)
    ctx.fill()
  }
}

type TopControlIcon = 'back' | 'restart' | 'sound'

function drawCreamCard(r: Rect, radius: number) {
  roundRect(ctx, r.x, r.y + Math.max(3, r.h * 0.09), r.w, r.h, radius)
  ctx.fillStyle = '#c99546'
  ctx.fill()
  const surface = ctx.createLinearGradient(0, r.y, 0, r.y + r.h)
  surface.addColorStop(0, '#fffaf0')
  surface.addColorStop(1, '#f3dfb9')
  roundRect(ctx, r.x, r.y, r.w, r.h, radius)
  ctx.fillStyle = surface
  ctx.fill()
  ctx.strokeStyle = '#fff5d7'
  ctx.lineWidth = Math.max(1.5, r.h * 0.045)
  ctx.stroke()
}

function drawTopControl(r: Rect, icon: TopControlIcon) {
  drawCreamCard(r, Math.max(9, r.h * 0.24))
  const cx = r.x + r.w / 2
  const cy = r.y + r.h / 2
  const s = Math.min(r.w, r.h) * 0.27
  ctx.strokeStyle = '#70452b'
  ctx.fillStyle = '#70452b'
  ctx.lineWidth = Math.max(3, s * 0.32)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  if (icon === 'back') {
    ctx.beginPath()
    ctx.moveTo(cx + s * 0.8, cy)
    ctx.lineTo(cx - s * 0.75, cy)
    ctx.moveTo(cx - s * 0.75, cy)
    ctx.lineTo(cx - s * 0.12, cy - s * 0.62)
    ctx.moveTo(cx - s * 0.75, cy)
    ctx.lineTo(cx - s * 0.12, cy + s * 0.62)
    ctx.stroke()
  } else if (icon === 'restart') {
    ctx.beginPath()
    ctx.arc(cx, cy, s * 0.82, -Math.PI * 0.55, Math.PI * 1.12)
    ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(cx - s * 0.92, cy - s * 0.36)
    ctx.lineTo(cx - s * 0.86, cy + s * 0.34)
    ctx.lineTo(cx - s * 0.28, cy - s * 0.02)
    ctx.closePath()
    ctx.fill()
  } else {
    ctx.beginPath()
    ctx.moveTo(cx - s * 0.86, cy - s * 0.34)
    ctx.lineTo(cx - s * 0.42, cy - s * 0.34)
    ctx.lineTo(cx + s * 0.12, cy - s * 0.78)
    ctx.lineTo(cx + s * 0.12, cy + s * 0.78)
    ctx.lineTo(cx - s * 0.42, cy + s * 0.34)
    ctx.lineTo(cx - s * 0.86, cy + s * 0.34)
    ctx.closePath()
    ctx.fill()
    if (sound.enabled) {
      ctx.beginPath()
      ctx.arc(cx + s * 0.12, cy, s * 0.72, -Math.PI * 0.35, Math.PI * 0.35)
      ctx.stroke()
    } else {
      ctx.beginPath()
      ctx.moveTo(cx + s * 0.35, cy - s * 0.55)
      ctx.lineTo(cx + s * 0.95, cy + s * 0.55)
      ctx.stroke()
    }
  }
}

function drawPlayScene(t: number) {
  if (!game) return
  const g = game
  drawGardenBackground()

  // ---- 候车人数牌与乘客队列 ----
  const signW = Math.min(82, Math.max(62, W * 0.17))
  drawQueueSign(ctx, W * 0.04, H * 0.142, signW, g.queue.length)

  drawQueueRail(ctx, L.qFrontX - 8, L.qY + 3, L.gate.x - L.qFrontX + W * 0.015, Math.max(12, H * 0.018))
  const visible = Math.min(g.queue.length, L.qMax)
  for (let i = visible - 1; i >= 0; i--) {
    const px = L.qFrontX + i * L.qSpacing + queueShift
    drawPerson(ctx, px, L.qY, Math.min(31, Math.max(23, H * 0.035)), CAR_COLORS[g.queue[i]], t * 2.2 + i * 0.8)
  }

  // ---- 停车区 ----
  // 背景底板已包含米色停车台与道路，这里只绘制可交互的车槽。
  for (let i = 0; i < MAX_SLOTS; i++) {
    const sx = L.slotXs[i]
    const x0 = sx - L.slotW / 2
    const y0 = L.parkY + H * 0.004
    const hh = L.parkH - H * 0.009
    if (i < g.slotCount) {
      roundRect(ctx, x0, y0 + 3, L.slotW, hh, Math.max(5, L.slotW * 0.12))
      ctx.fillStyle = 'rgba(93,65,36,0.2)'
      ctx.fill()
      const slotGradient = ctx.createLinearGradient(0, y0, 0, y0 + hh)
      slotGradient.addColorStop(0, '#a9c58c')
      slotGradient.addColorStop(1, '#86ad72')
      roundRect(ctx, x0, y0, L.slotW, hh, Math.max(5, L.slotW * 0.12))
      ctx.fillStyle = slotGradient
      ctx.fill()
      ctx.strokeStyle = '#fff4d3'
      ctx.lineWidth = Math.max(2, W * 0.006)
      ctx.stroke()
      if (!g.cars.some((car) => car.state === 'parked' && car.slot === i)) {
        ctx.fillStyle = 'rgba(255,250,226,0.95)'
        ctx.font = `900 ${Math.min(38, hh * 0.52)}px -apple-system, sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText('P', sx, y0 + hh * 0.46)
        drawSlotPlanter(sx, y0 + hh + 2, Math.min(18, L.slotW * 0.38))
      }
    } else {
      roundRect(ctx, x0, y0 + 3, L.slotW, hh, Math.max(5, L.slotW * 0.12))
      ctx.fillStyle = 'rgba(64,45,30,0.22)'
      ctx.fill()
      roundRect(ctx, x0, y0, L.slotW, hh, Math.max(5, L.slotW * 0.12))
      ctx.fillStyle = '#625f56'
      ctx.fill()
      ctx.strokeStyle = '#e7d8b8'
      ctx.lineWidth = 1.5
      ctx.setLineDash([5, 4])
      ctx.stroke()
      ctx.setLineDash([])
      const bx = sx
      const by = y0 + hh * 0.27
      const adBadgeW = Math.min(34, L.slotW * 0.74)
      const adBadgeH = Math.min(19, hh * 0.25)
      roundRect(ctx, bx - adBadgeW / 2, by - adBadgeH / 2, adBadgeW, adBadgeH, adBadgeH / 2)
      ctx.fillStyle = '#49be50'
      ctx.fill()
      ctx.strokeStyle = '#edf8d4'
      ctx.lineWidth = 2
      ctx.stroke()
      ctx.fillStyle = '#fff'
      ctx.font = `900 ${Math.max(9, adBadgeH * 0.58)}px -apple-system, sans-serif`
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText('AD', bx, by + 0.5)
      // 车槽宽度很窄，仅保留跨语言稳定的 AD 标识和锁图标；点击区域与广告逻辑不变。
      const lockY = y0 + hh * 0.62
      ctx.strokeStyle = '#f4ead1'
      ctx.lineWidth = 2.4
      ctx.beginPath()
      ctx.arc(bx, lockY, 7, Math.PI, 0)
      ctx.stroke()
      roundRect(ctx, bx - 7, lockY, 14, 12, 3)
      ctx.fillStyle = '#f4ead1'
      ctx.fill()
      if (i === g.slotCount) {
        ui.unlock = { x: x0, y: y0, w: L.slotW, h: hh + 6 }
      }
    }
  }
  if (g.slotCount >= MAX_SLOTS) delete ui.unlock
  // 道路中线与左侧斑马线；不绘制方向箭头。
  ctx.strokeStyle = 'rgba(255,244,215,0.82)'
  ctx.lineWidth = Math.max(2, H * 0.004)
  ctx.setLineDash([16, 12])
  ctx.beginPath()
  ctx.moveTo(W * 0.095, L.roadYc)
  ctx.lineTo(W, L.roadYc)
  ctx.stroke()
  ctx.setLineDash([])
  ctx.fillStyle = 'rgba(255,246,220,0.9)'
  const zebraW = W * 0.075
  const zebraX = W * 0.008
  const zebraGap = L.roadH / 6
  for (let i = 0; i < 5; i++) {
    ctx.fillRect(zebraX, L.roadY + zebraGap * (i + 0.45), zebraW, zebraGap * 0.42)
  }

  // ---- 车阵区 ----
  // === 棋盘世界（铺装、水池、障碍、jam 车）===
  ctx.save()
  ctx.translate(L.jamCx, L.jamCy)
  ctx.rotate(TILT)
  ctx.translate(-L.jamCx, -L.jamCy)

  // 异形铺装地面：逐格瓦片 + 棋盘明暗 + 外描边
  const lv = curLevel!
  const paved = (x: number, y: number) =>
    x >= 0 && x < g.w && y >= 0 && y < g.h && lv.mask[y * g.w + x]
  // 底层：整体阴影（向下偏移）
  ctx.fillStyle = 'rgba(82,56,30,0.08)'
  for (let gy = 0; gy < g.h; gy++) {
    for (let gx = 0; gx < g.w; gx++) {
      if (!paved(gx, gy)) continue
      ctx.fillRect(L.jamX + gx * L.cell - 1, L.jamY + gy * L.cell + 4, L.cell + 2, L.cell + 2)
    }
  }
  // 瓦片
  for (let gy = 0; gy < g.h; gy++) {
    for (let gx = 0; gx < g.w; gx++) {
      if (!paved(gx, gy)) continue
      // 让生成底板的真实石材纹理透出来，只叠加极轻的棋盘层次。
      ctx.fillStyle = (gx + gy) % 2 === 0 ? 'rgba(255,250,232,0.2)' : 'rgba(215,191,145,0.12)'
      ctx.fillRect(L.jamX + gx * L.cell, L.jamY + gy * L.cell, L.cell + 0.5, L.cell + 0.5)
    }
  }
  // 外轮廓描边（沿铺装边界）
  ctx.strokeStyle = 'rgba(164,133,86,0.28)'
  ctx.lineWidth = 1.5
  ctx.beginPath()
  for (let gy = 0; gy < g.h; gy++) {
    for (let gx = 0; gx < g.w; gx++) {
      if (!paved(gx, gy)) continue
      const x0 = L.jamX + gx * L.cell
      const y0 = L.jamY + gy * L.cell
      if (!paved(gx, gy - 1)) {
        ctx.moveTo(x0, y0)
        ctx.lineTo(x0 + L.cell, y0)
      }
      if (!paved(gx, gy + 1)) {
        ctx.moveTo(x0, y0 + L.cell)
        ctx.lineTo(x0 + L.cell, y0 + L.cell)
      }
      if (!paved(gx - 1, gy)) {
        ctx.moveTo(x0, y0)
        ctx.lineTo(x0, y0 + L.cell)
      }
      if (!paved(gx + 1, gy)) {
        ctx.moveTo(x0 + L.cell, y0)
        ctx.lineTo(x0 + L.cell, y0 + L.cell)
      }
    }
  }
  ctx.stroke()

  // 中心水池（ring 关卡）
  if (lv.solid.length > 0) {
    let minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9
    for (const s of lv.solid) {
      minX = Math.min(minX, s.x)
      maxX = Math.max(maxX, s.x)
      minY = Math.min(minY, s.y)
      maxY = Math.max(maxY, s.y)
    }
    const pcx = L.jamX + ((minX + maxX + 1) / 2) * L.cell
    const pcy = L.jamY + ((minY + maxY + 1) / 2) * L.cell
    drawPond(ctx, pcx, pcy, ((maxX - minX + 1) / 2) * L.cell * 0.95, ((maxY - minY + 1) / 2) * L.cell * 0.95, t)
  }

  // 障碍物
  for (const o of lv.obstacles) {
    drawObstacle(ctx, o.kind, L.jamX + (o.x + 0.5) * L.cell, L.jamY + (o.y + 0.5) * L.cell, L.cell)
  }

  // ---- 车辆 ----
  // jam 车（仍在旋转坐标系内：用局部坐标画，角度不用加 TILT）
  for (const c of g.cars) {
    if (c.state !== 'jam') continue
    let { x, y } = carCenterLocal(c)
    let squash = 0
    if (bump?.carId === c.id) {
      const el = t - bump.start
      if (el < BUMP_OUT + BUMP_BACK) {
        const off = bumpOffset(el, bump.freePx)
        x += DX[c.dir] * off
        y += DY[c.dir] * off
        // 撞击瞬间：挤压变形 + 火花 + 撞击音
        if (el >= BUMP_OUT) {
          const ip = (el - BUMP_OUT) / 0.22
          if (ip < 1) squash = Math.sin(Math.min(ip, 1) * Math.PI) * 0.9
          if (!bump.impacted) {
            bump.impacted = true
            sound.crash()
            const hpt = jamToScreen(
              x + DX[c.dir] * ((c.len / 2) * L.cell),
              y + DY[c.dir] * ((c.len / 2) * L.cell),
            )
            burst(hpt.x, hpt.y, '#ffd43b', 10, 170)
            burst(hpt.x, hpt.y, '#ffffff', 5, 100)
          }
        }
      } else bump = null
    }
    // 被撞车：撞击后被顶得晃一下
    if (bump?.blockerId === c.id && bump.impacted) {
      const sh = blockerShake(t - bump.start, L.cell)
      x += DX[bump.dir] * sh
      y += DY[bump.dir] * sh
    }
    const pal = c.mystery ? MYSTERY : CAR_COLORS[c.color]
    drawCarSprite(ctx, x, y, L.cell, c.len, (c.dir * Math.PI) / 2, pal, {
      arrow: false,
      mystery: c.mystery,
      squash,
    })
  }

  ctx.restore() // === 结束旋转坐标系 ===

  // 行驶中 / 驶离中（屏幕坐标系，路径起点已含旋转）
  for (const c of g.cars) {
    const a = carAnims.get(c.id)
    if (!a) continue
    const p = pathPos(a)
    const travel = a.total <= 0 ? 1 : Math.min(1, a.dist / a.total)
    const boardCell = Math.min(L.cell, 46)
    const parkedCell = slotCarCell(c)
    const scaleProgress = a.kind === 'drive'
      ? Math.max(0, Math.min(1, (travel - 0.72) / 0.28))
      : Math.max(0, Math.min(1, travel / 0.24))
    const movingCell = a.kind === 'drive'
      ? boardCell + (parkedCell - boardCell) * easeOut(scaleProgress)
      : parkedCell + (boardCell - parkedCell) * easeOut(scaleProgress)
    // 驶离渐隐
    const alpha = a.kind === 'leave' ? Math.max(0.25, 1 - (a.dist / a.total) * 0.6) : 1
    drawCarSprite(ctx, p.x, p.y, movingCell, c.len, a.angle, CAR_COLORS[c.color], {
      arrow: false,
      alpha,
    })
  }
  // 槽内显示真实车辆，不叠加数字气泡，保持视觉稿的干净信息层级。
  for (const c of g.cars) {
    if (c.state !== 'parked') continue
    const sx = L.slotXs[c.slot]
    drawCarSprite(ctx, sx, L.slotYc, slotCarCell(c), c.len, 0, CAR_COLORS[c.color], {})
  }

  // ---- 乘客跑动 ----
  for (const r of runners) {
    const e = easeOut(Math.min(r.t / r.dur, 1))
    const x = r.x + (r.tx - r.x) * e
    const y = r.y + (r.ty - r.y) * e
    drawPerson(ctx, x, y, 24, CAR_COLORS[r.color], t * 16, true)
  }

  // ---- 顶部 UI ----
  const topSize = Math.min(48, Math.max(34, W * 0.1))
  const topY = Math.max(10, H * 0.018)
  const topGap = Math.max(4, W * 0.012)
  ui.back = { x: W * 0.038, y: topY, w: topSize, h: topSize }
  ui.restart = { x: ui.back.x + topSize + topGap, y: topY, w: topSize, h: topSize }
  ui.soundBtn = { x: ui.restart.x + topSize + topGap, y: topY, w: topSize, h: topSize }
  drawTopControl(ui.back, 'back')
  drawTopControl(ui.restart, 'restart')
  drawTopControl(ui.soundBtn, 'sound')

  const titleR: Rect = {
    x: W * 0.38,
    y: topY,
    w: W * 0.27,
    h: topSize,
  }
  drawCreamCard(titleR, Math.max(10, titleR.h * 0.24))
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = `900 ${Math.min(25, titleR.h * 0.48)}px -apple-system, "PingFang SC", sans-serif`
  ctx.fillStyle = '#70452b'
  ctx.fillText(gameStrings.level(levelIdx + 1), titleR.x + titleR.w / 2, titleR.y + titleR.h * 0.52)

  // ---- 提示 ----
  if (toast && t < toast.until) {
    drawPill(
      ctx,
      { x: W / 2 - 90, y: L.roadY + L.roadH + 14, w: 180, h: 36 },
      toast.text,
      { bg: 'rgba(200,50,50,0.9)', font: 17 },
    )
  }

  // ---- 底部道具栏 ----
  drawToolbar(t)

  // ---- 结算弹窗 ----
  if (over === 'win') drawWinOverlay(t)
  else if (over === 'lose') drawLoseOverlay(t)
}

// ImageGen 定稿的奶油金边道具卡：所有入口统一明确标注为激励广告。
function drawToolButton(
  r: Rect,
  label: string,
  icon: (cx: number, cy: number, s: number) => void,
) {
  roundRect(ctx, r.x, r.y + Math.max(5, r.h * 0.08), r.w, r.h, Math.max(14, r.h * 0.2))
  ctx.fillStyle = 'rgba(91,56,25,0.3)'
  ctx.fill()
  const bg = ctx.createLinearGradient(0, r.y, 0, r.y + r.h)
  bg.addColorStop(0, '#fffaf0')
  bg.addColorStop(1, '#f2ddaf')
  roundRect(ctx, r.x, r.y, r.w, r.h, Math.max(14, r.h * 0.2))
  ctx.fillStyle = bg
  ctx.fill()
  ctx.strokeStyle = '#f5c86e'
  ctx.lineWidth = Math.max(2, r.h * 0.035)
  ctx.stroke()
  roundRect(ctx, r.x + 4, r.y + 4, r.w - 8, r.h - 8, Math.max(11, r.h * 0.16))
  ctx.strokeStyle = 'rgba(255,255,244,0.88)'
  ctx.lineWidth = 1.2
  ctx.stroke()
  icon(r.x + r.w / 2, r.y + r.h * 0.37, r.h * 0.27)
  ctx.fillStyle = '#70452b'
  ctx.font = `900 ${r.h * 0.21}px -apple-system, "PingFang SC", sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(label, r.x + r.w / 2, r.y + r.h * 0.79)
  const badgeH = Math.min(22, r.h * 0.23)
  const badgeW = Math.max(32, badgeH * 1.7)
  const badgeX = r.x + r.w - badgeW * 0.82
  const badgeY = r.y - badgeH * 0.18
  roundRect(ctx, badgeX, badgeY, badgeW, badgeH, badgeH / 2)
  ctx.fillStyle = '#45b94d'
  ctx.fill()
  ctx.strokeStyle = '#f8ffe9'
  ctx.lineWidth = 2
  ctx.stroke()
  ctx.fillStyle = '#fff'
  ctx.font = `900 ${Math.max(10, badgeH * 0.54)}px -apple-system, sans-serif`
  ctx.fillText('AD', badgeX + badgeW / 2, badgeY + badgeH / 2 + 0.5)
}

function drawToolbar(t: number) {
  void t
  const bw = Math.min(W * 0.25, 124)
  const bh = Math.min(94, Math.max(70, H * 0.096))
  const gap = Math.max(11, W * 0.035)
  const total = bw * 3 + gap * 2
  const x0 = (W - total) / 2
  const y0 = L.toolbarY

  ui.refresh = { x: x0, y: y0, w: bw, h: bh }
  ui.remove = { x: x0 + bw + gap, y: y0, w: bw, h: bh }
  ui.sort = { x: x0 + (bw + gap) * 2, y: y0, w: bw, h: bh }

  drawToolButton(ui.refresh, gameStrings.refresh, (cx, cy, s) => {
    // 环形双箭头
    ctx.strokeStyle = '#7048e8'
    ctx.lineWidth = s * 0.3
    ctx.lineCap = 'round'
    ctx.beginPath()
    ctx.arc(cx, cy, s * 0.75, -0.4, Math.PI - 0.8)
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(cx, cy, s * 0.75, Math.PI - 0.4, Math.PI * 2 - 0.8)
    ctx.stroke()
    // 箭头头
    for (const a of [Math.PI - 0.8, Math.PI * 2 - 0.8]) {
      const px = cx + Math.cos(a) * s * 0.75
      const py = cy + Math.sin(a) * s * 0.75
      ctx.fillStyle = '#7048e8'
      ctx.beginPath()
      ctx.arc(px, py, s * 0.24, 0, Math.PI * 2)
      ctx.fill()
    }
  })
  drawToolButton(ui.remove, gameStrings.remove, (cx, cy, s) => {
    // 小车 + 叉
    drawCarSprite(ctx, cx - s * 0.2, cy, s * 0.85, 2, Math.PI / 2, CAR_COLORS[4], {})
    ctx.strokeStyle = '#e5484d'
    ctx.lineWidth = s * 0.28
    ctx.lineCap = 'round'
    ctx.beginPath()
    ctx.moveTo(cx + s * 0.5, cy - s * 0.5)
    ctx.lineTo(cx + s * 1.1, cy + 0.1)
    ctx.moveTo(cx + s * 1.1, cy - s * 0.5)
    ctx.lineTo(cx + s * 0.5, cy + 0.1)
    ctx.stroke()
  })
  drawToolButton(ui.sort, gameStrings.sort, (cx, cy, s) => {
    // 双向箭头 + 小人
    ctx.strokeStyle = '#1c7ed6'
    ctx.lineWidth = s * 0.26
    ctx.lineCap = 'round'
    ctx.beginPath()
    ctx.moveTo(cx - s * 1.1, cy - s * 0.3)
    ctx.lineTo(cx + s * 0.1, cy - s * 0.3)
    ctx.moveTo(cx + s * 0.1, cy + s * 0.35)
    ctx.lineTo(cx - s * 1.1, cy + s * 0.35)
    ctx.stroke()
    ctx.fillStyle = '#1c7ed6'
    ctx.beginPath()
    ctx.moveTo(cx + s * 0.45, cy - s * 0.3)
    ctx.lineTo(cx + s * 0.05, cy - s * 0.62)
    ctx.lineTo(cx + s * 0.05, cy + 0.02)
    ctx.closePath()
    ctx.fill()
    ctx.beginPath()
    ctx.moveTo(cx - s * 1.45, cy + s * 0.35)
    ctx.lineTo(cx - s * 1.05, cy + s * 0.03)
    ctx.lineTo(cx - s * 1.05, cy + s * 0.67)
    ctx.closePath()
    ctx.fill()
    drawPerson(ctx, cx + s * 0.95, cy + s * 0.85, s * 1.5, CAR_COLORS[4], 0)
  })
}

function drawWinOverlay(t: number) {
  const p = Math.min((t - overTime) / 0.35, 1)
  const e = easeOut(p)
  ctx.fillStyle = `rgba(10,15,30,${0.55 * e})`
  ctx.fillRect(0, 0, W, H)

  const pw = Math.min(W * 0.82, 330)
  const ph = 250
  const px = (W - pw) / 2
  const py = (H - ph) / 2 - 30 + (1 - e) * 60
  roundRect(ctx, px, py, pw, ph, 24)
  ctx.fillStyle = '#2c3e50'
  ctx.fill()
  ctx.strokeStyle = '#f1c40f'
  ctx.lineWidth = 3
  ctx.stroke()

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#f1c40f'
  ctx.font = 'bold 30px -apple-system, "PingFang SC", sans-serif'
  ctx.fillText(gameStrings.winTitle, W / 2, py + 50)

  ctx.fillStyle = 'rgba(255,255,255,0.78)'
  ctx.font = '16px -apple-system, "PingFang SC", sans-serif'
  ctx.textAlign = 'center'
  ctx.fillText(gameStrings.winMessage, W / 2, py + 96)

  const bw = pw - 60
  ui.next = { x: px + 30, y: py + 124, w: bw, h: 52 }
  drawButton(ctx, ui.next, levelIdx + 1 < LEVEL_COUNT ? gameStrings.nextLevel : gameStrings.home, {
    bg: '#27ae60',
    font: 20,
  })
  ui.menu = { x: px + 30, y: py + 186, w: bw, h: 44 }
  drawButton(ctx, ui.menu, gameStrings.home, { bg: '#34495e', font: 17 })
}

function drawLoseOverlay(t: number) {
  const p = Math.min((t - overTime) / 0.35, 1)
  const e = easeOut(p)
  ctx.fillStyle = `rgba(30,10,10,${0.6 * e})`
  ctx.fillRect(0, 0, W, H)

  const canRescue = game!.slotCount < MAX_SLOTS
  const pw = Math.min(W * 0.82, 330)
  const ph = canRescue ? 306 : 250
  const px = (W - pw) / 2
  const py = (H - ph) / 2 - 30 + (1 - e) * 60
  roundRect(ctx, px, py, pw, ph, 24)
  ctx.fillStyle = '#3d2c2c'
  ctx.fill()
  ctx.strokeStyle = '#e74c3c'
  ctx.lineWidth = 3
  ctx.stroke()

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#ff8787'
  ctx.font = 'bold 28px -apple-system, "PingFang SC", sans-serif'
  ctx.fillText(gameStrings.fullTitle, W / 2, py + 52)
  ctx.fillStyle = 'rgba(255,255,255,0.7)'
  ctx.font = '15px -apple-system, "PingFang SC", sans-serif'
  ctx.fillText(gameStrings.fullMessage, W / 2, py + 92)

  const bw = pw - 60
  let by = py + 122
  if (canRescue) {
    ui.rescue = { x: px + 30, y: by, w: bw, h: 52 }
    drawButton(ctx, ui.rescue, gameStrings.unlockToContinue, { bg: '#2f9e44', font: 17 })
    by += 62
  } else {
    delete ui.rescue
  }
  ui.retry = { x: px + 30, y: by, w: bw, h: 52 }
  drawButton(ctx, ui.retry, gameStrings.retry, { bg: '#e67e22', font: 20 })
  ui.menu = { x: px + 30, y: by + 62, w: bw, h: 44 }
  drawButton(ctx, ui.menu, gameStrings.home, { bg: '#34495e', font: 17 })
}

// ------------------------------------------------------------------
// 菜单 / 选关
// ------------------------------------------------------------------
function drawMenu(t: number) {
  const theme = THEMES[Math.floor(t / 6) % THEMES.length]
  const g = ctx.createLinearGradient(0, 0, 0, H)
  g.addColorStop(0, theme.sky[0])
  g.addColorStop(1, theme.sky[1])
  ctx.fillStyle = g
  ctx.fillRect(0, 0, W, H)
  theme.deco(ctx, 0, H * 0.05, W, H * 0.3, t)

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#fff'
  ctx.strokeStyle = 'rgba(0,0,0,0.35)'
  ctx.lineWidth = 6
  ctx.font = `bold ${Math.min(W * 0.11, 54)}px -apple-system, "PingFang SC", sans-serif`
  ctx.strokeText(gameStrings.gameTitle, W / 2, H * 0.42)
  ctx.fillText(gameStrings.gameTitle, W / 2, H * 0.42)
  ctx.fillStyle = 'rgba(255,255,255,0.9)'
  ctx.font = `${Math.min(W * 0.042, 19)}px -apple-system, "PingFang SC", sans-serif`
  ctx.fillText(gameStrings.gameSubtitle, W / 2, H * 0.49)

  // 巡游的车
  const cs = Math.min(W, H) * 0.1
  const cx = ((t * 70) % (W + cs * 5)) - cs * 2.5
  drawCarSprite(ctx, cx, H * 0.6, cs, 2, Math.PI / 2, CAR_COLORS[1], {})
  drawCarSprite(ctx, W - cx, H * 0.68, cs * 0.85, 3, -Math.PI / 2, CAR_COLORS[0], {})
  // 排队小人
  for (let i = 0; i < 6; i++) {
    drawPerson(ctx, W * 0.28 + i * 26, H * 0.78, 28, CAR_COLORS[i % 6], t * 2 + i)
  }

  const bw = Math.min(W * 0.6, 260)
  ui.play = { x: (W - bw) / 2, y: H * 0.84, w: bw, h: 60 }
  drawButton(ctx, ui.play, gameStrings.startGame, { bg: '#27ae60', font: 24 })

}

function drawSelect(t: number) {
  const g = ctx.createLinearGradient(0, 0, 0, H)
  g.addColorStop(0, '#2c3e50')
  g.addColorStop(1, '#1a2332')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, W, H)

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#fff'
  ctx.font = `bold ${Math.min(W * 0.07, 30)}px -apple-system, "PingFang SC", sans-serif`
  ctx.fillText(gameStrings.selectLevel, W / 2, 46)
  ui.back = { x: 12, y: 24, w: 44, h: 44 }
  drawButton(ctx, ui.back, '←', { bg: '#34495e', font: 21, radius: 13 })

  const cols = 5
  const gap = 12
  const size = Math.min((W - gap * (cols + 1) - 16) / cols, 74)
  const rows = Math.ceil(LEVEL_COUNT / cols)
  const gridW = cols * size + (cols - 1) * gap
  const gridH = rows * size + (rows - 1) * gap
  const startX = (W - gridW) / 2
  const startY = Math.max(88, (H - gridH) / 2)
  const themeColors = ['#27ae60', '#2980b9', '#8e44ad', '#c0392b']

  for (let i = 0; i < LEVEL_COUNT; i++) {
    const cx = startX + (i % cols) * (size + gap)
    const cy = startY + Math.floor(i / cols) * (size + gap)
    const r: Rect = { x: cx, y: cy, w: size, h: size }
    ui['lv' + i] = r
    const unlocked = i < progress.unlocked
    const done = !!progress.done[i]

    roundRect(ctx, cx, cy + 3, size, size, 14)
    ctx.fillStyle = 'rgba(0,0,0,0.4)'
    ctx.fill()
    roundRect(ctx, cx, cy, size, size, 14)
    ctx.fillStyle = unlocked ? themeColors[Math.floor(i / 4) % 4] : '#40495a'
    ctx.fill()

    if (unlocked) {
      ctx.fillStyle = '#fff'
      ctx.font = `bold ${size * 0.36}px -apple-system, sans-serif`
      ctx.fillText(String(i + 1), cx + size / 2, cy + size * (done ? 0.38 : 0.5))
      if (done) {
        ctx.fillStyle = '#a3f7bf'
        ctx.font = `bold ${size * 0.26}px -apple-system, sans-serif`
        ctx.fillText('✓', cx + size / 2, cy + size * 0.72)
      }
    } else {
      ctx.fillStyle = 'rgba(255,255,255,0.4)'
      ctx.font = `${size * 0.36}px -apple-system, sans-serif`
      ctx.fillText('🔒', cx + size / 2, cy + size / 2)
    }
  }
  // 底部小车装饰
  const cs = 30
  const cx2 = ((t * 60) % (W + cs * 6)) - cs * 3
  drawCarSprite(ctx, cx2, H - 40, cs, 2, Math.PI / 2, CAR_COLORS[Math.floor(t / 7) % 6], {})
}

// ------------------------------------------------------------------
// 粒子 & 主循环
// ------------------------------------------------------------------
function drawParticles(t: number, dt: number) {
  particles = particles.filter((pt) => {
    const age = t - pt.born
    if (age > pt.life) return false
    pt.vy += pt.grav * dt
    pt.x += pt.vx * dt
    pt.y += pt.vy * dt
    ctx.globalAlpha = 1 - age / pt.life
    ctx.fillStyle = pt.color
    ctx.fillRect(pt.x - pt.size / 2, pt.y - pt.size / 2, pt.size, pt.size)
    ctx.globalAlpha = 1
    return true
  })
}

let lastT = now()
let firstFrameReported = false
function frame() {
  const t = now()
  const dt = Math.min(t - lastT, 0.05)
  lastT = t

  // Activity 生命周期是游戏运行状态的唯一来源，页面暂停后不继续绘制或更新逻辑。
  if (!hostPaused) {
    tick(dt)
    if (scene === 'menu') drawMenu(t)
    else if (scene === 'select') drawSelect(t)
    else drawPlayScene(t)
    drawParticles(t, dt)
    // 原生窗口只在完整素材首帧后接管；图片失败也算 settled，届时使用矢量降级绘制。
    if (!firstFrameReported && gardenBackgroundSettled && gameSpriteAssetsSettled()) {
      firstFrameReported = true
      notifyNativeFirstFrameRendered()
    }
  }

  requestAnimationFrame(frame)
}

window.CaroutHost = {
  setPaused(paused: boolean) {
    hostPaused = paused
    lastT = now()
  },
  completeRewardedAd(requestId: number, rewardEarned: boolean) {
    completeNativeRewardedAd(requestId, rewardEarned)
  },
}

// 调试入口：?level=5 或 ?lv=5 直接进第 5 关；&auto=1 自动通关。
const dbg = new URLSearchParams(location.search)
const requestedLevel = dbg.get('level') ?? dbg.get('lv')
if (requestedLevel) {
  startLevel(Math.min(Math.max(+requestedLevel, 1), LEVEL_COUNT) - 1)
  // 暴露调试钩子：定位被堵车辆的屏幕坐标
  ;(window as any).__dbg = {
    game: () => game,
    // 找一辆被堵的车，返回其屏幕坐标（用于碰撞测试）
    blockedCarPos: () => {
      if (!game) return null
      for (const c of game.cars) {
        if (c.state !== 'jam') continue
        const r = game.tapCar(c.id)
        if (r.kind === 'bump') {
          const p = carCenterScreen(c)
          return { x: p.x, y: p.y, id: c.id, cells: r.cells }
        }
        if (r.kind === 'out') {
          // 回滚这次意外驶出预定
          game.slots[c.slot] = null
          c.slot = -1
          c.state = 'jam'
        }
      }
      return null
    },
  }
  if (dbg.has('auto') && game) {
    const order = [...(game as JamGame).cars].sort((a, b) => b.id - a.id).map((c) => c.id)
    let i = 0
    const timer = setInterval(() => {
      if (!game || over) return clearInterval(timer)
      if (i >= order.length) return clearInterval(timer)
      const car = (game as JamGame).carById(order[i])
      if (car.state === 'jam' && (game as JamGame).freeSlot() >= 0) {
        const r = (game as JamGame).tapCar(car.id)
        if (r.kind === 'out') {
          startPathAnim(car, 'drive')
          i++
        }
      }
    }, 450)
  }
}
if (hostMode && !requestedLevel) startLevel(0)
frame()
