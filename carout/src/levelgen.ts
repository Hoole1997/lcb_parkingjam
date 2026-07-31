import type { Car, JamLevel, Dir4, Obstacle } from './types'
import { DX, DY, capacityOf } from './types'

// 逆向生成保证可解：按 1..N 依次把车"沿箭头反方向倒入"场内——
// 倒入时它的驶出路径（w.r.t. 已放置的车 + 静态障碍）必须畅通。
// 则按 N..1 顺序点车必然全部驶出（后放的先出，先放的车永不挡它们）。
// 障碍物在放车之前放置并计入占格 → 任何车的驶出路径天然避开障碍。
// 乘客队列按 N..1 的车色展开，再做不重叠的相邻块交换以增加变化。
//
// 高密度填充：多轮扫描随机顺序的候选（格子×方向×车长），
// 贪心放置所有"驶出射线畅通"的车，直到达到目标密度或无法再放。

// mulberry32 种子随机：同一关生成结果对所有玩家一致
export function rng(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const LEVEL_COUNT = 30

type Shape = JamLevel['shape']

interface Cfg {
  w: number
  h: number
  density: number // 车身覆盖铺装格的目标比例
  colors: number
  longRatio: number
  mysteryRatio: number
  shuffle: number
  shape: Shape
  obstacles: number
}

// 关卡节奏：先学基础（矩形），再逐步引入形状变化
const SHAPE_PLAN: Shape[] = [
  'rect', 'rect', 'rect', 'diamond',        // 1-4 入门
  'rect', 'circle', 'rect', 'diamond',      // 5-8
  'cross', 'rect', 'circle', 'ring',        // 9-12
  'rect', 'heart', 'diamond', 'rect',       // 13-16
  'circle', 'cross', 'rect', 'ring',        // 17-20
  'heart', 'rect', 'circle', 'diamond',     // 21-24
  'cross', 'ring', 'rect', 'heart',         // 25-28
  'circle', 'ring',                          // 29-30
]

function cfgFor(idx: number): Cfg {
  const t = idx / (LEVEL_COUNT - 1)
  const shape = SHAPE_PLAN[idx] ?? 'rect'
  // 网格保持较大（竞品观感：车小而密），入门靠密度而非棋盘大小降难度
  let w: number
  let h: number
  if (idx < 2) {
    w = 7
    h = 9
  } else if (idx < 6) {
    w = 8
    h = 11
  } else if (idx < 12) {
    w = 9
    h = 12
  } else {
    w = 10
    h = 13
  }
  if (shape === 'heart') {
    // 心形要奇数宽才有对称双峰
    if (w % 2 === 0) w++
    h = Math.max(h - 2, 9)
  }
  return {
    w,
    h,
    density: 0.55 + t * 0.27, // 车身覆盖率 55% → 82%
    colors: idx < 2 ? 3 : idx < 6 ? 4 : idx < 16 ? 5 : 6,
    longRatio: idx < 3 ? 0.15 : 0.25 + t * 0.15,
    mysteryRatio: idx < 5 ? 0 : 0.08 + t * 0.12,
    shuffle: idx < 3 ? 0 : 0.25 + t * 0.3,
    shape,
    obstacles: idx < 3 ? 1 : Math.min(2 + Math.floor(t * 5), 7),
  }
}

// 形状遮罩：true = 铺装格
function buildMask(shape: Shape, w: number, h: number): { mask: boolean[]; solid: { x: number; y: number }[] } {
  const mask = new Array(w * h).fill(false)
  const solid: { x: number; y: number }[] = []
  const cx = (w - 1) / 2
  const cy = (h - 1) / 2

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const nx = (x - cx) / (w / 2)
      const ny = (y - cy) / (h / 2)
      let inside = true
      if (shape === 'circle') {
        inside = nx * nx + ny * ny <= 1.02
      } else if (shape === 'diamond') {
        inside = Math.abs(nx) + Math.abs(ny) <= 1.08
      } else if (shape === 'ring') {
        const d = nx * nx + ny * ny
        inside = d <= 1.02
        // 中心水池（约 1/3 半径）
        if (d < 0.16) {
          inside = false
          solid.push({ x, y })
        }
      } else if (shape === 'heart') {
        // 心形：上双圆 + 下三角收尖
        const upper =
          Math.min(Math.hypot(nx - 0.45, ny + 0.32), Math.hypot(nx + 0.45, ny + 0.32)) <= 0.58
        const lower =
          ny >= -0.15 && ny <= 1.02 && Math.abs(nx) <= 0.95 * (1 - (ny + 0.15) / 1.15)
        inside = upper || lower
      } else if (shape === 'cross') {
        inside = Math.abs(nx) <= 0.42 || Math.abs(ny) <= 0.42
      }
      mask[y * w + x] = inside
    }
  }
  return { mask, solid }
}

export function generateLevel(idx: number): JamLevel {
  const cfg = cfgFor(idx)
  const rand = rng(9257 + idx * 7919)
  const { w, h, shape } = cfg
  const { mask, solid } = buildMask(shape, w, h)

  const key = (x: number, y: number) => `${x},${y}`
  const inGrid = (x: number, y: number) => x >= 0 && x < w && y >= 0 && y < h
  const paved = (x: number, y: number) => inGrid(x, y) && mask[y * w + x]
  const pavedCount = mask.filter(Boolean).length

  const occ = new Set<string>(solid.map((s) => key(s.x, s.y)))

  // ---- 1. 先放障碍物（计入占格 → 后续车的射线自动避开）----
  const obstacles: Obstacle[] = []
  const kinds: Obstacle['kind'][] = ['cone', 'bush', 'rock', 'hydrant']
  for (let tries = 0; obstacles.length < cfg.obstacles && tries < 100; tries++) {
    const x = Math.floor(rand() * w)
    const y = Math.floor(rand() * h)
    if (!paved(x, y) || occ.has(key(x, y))) continue
    occ.add(key(x, y))
    obstacles.push({ x, y, kind: kinds[Math.floor(rand() * kinds.length)] })
  }

  // ---- 2. 高密度倒车填充 ----
  const cars: Car[] = []
  let covered = 0
  const target = Math.floor((pavedCount - obstacles.length - 0) * cfg.density)

  // 候选池：每个铺装格 × 4 方向；多轮扫描
  const cells: { x: number; y: number }[] = []
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (paved(x, y)) cells.push({ x, y })

  const shuffle = <T,>(arr: T[]) => {
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1))
      const t = arr[i]
      arr[i] = arr[j]
      arr[j] = t
    }
    return arr
  }

  const tryPlace = (hx: number, hy: number, dir: Dir4, len: 2 | 3): boolean => {
    // 车身全在铺装区且未被占
    const bodyCells = []
    for (let i = 0; i < len; i++) {
      const cx = hx - DX[dir] * i
      const cy = hy - DY[dir] * i
      if (!paved(cx, cy) || occ.has(key(cx, cy))) return false
      bodyCells.push({ x: cx, y: cy })
    }
    // 驶出射线（到包围盒边界）不被已占格阻挡
    for (let s = 1; ; s++) {
      const px = hx + DX[dir] * s
      const py = hy + DY[dir] * s
      if (!inGrid(px, py)) break
      if (occ.has(key(px, py))) return false
    }
    bodyCells.forEach((c) => occ.add(key(c.x, c.y)))
    cars.push({
      id: cars.length,
      x: hx,
      y: hy,
      len,
      dir,
      color: Math.floor(rand() * cfg.colors),
      mystery: rand() < cfg.mysteryRatio,
      state: 'jam',
      seats: 0,
      slot: -1,
    })
    covered += len
    return true
  }

  // 多轮：每轮随机顺序扫描所有格子，随机方向序，先试目标车长再试另一种
  for (let pass = 0; pass < 14 && covered < target; pass++) {
    shuffle(cells)
    for (const c of cells) {
      if (covered >= target) break
      if (occ.has(key(c.x, c.y))) continue
      const wantLong = rand() < cfg.longRatio
      const lens: (2 | 3)[] = wantLong ? [3, 2] : [2, 3]
      const dirs = shuffle([0, 1, 2, 3] as Dir4[])
      let placed = false
      for (const len of lens) {
        for (const dir of dirs) {
          if (tryPlace(c.x, c.y, dir, len)) {
            placed = true
            break
          }
        }
        if (placed) break
      }
    }
  }

  // ---- 3. 队列：按可行驶出顺序（倒序放车）展开车色 ----
  const exitOrder = [...cars].reverse()
  const blocks = exitOrder.map((c) => ({ color: c.color, n: capacityOf(c.len) }))
  for (let i = 0; i + 1 < blocks.length; i += 2) {
    if (rand() < cfg.shuffle) {
      const t = blocks[i]
      blocks[i] = blocks[i + 1]
      blocks[i + 1] = t
    }
  }
  const queue: number[] = []
  for (const b of blocks) for (let i = 0; i < b.n; i++) queue.push(b.color)

  return {
    w,
    h,
    cars,
    queue,
    slots: 5,
    theme: Math.floor(idx / 4) % 4,
    mask,
    obstacles,
    solid,
    shape,
  }
}
