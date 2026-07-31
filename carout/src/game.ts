import type { Car, JamLevel, TapResult } from './types'
import { DX, DY, capacityOf } from './types'

// Car Jam 玩法状态机。
// 时间无关的纯逻辑：动画由 main 驱动，动画到位后调用 parkCar/arrivePassenger 提交状态。
export class JamGame {
  w: number
  h: number
  cars: Car[]
  queue: number[] // 剩余乘客颜色（0 = 队首）
  slotCount: number
  slots: (number | null)[] // 每个车位当前占用的 carId（含驶来途中/停靠；驶离后置空）
  pending = new Map<number, number>() // carId -> 在途乘客数
  theme: number
  blockedStatic: Set<string> // 障碍物 + 不可通行格

  constructor(level: JamLevel) {
    this.w = level.w
    this.h = level.h
    this.cars = level.cars.map((c) => ({ ...c }))
    this.queue = [...level.queue]
    this.slotCount = level.slots
    this.slots = Array(level.slots).fill(null)
    this.theme = level.theme
    this.blockedStatic = new Set([
      ...level.obstacles.map((o) => `${o.x},${o.y}`),
      ...level.solid.map((s) => `${s.x},${s.y}`),
    ])
  }

  carById(id: number): Car {
    return this.cars.find((c) => c.id === id)!
  }

  // 车身占据的格子（车头在 (x,y)，车身向 dir 反方向延伸）
  cellsOf(c: Car): { x: number; y: number }[] {
    const cells = []
    for (let i = 0; i < c.len; i++) {
      cells.push({ x: c.x - DX[c.dir] * i, y: c.y - DY[c.dir] * i })
    }
    return cells
  }

  carAtCell(x: number, y: number): Car | undefined {
    return this.cars.find(
      (c) => c.state === 'jam' && this.cellsOf(c).some((p) => p.x === x && p.y === y),
    )
  }

  private occupied(x: number, y: number, ignore: Car): boolean {
    if (this.blockedStatic.has(`${x},${y}`)) return true
    for (const c of this.cars) {
      if (c === ignore || c.state !== 'jam') continue
      if (this.cellsOf(c).some((p) => p.x === x && p.y === y)) return true
    }
    return false
  }

  freeSlot(): number {
    return this.slots.indexOf(null)
  }

  // 点击车辆：路径畅通且有空车位 → 预定车位并进入 driving
  tapCar(id: number): TapResult {
    const car = this.carById(id)
    if (car.state !== 'jam') return { kind: 'busy' }

    // 前方路径检查（只需检查场内格子）
    for (let s = 1; ; s++) {
      const nx = car.x + DX[car.dir] * s
      const ny = car.y + DY[car.dir] * s
      if (nx < 0 || nx >= this.w || ny < 0 || ny >= this.h) break // 到达边界，畅通
      if (this.occupied(nx, ny, car)) {
        // 找出被撞的车（可能是障碍物 → undefined）
        const blocker = this.carAtCell(nx, ny)
        return { kind: 'bump', cells: s, blockerId: blocker?.id }
      }
    }

    const slot = this.freeSlot()
    if (slot < 0) return { kind: 'busy' } // 没有空位（UI 层会提示）

    this.slots[slot] = car.id
    car.slot = slot
    car.state = 'driving'
    car.mystery = false // 出阵即揭晓颜色
    return { kind: 'out' }
  }

  // 动画到达车位后提交
  parkCar(id: number) {
    const car = this.carById(id)
    car.state = 'parked'
  }

  // 尝试派出一名队首乘客 → 返回目标车（供动画），无可派返回 null
  dispatchPassenger(): { carId: number; color: number } | null {
    if (this.queue.length === 0) return null
    const color = this.queue[0]
    for (const c of this.cars) {
      if (c.state !== 'parked' || c.color !== color) continue
      const enroute = this.pending.get(c.id) ?? 0
      if (c.seats + enroute < capacityOf(c.len)) {
        this.pending.set(c.id, enroute + 1)
        this.queue.shift()
        return { carId: c.id, color }
      }
    }
    return null
  }

  // 乘客跑到车旁：入座。若坐满 → 车驶离并释放车位
  arrivePassenger(carId: number): 'seated' | 'full' {
    const car = this.carById(carId)
    this.pending.set(carId, (this.pending.get(carId) ?? 0) - 1)
    car.seats++
    if (car.seats >= capacityOf(car.len) && (this.pending.get(carId) ?? 0) <= 0) {
      car.state = 'leaving'
      this.slots[car.slot] = null
      return 'full'
    }
    return 'seated'
  }

  leaveDone(id: number) {
    this.carById(id).state = 'gone'
  }

  // ---- 道具 ----

  // 揭秘：显示所有神秘车颜色
  revealAll(): number {
    let n = 0
    for (const c of this.cars) {
      if (c.mystery) {
        c.mystery = false
        n++
      }
    }
    return n
  }

  // 排序：稳定重排队列——能上"当前停靠未满车"的乘客排到前面
  private sortedQueueForParkedCars(): number[] | null {
    const parkedColors = new Set(
      this.cars
        .filter(
          (c) =>
            c.state === 'parked' &&
            c.seats + (this.pending.get(c.id) ?? 0) < capacityOf(c.len),
        )
        .map((c) => c.color),
    )
    if (parkedColors.size === 0) return null
    const front = this.queue.filter((q) => parkedColors.has(q))
    const rest = this.queue.filter((q) => !parkedColors.has(q))
    if (front.length === 0) return null
    const sorted = [...front, ...rest]
    if (sorted.every((color, index) => color === this.queue[index])) return null
    return sorted
  }

  canSortQueue(): boolean {
    return this.sortedQueueForParkedCars() !== null
  }

  sortQueue(): boolean {
    const sorted = this.sortedQueueForParkedCars()
    if (!sorted) return false
    this.queue = sorted
    return true
  }

  // 消除：移除一辆"挡路"的停靠车（颜色≠队首），并同步移除队列中
  // 对应数量的同色乘客（保持车座与人数守恒 → 关卡仍可解）
  canRemoveBlocker(): boolean {
    if (this.queue.length === 0) return false
    const head = this.queue[0]
    return this.cars.some((car) => car.state === 'parked' && car.color !== head)
  }

  removeBlocker(): Car | null {
    if (this.queue.length === 0) return null
    const head = this.queue[0]
    const car = this.cars.find((c) => c.state === 'parked' && c.color !== head)
    if (!car) return null
    let need = capacityOf(car.len) - car.seats - (this.pending.get(car.id) ?? 0)
    if (need > 0) {
      const kept: number[] = []
      for (const q of this.queue) {
        if (need > 0 && q === car.color) {
          need--
          continue
        }
        kept.push(q)
      }
      this.queue = kept
    }
    this.slots[car.slot] = null
    car.state = 'leaving'
    return car
  }

  // 解锁新车位
  addSlot() {
    this.slotCount++
    this.slots.push(null)
  }

  get won(): boolean {
    return this.queue.length === 0 && this.cars.every((c) => c.state === 'gone')
  }

  // 死局：车位全被占且（含驶来途中的车）都无法接纳队首乘客，且没有在途乘客
  get lost(): boolean {
    if (this.won || this.queue.length === 0) return false
    if (this.slots.some((s) => s === null)) return false
    let pendingTotal = 0
    this.pending.forEach((v) => (pendingTotal += v))
    if (pendingTotal > 0) return false
    const head = this.queue[0]
    return this.slots.every((id) => {
      const c = this.carById(id!)
      return c.color !== head || c.seats + (this.pending.get(c.id) ?? 0) >= capacityOf(c.len)
    })
  }
}
