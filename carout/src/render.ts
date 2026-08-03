// Canvas 绘制原语：车辆精灵、小人、主题场景、车位、UI 元素

import { gameStrings } from './i18n'

export interface CarPalette {
  body: string
  dark: string
  light: string
  roof?: string // 车顶高台中心色（可选，默认用 light）
}

// 6 色车漆（与乘客颜色一一对应）——饱和明快，接近竞品玩具感
export const CAR_COLORS: CarPalette[] = [
  { body: '#e5484d', dark: '#9f2d33', light: '#ff7b81', roof: '#ff9a9e' }, // 红
  { body: '#3d8bfd', dark: '#2358a8', light: '#7ab8ff', roof: '#9ccaff' }, // 蓝
  { body: '#ffc53d', dark: '#b58415', light: '#ffdd7a', roof: '#ffe9a8' }, // 黄
  { body: '#30c465', dark: '#1a7d3e', light: '#6fe09a', roof: '#95ecb5' }, // 绿
  { body: '#a06ee8', dark: '#6a41a8', light: '#c49bf5', roof: '#d7bafa' }, // 紫
  { body: '#ff8fb5', dark: '#c25580', light: '#ffb8d1', roof: '#ffd2e2' }, // 粉
]
export const MYSTERY: CarPalette = { body: '#9aa5b5', dark: '#626d7f', light: '#c3ccd9', roof: '#d5dce6' }

interface SpriteBox {
  x: number
  y: number
  w: number
  h: number
}

interface SpriteAtlas {
  image: HTMLImageElement
  boxes: readonly SpriteBox[]
  settled: boolean
}

function createSpriteAtlas(source: string, boxes: readonly SpriteBox[]): SpriteAtlas {
  const atlas: SpriteAtlas = { image: new Image(), boxes, settled: false }
  const markSettled = () => {
    atlas.settled = true
  }
  atlas.image.addEventListener('load', markSettled, { once: true })
  atlas.image.addEventListener('error', markSettled, { once: true })
  atlas.image.src = source
  return atlas
}

// ImageGen 生成的精灵图集。每列严格对应 CAR_COLORS 的颜色索引，绘制时只裁取当前列，
// 不为每辆车创建单独纹理，降低请求数量与 WebView 图片对象数量。
const passengerAtlas = createSpriteAtlas('./sprites-v2/passengers-atlas-v2.png', [
  { x: 49, y: 14, w: 193, h: 452 },
  { x: 305, y: 7, w: 193, h: 459 },
  { x: 559, y: 13, w: 192, h: 453 },
  { x: 807, y: 15, w: 192, h: 451 },
  { x: 1056, y: 18, w: 191, h: 448 },
  { x: 1289, y: 27, w: 210, h: 439 },
])
const compactCarAtlas = createSpriteAtlas('./sprites-v2/compact-cars-atlas-v2.png', [
  { x: 53, y: 9, w: 201, h: 410 },
  { x: 300, y: 9, w: 202, h: 410 },
  { x: 547, y: 9, w: 201, h: 410 },
  { x: 793, y: 9, w: 201, h: 410 },
  { x: 1040, y: 9, w: 202, h: 411 },
  { x: 1283, y: 9, w: 202, h: 411 },
])
const longBusAtlas = createSpriteAtlas('./sprites-v2/long-buses-atlas-v2.png', [
  { x: 73, y: 10, w: 183, h: 605 },
  { x: 314, y: 10, w: 186, h: 605 },
  { x: 555, y: 10, w: 186, h: 605 },
  { x: 797, y: 9, w: 186, h: 606 },
  { x: 1040, y: 11, w: 185, h: 604 },
  { x: 1281, y: 11, w: 185, h: 604 },
])

function atlasDrawable(atlas: SpriteAtlas): boolean {
  return atlas.image.complete && atlas.image.naturalWidth > 0
}

/** 首帧需要等待三张本地精灵图结束加载，失败时会自动回退到矢量绘制。 */
export function gameSpriteAssetsSettled(): boolean {
  return passengerAtlas.settled && compactCarAtlas.settled && longBusAtlas.settled
}

export function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

/**
 * 为固定宽度的 Canvas 标签设置单行自适应字体。
 *
 * 每次只测量最大字号一次，再按宽度比例计算目标字号，避免逐像素循环测量影响游戏帧率。
 */
function applyFittedCanvasFont(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  minFontSize: number,
  maxFontSize: number,
  fontFamily: string,
  fontWeight = '900',
) {
  const safeMin = Math.max(1, Math.min(minFontSize, maxFontSize))
  const safeMax = Math.max(safeMin, maxFontSize)
  ctx.font = `${fontWeight} ${safeMax}px ${fontFamily}`
  const measuredWidth = Math.max(1, ctx.measureText(text).width)
  const fittedSize = Math.max(safeMin, Math.min(safeMax, safeMax * Math.max(1, maxWidth) / measuredWidth))
  ctx.font = `${fontWeight} ${fittedSize}px ${fontFamily}`
}

// ------------------------------------------------------------------
// 车辆精灵：以 (cx,cy) 为中心，车头朝 angle 方向（0 = 朝上），len 节车身
// ------------------------------------------------------------------
export function drawCarSprite(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  cell: number,
  len: number,
  angle: number,
  pal: CarPalette,
  opts: { arrow?: boolean; mystery?: boolean; alpha?: number; squash?: number } = {},
) {
  const { arrow = false, mystery = false, alpha = 1, squash = 0 } = opts
  ctx.save()
  ctx.translate(cx, cy)
  ctx.rotate(angle)
  ctx.globalAlpha = alpha

  const w = cell * 0.8 * (1 + squash * 0.12)
  const h = cell * len * 0.95 * (1 - squash * 0.1)
  const x = -w / 2
  const y = -h / 2
  const r = Math.min(w * 0.28, cell * 0.2)
  const isLong = len >= 3
  const lift = cell * 0.16 // 挤出高度（竞品式立体感）

  const colorIndex = CAR_COLORS.indexOf(pal)
  const atlas = isLong ? longBusAtlas : compactCarAtlas
  if (!mystery && colorIndex >= 0 && atlasDrawable(atlas)) {
    const sprite = atlas.boxes[colorIndex]
    // 阴影由运行时绘制，素材本身保持干净透明，移动和旋转时不会出现方形底色。
    roundRect(ctx, -w * 0.46 + cell * 0.08, -h * 0.48 + cell * 0.12, w * 0.92, h * 0.96, r)
    ctx.fillStyle = 'rgba(61,43,28,0.27)'
    ctx.fill()

    const targetW = cell * 0.92 * (1 + squash * 0.12)
    const targetH = cell * len * 0.98 * (1 - squash * 0.1)
    const scale = Math.min(targetW / sprite.w, targetH / sprite.h)
    const drawW = sprite.w * scale
    const drawH = sprite.h * scale
    ctx.drawImage(
      atlas.image,
      sprite.x,
      sprite.y,
      sprite.w,
      sprite.h,
      -drawW / 2,
      -drawH / 2,
      drawW,
      drawH,
    )
    ctx.restore()
    return
  }

  // ---- 地面阴影（右下偏移）----
  roundRect(ctx, x + cell * 0.1, y + cell * 0.12, w, h, r)
  ctx.fillStyle = 'rgba(0,0,0,0.32)'
  ctx.fill()

  // ---- 车轮（底层探出）----
  ctx.fillStyle = '#1f232b'
  const wheelW = w * 0.13
  const wheelH = h * (isLong ? 0.09 : 0.13)
  const wheelYs = isLong
    ? [y + h * 0.13, y + h * 0.45, y + h * 0.77]
    : [y + h * 0.15, y + h * 0.69]
  for (const wy of wheelYs) {
    roundRect(ctx, x - wheelW * 0.4, wy, wheelW, wheelH, wheelW * 0.35)
    ctx.fill()
    roundRect(ctx, x + w - wheelW * 0.6, wy, wheelW, wheelH, wheelW * 0.35)
    ctx.fill()
  }

  // ---- 车身侧壁（深色，挤出感的"墙"）----
  roundRect(ctx, x, y, w, h, r)
  ctx.fillStyle = pal.dark
  ctx.fill()

  // ---- 车身顶面（上移 lift，亮色渐变）----
  const ty = y - lift
  const bodyG = ctx.createLinearGradient(x, 0, x + w, 0)
  bodyG.addColorStop(0, pal.body)
  bodyG.addColorStop(0.45, pal.light)
  bodyG.addColorStop(1, pal.body)
  roundRect(ctx, x, ty, w, h, r)
  ctx.fillStyle = bodyG
  ctx.fill()
  ctx.strokeStyle = 'rgba(25,30,45,0.35)'
  ctx.lineWidth = Math.max(1, cell * 0.02)
  ctx.stroke()

  // ---- 前挡风玻璃（顶面上的深色梯形带）----
  const glass = (gy: number, gh: number, wTop: number, wBot: number) => {
    ctx.beginPath()
    ctx.moveTo(-wTop / 2, gy)
    ctx.lineTo(wTop / 2, gy)
    ctx.lineTo(wBot / 2, gy + gh)
    ctx.lineTo(-wBot / 2, gy + gh)
    ctx.closePath()
    const gg = ctx.createLinearGradient(0, gy, 0, gy + gh)
    gg.addColorStop(0, '#bfe3f7')
    gg.addColorStop(1, '#6da9cc')
    ctx.fillStyle = gg
    ctx.fill()
    // 高光斜条
    ctx.save()
    ctx.clip()
    ctx.fillStyle = 'rgba(255,255,255,0.5)'
    ctx.beginPath()
    ctx.moveTo(-wTop * 0.42, gy)
    ctx.lineTo(-wTop * 0.12, gy)
    ctx.lineTo(-wBot * 0.28, gy + gh)
    ctx.lineTo(-wBot * 0.46, gy + gh)
    ctx.closePath()
    ctx.fill()
    ctx.restore()
  }
  glass(ty + h * 0.13, h * (isLong ? 0.09 : 0.12), w * 0.66, w * 0.82)

  // ---- 车顶高台（第二层挤出：像竞品那样车顶再抬一层）----
  const roofY = ty + h * (isLong ? 0.26 : 0.3)
  const roofH = h * (isLong ? 0.52 : 0.4)
  const roofW = w * 0.8
  const roofLift = cell * 0.05
  // 高台侧壁
  roundRect(ctx, -roofW / 2, roofY, roofW, roofH, r * 0.75)
  ctx.fillStyle = pal.dark
  ctx.fill()
  // 高台顶面
  const roofG = ctx.createLinearGradient(-roofW / 2, 0, roofW / 2, 0)
  roofG.addColorStop(0, pal.light)
  roofG.addColorStop(0.5, pal.roof ?? pal.light)
  roofG.addColorStop(1, pal.light)
  roundRect(ctx, -roofW / 2, roofY - roofLift, roofW, roofH, r * 0.75)
  ctx.fillStyle = roofG
  ctx.fill()

  // ---- 侧窗（顶面两侧的深色条）----
  ctx.fillStyle = '#8fc8e8'
  const swH = roofH * (isLong ? 0.2 : 0.32)
  for (const sx of [-1, 1]) {
    roundRect(ctx, sx * w * 0.435 - w * 0.045, roofY - roofLift + roofH * 0.1, w * 0.09, swH, w * 0.03)
    ctx.fill()
    if (isLong) {
      roundRect(ctx, sx * w * 0.435 - w * 0.045, roofY - roofLift + roofH * 0.56, w * 0.09, swH, w * 0.03)
      ctx.fill()
    }
  }

  // ---- 后窗 ----
  glass(roofY + roofH + h * 0.025, h * (isLong ? 0.06 : 0.09), w * 0.74, w * 0.62)

  // ---- 车灯 ----
  const lr = Math.max(2, cell * 0.055)
  for (const sx of [-1, 1]) {
    ctx.beginPath()
    ctx.ellipse(sx * w * 0.3, ty + h * 0.04, lr * 1.1, lr * 0.75, 0, 0, Math.PI * 2)
    ctx.fillStyle = '#fff6cf'
    ctx.fill()
  }
  for (const sx of [-1, 1]) {
    roundRect(ctx, sx * w * 0.35 - lr * 0.7, ty + h - h * 0.05 - lr * 0.45, lr * 1.4, lr * 0.9, lr * 0.35)
    ctx.fillStyle = '#e5484d'
    ctx.fill()
  }

  // ---- 车顶标识：大箭头（竞品式，几乎占满车顶）或 ? ----
  if (mystery) {
    ctx.save()
    ctx.translate(0, roofY + roofH * 0.5 - roofLift)
    ctx.rotate(-angle)
    ctx.fillStyle = 'rgba(255,255,255,0.95)'
    ctx.font = `bold ${cell * 0.55}px -apple-system, sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('?', 0, 0)
    ctx.restore()
  } else if (arrow) {
    // 大箭头：宽箭杆 + 大三角头，带淡阴影
    const ay0 = roofY - roofLift + roofH * 0.08 // 箭头尖
    const ay1 = roofY - roofLift + roofH * 0.92 // 箭杆底
    const headW = roofW * 0.44
    const headH = (ay1 - ay0) * 0.42
    const shaftW = roofW * 0.22
    const draw = (dx: number, dy: number, style: string) => {
      ctx.fillStyle = style
      ctx.beginPath()
      ctx.moveTo(dx, ay0 + dy)
      ctx.lineTo(dx + headW, ay0 + headH + dy)
      ctx.lineTo(dx + shaftW, ay0 + headH + dy)
      ctx.lineTo(dx + shaftW, ay1 + dy)
      ctx.lineTo(dx - shaftW, ay1 + dy)
      ctx.lineTo(dx - shaftW, ay0 + headH + dy)
      ctx.lineTo(dx - headW, ay0 + headH + dy)
      ctx.closePath()
      ctx.fill()
    }
    draw(cell * 0.02, cell * 0.03, 'rgba(0,0,0,0.18)') // 阴影
    draw(0, 0, '#ffffff')
  }

  ctx.restore()
}

// ------------------------------------------------------------------
// 小人：圆头 + 渐变胶囊身 + 手臂 + 眼睛，站立微摆 / 奔跑摆臂
// ------------------------------------------------------------------
export function drawPerson(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number, // 脚底中心
  size: number, // 身高
  pal: CarPalette,
  bob = 0, // 相位：站立时慢（微摆），奔跑时快（弹跳+摆臂）
  running = false,
) {
  const hop = Math.abs(Math.sin(bob)) * size * (running ? 0.14 : 0.05)
  const by = y - hop
  const sway = running ? 0 : Math.sin(bob * 0.7) * size * 0.02
  ctx.save()
  const colorIndex = CAR_COLORS.indexOf(pal)
  if (colorIndex >= 0 && atlasDrawable(passengerAtlas)) {
    const sprite = passengerAtlas.boxes[colorIndex]
    const drawH = size * 1.08
    const drawW = drawH * (sprite.w / sprite.h)
    ctx.fillStyle = 'rgba(62,43,27,0.2)'
    ctx.beginPath()
    ctx.ellipse(x, y, drawW * 0.34, size * 0.07, 0, 0, Math.PI * 2)
    ctx.fill()
    ctx.translate(x + sway, by)
    ctx.rotate(running ? Math.sin(bob) * 0.07 : Math.sin(bob * 0.55) * 0.018)
    ctx.drawImage(
      passengerAtlas.image,
      sprite.x,
      sprite.y,
      sprite.w,
      sprite.h,
      -drawW / 2,
      -drawH,
      drawW,
      drawH,
    )
    ctx.restore()
    return
  }
  // 影子
  ctx.fillStyle = 'rgba(0,0,0,0.22)'
  ctx.beginPath()
  ctx.ellipse(x, y, size * 0.24, size * 0.08, 0, 0, Math.PI * 2)
  ctx.fill()

  const bw = size * 0.44
  const bh = size * 0.56
  const bodyTop = by - bh

  // 腿（奔跑时前后摆）
  if (running) {
    const lsw = Math.sin(bob) * size * 0.12
    ctx.strokeStyle = pal.dark
    ctx.lineWidth = size * 0.11
    ctx.lineCap = 'round'
    ctx.beginPath()
    ctx.moveTo(x - bw * 0.2, by - bh * 0.15)
    ctx.lineTo(x - bw * 0.2 + lsw, by)
    ctx.moveTo(x + bw * 0.2, by - bh * 0.15)
    ctx.lineTo(x + bw * 0.2 - lsw, by)
    ctx.stroke()
  }

  // 手臂（摆动）
  const asw = Math.sin(bob + (running ? Math.PI : 0)) * (running ? size * 0.16 : size * 0.03)
  ctx.strokeStyle = pal.body
  ctx.lineWidth = size * 0.1
  ctx.lineCap = 'round'
  ctx.beginPath()
  ctx.moveTo(x - bw * 0.52, bodyTop + bh * 0.28)
  ctx.lineTo(x - bw * 0.62 - asw * 0.4, bodyTop + bh * 0.62 + asw)
  ctx.moveTo(x + bw * 0.52, bodyTop + bh * 0.28)
  ctx.lineTo(x + bw * 0.62 + asw * 0.4, bodyTop + bh * 0.62 - asw)
  ctx.stroke()

  // 身体（左右渐变胶囊）
  const bg = ctx.createLinearGradient(x - bw / 2, 0, x + bw / 2, 0)
  bg.addColorStop(0, pal.dark)
  bg.addColorStop(0.4, pal.body)
  bg.addColorStop(1, pal.body)
  roundRect(ctx, x - bw / 2 + sway, bodyTop, bw, bh, bw / 2)
  ctx.fillStyle = bg
  ctx.fill()

  // 头（肤色由 light 渐变）
  const hr = size * 0.21
  const hx = x + sway * 1.4
  const hy = bodyTop - size * 0.14
  const hg = ctx.createRadialGradient(hx - hr * 0.35, hy - hr * 0.35, hr * 0.2, hx, hy, hr)
  hg.addColorStop(0, '#ffe8d6')
  hg.addColorStop(1, pal.light)
  ctx.beginPath()
  ctx.arc(hx, hy, hr, 0, Math.PI * 2)
  ctx.fillStyle = hg
  ctx.fill()
  // 头发（顶部小弧）
  ctx.beginPath()
  ctx.arc(hx, hy - hr * 0.25, hr * 0.82, Math.PI * 1.05, Math.PI * 1.95)
  ctx.strokeStyle = pal.dark
  ctx.lineWidth = hr * 0.5
  ctx.stroke()
  // 眼睛（朝行进方向偏右）
  ctx.fillStyle = '#2d3436'
  const eo = running ? hr * 0.25 : 0
  ctx.beginPath()
  ctx.arc(hx - hr * 0.28 + eo, hy + hr * 0.05, hr * 0.13, 0, Math.PI * 2)
  ctx.arc(hx + hr * 0.28 + eo, hy + hr * 0.05, hr * 0.13, 0, Math.PI * 2)
  ctx.fill()
  ctx.restore()
}

// ------------------------------------------------------------------
// 障碍物（占据车阵格子的装饰物）
// ------------------------------------------------------------------
export type ObstacleKind = 'cone' | 'bush' | 'rock' | 'hydrant'

export function drawObstacle(
  ctx: CanvasRenderingContext2D,
  kind: ObstacleKind,
  cx: number,
  cy: number,
  cell: number,
) {
  const s = cell * 0.5
  ctx.save()
  // 通用影子
  ctx.fillStyle = 'rgba(0,0,0,0.22)'
  ctx.beginPath()
  ctx.ellipse(cx, cy + s * 0.55, s * 0.6, s * 0.2, 0, 0, Math.PI * 2)
  ctx.fill()

  if (kind === 'cone') {
    // 底座
    roundRect(ctx, cx - s * 0.58, cy + s * 0.36, s * 1.16, s * 0.2, s * 0.08)
    ctx.fillStyle = '#c44d0e'
    ctx.fill()
    // 锥体（渐变）
    const cg = ctx.createLinearGradient(cx - s * 0.4, 0, cx + s * 0.4, 0)
    cg.addColorStop(0, '#c44d0e')
    cg.addColorStop(0.45, '#f97316')
    cg.addColorStop(1, '#c44d0e')
    ctx.beginPath()
    ctx.moveTo(cx, cy - s * 0.75)
    ctx.quadraticCurveTo(cx + s * 0.13, cy - s * 0.1, cx + s * 0.42, cy + s * 0.4)
    ctx.lineTo(cx - s * 0.42, cy + s * 0.4)
    ctx.quadraticCurveTo(cx - s * 0.13, cy - s * 0.1, cx, cy - s * 0.75)
    ctx.fillStyle = cg
    ctx.fill()
    // 反光条 ×2
    ctx.fillStyle = '#fff7ed'
    for (const [ry, rw] of [
      [0.02, 0.27],
      [-0.32, 0.17],
    ] as const) {
      ctx.beginPath()
      ctx.moveTo(cx - s * rw, cy + s * ry)
      ctx.lineTo(cx + s * rw, cy + s * ry)
      ctx.lineTo(cx + s * (rw + 0.05), cy + s * (ry + 0.16))
      ctx.lineTo(cx - s * (rw + 0.05), cy + s * (ry + 0.16))
      ctx.closePath()
      ctx.fill()
    }
  } else if (kind === 'bush') {
    // 灌木丛：三球叠加 + 高光 + 小花
    for (const [dx, dy, r, c] of [
      [-0.35, 0.15, 0.42, '#1e8449'],
      [0.35, 0.12, 0.44, '#229954'],
      [0, -0.18, 0.52, '#27ae60'],
    ] as const) {
      ctx.beginPath()
      ctx.arc(cx + s * dx, cy + s * dy, s * r, 0, Math.PI * 2)
      ctx.fillStyle = c
      ctx.fill()
    }
    ctx.fillStyle = 'rgba(255,255,255,0.22)'
    ctx.beginPath()
    ctx.arc(cx - s * 0.15, cy - s * 0.38, s * 0.2, 0, Math.PI * 2)
    ctx.fill()
    // 小花
    for (const [fx, fy, fc] of [
      [-0.4, -0.05, '#ff8fa3'],
      [0.3, -0.28, '#ffd43b'],
      [0.45, 0.22, '#ff8fa3'],
    ] as const) {
      ctx.fillStyle = fc
      for (let i = 0; i < 5; i++) {
        const a = (Math.PI * 2 * i) / 5
        ctx.beginPath()
        ctx.arc(cx + s * fx + Math.cos(a) * s * 0.07, cy + s * fy + Math.sin(a) * s * 0.07, s * 0.05, 0, Math.PI * 2)
        ctx.fill()
      }
      ctx.fillStyle = '#fff'
      ctx.beginPath()
      ctx.arc(cx + s * fx, cy + s * fy, s * 0.05, 0, Math.PI * 2)
      ctx.fill()
    }
  } else if (kind === 'rock') {
    // 岩石：多边形 + 面分层
    ctx.beginPath()
    ctx.moveTo(cx - s * 0.55, cy + s * 0.4)
    ctx.lineTo(cx - s * 0.62, cy - s * 0.05)
    ctx.lineTo(cx - s * 0.25, cy - s * 0.5)
    ctx.lineTo(cx + s * 0.3, cy - s * 0.45)
    ctx.lineTo(cx + s * 0.6, cy - s * 0.02)
    ctx.lineTo(cx + s * 0.5, cy + s * 0.4)
    ctx.closePath()
    ctx.fillStyle = '#8e9aaf'
    ctx.fill()
    ctx.strokeStyle = '#5c677d'
    ctx.lineWidth = 1.5
    ctx.stroke()
    // 亮面
    ctx.beginPath()
    ctx.moveTo(cx - s * 0.25, cy - s * 0.5)
    ctx.lineTo(cx + s * 0.3, cy - s * 0.45)
    ctx.lineTo(cx + s * 0.15, cy - s * 0.05)
    ctx.lineTo(cx - s * 0.3, cy - s * 0.02)
    ctx.closePath()
    ctx.fillStyle = '#c9d3e0'
    ctx.fill()
    // 裂纹
    ctx.strokeStyle = 'rgba(60,70,90,0.4)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(cx - s * 0.1, cy + s * 0.05)
    ctx.lineTo(cx + s * 0.05, cy + s * 0.22)
    ctx.stroke()
  } else {
    // 消防栓
    const hg = ctx.createLinearGradient(cx - s * 0.3, 0, cx + s * 0.3, 0)
    hg.addColorStop(0, '#c0392b')
    hg.addColorStop(0.45, '#e74c3c')
    hg.addColorStop(1, '#c0392b')
    // 底座
    roundRect(ctx, cx - s * 0.34, cy + s * 0.3, s * 0.68, s * 0.18, s * 0.06)
    ctx.fillStyle = '#a93226'
    ctx.fill()
    // 身体
    roundRect(ctx, cx - s * 0.26, cy - s * 0.42, s * 0.52, s * 0.78, s * 0.2)
    ctx.fillStyle = hg
    ctx.fill()
    // 侧接口
    ctx.fillStyle = '#f1c40f'
    ctx.beginPath()
    ctx.arc(cx - s * 0.3, cy - s * 0.05, s * 0.11, 0, Math.PI * 2)
    ctx.arc(cx + s * 0.3, cy - s * 0.05, s * 0.11, 0, Math.PI * 2)
    ctx.fill()
    // 顶帽
    ctx.beginPath()
    ctx.arc(cx, cy - s * 0.42, s * 0.2, Math.PI, 0)
    ctx.fillStyle = '#f1c40f'
    ctx.fill()
    ctx.beginPath()
    ctx.arc(cx, cy - s * 0.58, s * 0.07, 0, Math.PI * 2)
    ctx.fill()
    // 高光
    ctx.fillStyle = 'rgba(255,255,255,0.35)'
    roundRect(ctx, cx - s * 0.16, cy - s * 0.34, s * 0.09, s * 0.5, s * 0.04)
    ctx.fill()
  }
  ctx.restore()
}

// 水池（环形关卡中心装饰，占多格）
export function drawPond(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  rw: number,
  rh: number,
  t: number,
) {
  ctx.save()
  // 外圈石沿
  ctx.beginPath()
  ctx.ellipse(cx, cy, rw, rh, 0, 0, Math.PI * 2)
  ctx.fillStyle = '#b8c4d6'
  ctx.fill()
  ctx.strokeStyle = '#8494ad'
  ctx.lineWidth = 2
  ctx.stroke()
  // 水面
  const wg = ctx.createRadialGradient(cx - rw * 0.2, cy - rh * 0.25, rw * 0.1, cx, cy, rw)
  wg.addColorStop(0, '#74c0fc')
  wg.addColorStop(1, '#339af0')
  ctx.beginPath()
  ctx.ellipse(cx, cy, rw * 0.85, rh * 0.82, 0, 0, Math.PI * 2)
  ctx.fillStyle = wg
  ctx.fill()
  // 波纹
  ctx.strokeStyle = 'rgba(255,255,255,0.5)'
  ctx.lineWidth = 1.5
  for (let i = 0; i < 2; i++) {
    const ph = ((t * 0.35 + i * 0.5) % 1)
    ctx.globalAlpha = 1 - ph
    ctx.beginPath()
    ctx.ellipse(cx, cy, rw * 0.25 + rw * 0.5 * ph, (rh * 0.25 + rh * 0.5 * ph) * 0.9, 0, 0, Math.PI * 2)
    ctx.stroke()
  }
  ctx.globalAlpha = 1
  // 荷叶
  ctx.fillStyle = '#40c057'
  ctx.beginPath()
  ctx.ellipse(cx + rw * 0.35, cy + rh * 0.2, rw * 0.16, rh * 0.13, 0.3, 0.25, Math.PI * 2)
  ctx.fill()
  ctx.restore()
}

// ------------------------------------------------------------------
// 主题场景（顶部装饰带）
// ------------------------------------------------------------------
export interface Theme {
  name: string
  sky: string[]
  ground: string
  road: string
  jamBg: string
  jamLine: string
  tileA: string // 铺装瓦片棋盘色 A
  tileB: string
  jamEdge: string // 铺装外轮廓描边
  deco: (ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, t: number) => void
}

function tree(ctx: CanvasRenderingContext2D, x: number, y: number, s: number, leaf = '#27ae60') {
  ctx.fillStyle = '#7d5a3c'
  ctx.fillRect(x - s * 0.07, y - s * 0.35, s * 0.14, s * 0.35)
  for (const [dx, dy, r] of [
    [0, -s * 0.62, s * 0.3],
    [-s * 0.22, -s * 0.42, s * 0.24],
    [s * 0.22, -s * 0.42, s * 0.24],
  ] as const) {
    ctx.beginPath()
    ctx.arc(x + dx, y + dy, r, 0, Math.PI * 2)
    ctx.fillStyle = leaf
    ctx.fill()
  }
}

function cloud(ctx: CanvasRenderingContext2D, x: number, y: number, s: number, a = 0.85) {
  ctx.save()
  ctx.globalAlpha = a
  ctx.fillStyle = '#ffffff'
  for (const [dx, dy, r] of [
    [0, 0, s],
    [s * 0.9, s * 0.1, s * 0.75],
    [-s * 0.9, s * 0.15, s * 0.7],
  ] as const) {
    ctx.beginPath()
    ctx.arc(x + dx, y + dy, r, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.restore()
}

export const THEMES: Theme[] = [
  {
    name: '阳光公园',
    sky: ['#f8f3d7', '#dff0c4'],
    ground: '#a9d98d',
    road: '#697876',
    jamBg: '#8bc77e',
    jamLine: 'rgba(255,255,239,0.55)',
    tileA: '#f3e8c9',
    tileB: '#eadcba',
    jamEdge: '#c8b98d',
    deco(ctx, x, y, w, h, t) {
      cloud(ctx, x + w * 0.2 + Math.sin(t * 0.1) * 12, y + h * 0.2, h * 0.1)
      cloud(ctx, x + w * 0.65 + Math.cos(t * 0.08) * 14, y + h * 0.14, h * 0.08)
      tree(ctx, x + w * 0.1, y + h * 0.82, h * 0.75)
      tree(ctx, x + w * 0.3, y + h * 0.8, h * 0.55, '#2f9e44')
      tree(ctx, x + w * 0.52, y + h * 0.82, h * 0.65)
      // 栅栏
      ctx.fillStyle = '#d9a066'
      for (let i = 0; i < 14; i++) ctx.fillRect(x + w * 0.02 + i * w * 0.07, y + h * 0.62, 3, h * 0.16)
      ctx.fillRect(x, y + h * 0.66, w, 3)
    },
  },
  {
    name: '碧海沙滩',
    sky: ['#4dabf7', '#99e9f2'],
    ground: '#ffe8a3',
    road: '#8d99ae',
    jamBg: '#e9c46a',
    jamLine: 'rgba(255,255,255,0.5)',
    tileA: '#f3d9a4',
    tileB: '#ecd096',
    jamEdge: '#c9a86a',
    deco(ctx, x, y, w, h, t) {
      // 海
      ctx.fillStyle = '#22b8cf'
      ctx.fillRect(x, y + h * 0.36, w, h * 0.24)
      ctx.strokeStyle = 'rgba(255,255,255,0.6)'
      ctx.lineWidth = 2
      for (let i = 0; i < 4; i++) {
        const wy = y + h * 0.42 + i * h * 0.05 + Math.sin(t * 2 + i) * 2
        ctx.beginPath()
        ctx.moveTo(x + w * 0.05 + i * w * 0.22, wy)
        ctx.quadraticCurveTo(x + w * 0.12 + i * w * 0.22, wy - 4, x + w * 0.19 + i * w * 0.22, wy)
        ctx.stroke()
      }
      // 遮阳伞
      const ux = x + w * 0.16
      const uy = y + h * 0.8
      ctx.strokeStyle = '#e8590c'
      ctx.lineWidth = 3
      ctx.beginPath()
      ctx.moveTo(ux, uy)
      ctx.lineTo(ux, uy - h * 0.34)
      ctx.stroke()
      ctx.beginPath()
      ctx.arc(ux, uy - h * 0.34, h * 0.22, Math.PI, 0)
      ctx.fillStyle = '#ff6b6b'
      ctx.fill()
      cloud(ctx, x + w * 0.6 + Math.sin(t * 0.12) * 10, y + h * 0.16, h * 0.09)
    },
  },
  {
    name: '霓虹夜城',
    sky: ['#1a1b4b', '#3b2f63'],
    ground: '#40405c',
    road: '#2f2f45',
    jamBg: '#4a4e69',
    jamLine: 'rgba(255,255,255,0.3)',
    tileA: '#5e6382',
    tileB: '#565b78',
    jamEdge: '#3c405c',
    deco(ctx, x, y, w, h, t) {
      // 月亮
      ctx.beginPath()
      ctx.arc(x + w * 0.85, y + h * 0.22, h * 0.11, 0, Math.PI * 2)
      ctx.fillStyle = '#ffe066'
      ctx.fill()
      // 楼群
      const buildings = [0.05, 0.18, 0.33, 0.5, 0.63]
      buildings.forEach((bx, i) => {
        const bw = w * 0.11
        const bh = h * (0.36 + ((i * 37) % 20) / 60)
        ctx.fillStyle = i % 2 ? '#2b2d52' : '#343869'
        ctx.fillRect(x + w * bx, y + h * 0.94 - bh, bw, bh)
        // 亮窗
        for (let r = 0; r < 4; r++)
          for (let c = 0; c < 3; c++) {
            if ((i * 7 + r * 3 + c) % 3 === 0) {
              ctx.fillStyle = Math.sin(t * 1.5 + i + r + c) > -0.6 ? '#ffd43b' : '#5c5f8a'
              ctx.fillRect(x + w * bx + bw * (0.16 + c * 0.3), y + h * 0.94 - bh + bh * (0.1 + r * 0.22), bw * 0.16, bh * 0.1)
            }
          }
      })
      // 星星
      ctx.fillStyle = '#fff'
      for (let i = 0; i < 8; i++) {
        const a = 0.4 + Math.sin(t * 2 + i * 1.7) * 0.4
        ctx.globalAlpha = Math.max(a, 0.1)
        ctx.fillRect(x + w * ((i * 0.13 + 0.04) % 1), y + h * ((i * 0.09 + 0.06) % 0.3), 2.5, 2.5)
      }
      ctx.globalAlpha = 1
    },
  },
  {
    name: '冰雪世界',
    sky: ['#a5d8ff', '#d0ebff'],
    ground: '#f1f3f9',
    road: '#9aa5b8',
    jamBg: '#c3d0e8',
    jamLine: 'rgba(255,255,255,0.8)',
    tileA: '#e8eef8',
    tileB: '#dde5f2',
    jamEdge: '#a9b8d4',
    deco(ctx, x, y, w, h, t) {
      // 远山
      ctx.fillStyle = '#dee6f5'
      ctx.beginPath()
      ctx.moveTo(x, y + h * 0.6)
      ctx.lineTo(x + w * 0.25, y + h * 0.15)
      ctx.lineTo(x + w * 0.5, y + h * 0.6)
      ctx.closePath()
      ctx.fill()
      ctx.beginPath()
      ctx.moveTo(x + w * 0.35, y + h * 0.6)
      ctx.lineTo(x + w * 0.62, y + h * 0.1)
      ctx.lineTo(x + w * 0.9, y + h * 0.6)
      ctx.closePath()
      ctx.fill()
      ctx.fillStyle = '#ffffff'
      ctx.beginPath()
      ctx.moveTo(x + w * 0.55, y + h * 0.23)
      ctx.lineTo(x + w * 0.62, y + h * 0.1)
      ctx.lineTo(x + w * 0.69, y + h * 0.23)
      ctx.closePath()
      ctx.fill()
      // 雪人
      const sx = x + w * 0.12
      const sy = y + h * 0.85
      ctx.fillStyle = '#fff'
      ctx.beginPath()
      ctx.arc(sx, sy - h * 0.1, h * 0.11, 0, Math.PI * 2)
      ctx.arc(sx, sy - h * 0.28, h * 0.08, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = '#ff922b'
      ctx.beginPath()
      ctx.moveTo(sx, sy - h * 0.28)
      ctx.lineTo(sx + h * 0.06, sy - h * 0.27)
      ctx.lineTo(sx, sy - h * 0.25)
      ctx.closePath()
      ctx.fill()
      tree(ctx, x + w * 0.4, y + h * 0.85, h * 0.6, '#4dab6d')
      // 雪花
      ctx.fillStyle = '#fff'
      for (let i = 0; i < 12; i++) {
        const fx = x + w * ((i * 0.083 + t * 0.02 * (1 + (i % 3) * 0.3)) % 1)
        const fy = y + h * ((i * 0.13 + t * 0.06 * (1 + (i % 2) * 0.5)) % 0.92)
        ctx.globalAlpha = 0.7
        ctx.beginPath()
        ctx.arc(fx, fy, 2, 0, Math.PI * 2)
        ctx.fill()
      }
      ctx.globalAlpha = 1
    },
  },
]

// 大门（乘客入场口，画在场景带右侧）
export function drawGate(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number) {
  // 门柱
  ctx.fillStyle = '#c0392b'
  roundRect(ctx, x, y, w * 0.12, h, 4)
  ctx.fill()
  roundRect(ctx, x + w * 0.88, y, w * 0.12, h, 4)
  ctx.fill()
  // 门拱
  ctx.beginPath()
  ctx.moveTo(x, y + h * 0.25)
  ctx.quadraticCurveTo(x + w / 2, y - h * 0.35, x + w, y + h * 0.25)
  ctx.lineTo(x + w, y + h * 0.08)
  ctx.quadraticCurveTo(x + w / 2, y - h * 0.52, x, y + h * 0.08)
  ctx.closePath()
  ctx.fillStyle = '#e74c3c'
  ctx.fill()
  // 门洞
  ctx.fillStyle = 'rgba(30,30,50,0.55)'
  ctx.beginPath()
  ctx.moveTo(x + w * 0.18, y + h)
  ctx.lineTo(x + w * 0.18, y + h * 0.45)
  ctx.quadraticCurveTo(x + w / 2, y + h * 0.12, x + w * 0.82, y + h * 0.45)
  ctx.lineTo(x + w * 0.82, y + h)
  ctx.closePath()
  ctx.fill()
}

// 候车人数牌：对应 ImageGen 定稿里的木质金边站牌。
export function drawQueueSign(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  count: number,
) {
  const h = w * 0.72
  // 双木柱和底脚
  ctx.fillStyle = '#8d5b2d'
  roundRect(ctx, x + w * 0.12, y + h * 0.8, w * 0.11, h * 0.55, 3)
  ctx.fill()
  roundRect(ctx, x + w * 0.77, y + h * 0.8, w * 0.11, h * 0.55, 3)
  ctx.fill()
  roundRect(ctx, x + w * 0.04, y + h * 1.28, w * 0.92, h * 0.13, 3)
  ctx.fill()

  // 金色厚框与奶油内板
  roundRect(ctx, x + 2, y + 5, w, h, Math.max(8, w * 0.14))
  ctx.fillStyle = 'rgba(73,45,22,0.25)'
  ctx.fill()
  const frame = ctx.createLinearGradient(0, y, 0, y + h)
  frame.addColorStop(0, '#f6c557')
  frame.addColorStop(1, '#a86b2c')
  roundRect(ctx, x, y, w, h, Math.max(8, w * 0.14))
  ctx.fillStyle = frame
  ctx.fill()
  roundRect(ctx, x + w * 0.09, y + h * 0.1, w * 0.82, h * 0.79, Math.max(5, w * 0.07))
  ctx.fillStyle = '#fff4d8'
  ctx.fill()
  ctx.strokeStyle = '#3573ac'
  ctx.lineWidth = Math.max(2, w * 0.035)
  ctx.stroke()

  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#2866a0'
  ctx.font = `900 ${h * 0.43}px -apple-system, sans-serif`
  ctx.fillText(String(count), x + w / 2, y + h * 0.33)
  ctx.fillStyle = '#c83d36'
  applyFittedCanvasFont(
    ctx,
    gameStrings.queueCount,
    w * 0.68,
    h * 0.13,
    h * 0.22,
    '-apple-system, "PingFang SC", sans-serif',
  )
  ctx.fillText(gameStrings.queueCount, x + w / 2, y + h * 0.7)
}

// 乘客脚下的奶油色矮护栏，弱化玻璃感以贴近新视觉稿。
export function drawQueueRail(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
) {
  ctx.strokeStyle = '#efe4ca'
  ctx.lineWidth = Math.max(2, h * 0.24)
  ctx.lineCap = 'round'
  ctx.setLineDash([Math.max(7, h * 0.65), Math.max(4, h * 0.38)])
  ctx.beginPath()
  ctx.moveTo(x, y - h * 0.65)
  ctx.lineTo(x + w, y - h * 0.65)
  ctx.stroke()
  ctx.setLineDash([])
  const n = Math.max(3, Math.round(w / 34))
  for (let i = 0; i <= n; i++) {
    const px = x + (w * i) / n
    ctx.fillStyle = 'rgba(72,52,33,0.2)'
    ctx.beginPath()
    ctx.ellipse(px + 1, y + 1, h * 0.22, h * 0.11, 0, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = '#f6ebd2'
    roundRect(ctx, px - h * 0.14, y - h, h * 0.28, h, h * 0.14)
    ctx.fill()
  }
}

// ------------------------------------------------------------------
// UI
// ------------------------------------------------------------------
export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export function hit(r: Rect, x: number, y: number): boolean {
  return x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h
}

export function drawButton(
  ctx: CanvasRenderingContext2D,
  r: Rect,
  label: string,
  opts: { bg?: string; fg?: string; font?: number; radius?: number } = {},
) {
  const { bg = '#f39c12', fg = '#fff', font = r.h * 0.42, radius = r.h * 0.3 } = opts
  roundRect(ctx, r.x, r.y + 4, r.w, r.h, radius)
  ctx.fillStyle = 'rgba(0,0,0,0.3)'
  ctx.fill()
  roundRect(ctx, r.x, r.y, r.w, r.h, radius)
  ctx.fillStyle = bg
  ctx.fill()
  ctx.fillStyle = fg
  ctx.font = `bold ${font}px -apple-system, "PingFang SC", sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(label, r.x + r.w / 2, r.y + r.h / 2 + font * 0.05)
}

// 通用胶囊提示
export function drawPill(
  ctx: CanvasRenderingContext2D,
  r: Rect,
  text: string,
  opts: { bg?: string; fg?: string; font?: number } = {},
) {
  const { bg = 'rgba(0,0,0,0.45)', fg = '#fff', font = r.h * 0.5 } = opts
  roundRect(ctx, r.x, r.y, r.w, r.h, r.h / 2)
  ctx.fillStyle = bg
  ctx.fill()
  ctx.fillStyle = fg
  ctx.font = `bold ${font}px -apple-system, "PingFang SC", sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, r.x + r.w / 2, r.y + r.h / 2 + font * 0.06)
}
