// 核心数据类型（Car Jam 玩法）

// 方向：0=上 1=右 2=下 3=左（车头朝向 = 行驶方向）
export type Dir4 = 0 | 1 | 2 | 3
export const DX = [0, 1, 0, -1] as const
export const DY = [-1, 0, 1, 0] as const

export type CarState =
  | 'jam' // 在车阵中
  | 'driving' // 沿路径驶向车位
  | 'parked' // 停在车位等乘客
  | 'leaving' // 坐满驶离
  | 'gone' // 已离场

export interface Car {
  id: number
  x: number // 车头格坐标（车身沿 dir 反方向延伸）
  y: number
  len: 2 | 3 // 占格数；2=小车(4座) 3=长车(6座)
  dir: Dir4
  color: number // 调色板索引 0..5
  mystery: boolean // 神秘车：出阵前不显示颜色
  state: CarState
  seats: number // 已上车人数
  slot: number // 停在哪个车位（parked 时有效）
}

export const capacityOf = (len: number) => (len === 2 ? 4 : 6)

export interface Obstacle {
  x: number
  y: number
  kind: 'cone' | 'bush' | 'rock' | 'hydrant'
}

export interface JamLevel {
  w: number
  h: number
  cars: Car[]
  queue: number[] // 乘客颜色序列（下标 0 = 队首）
  slots: number // 车位数量
  theme: number // 主题场景索引
  mask: boolean[] // w*h，true=铺装地面（车位区域，仅用于摆车与绘制）
  obstacles: Obstacle[] // 装饰障碍：占格且阻挡行驶
  solid: { x: number; y: number }[] // 不可通行区域（如环形关卡中心水池）
  shape: 'rect' | 'circle' | 'diamond' | 'ring' | 'heart' | 'cross'
}

// 点击车辆的结果
export type TapResult =
  | { kind: 'out' } // 成功驶出车阵
  | { kind: 'bump'; cells: number; blockerId?: number } // 被挡（前方第 cells 格；blockerId=被撞的车）
  | { kind: 'busy' } // 不在车阵中
