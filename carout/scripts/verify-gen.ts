// 验证生成的 30 关全部可通关：
// 用"逆序出车 + 贪心上客"策略完整模拟一遍，任何一关卡死即失败。
import { generateLevel, LEVEL_COUNT } from '../src/levelgen'
import { JamGame } from '../src/game'

let failed = false

for (let i = 0; i < LEVEL_COUNT; i++) {
  const level = generateLevel(i)
  const g = new JamGame(level)
  const exitOrder = [...level.cars].reverse().map((c) => c.id)
  let ptr = 0
  let guard = 100000

  while (!g.won && guard-- > 0) {
    // 尽量派乘客（瞬时到达）
    const d = g.dispatchPassenger()
    if (d) {
      g.arrivePassenger(d.carId)
      // 满员车立即离场
      const car = g.carById(d.carId)
      if (car.state === 'leaving') g.leaveDone(d.carId)
      continue
    }
    // 无乘客可派 → 按逆序放出下一辆车
    if (ptr < exitOrder.length && g.freeSlot() >= 0) {
      const r = g.tapCar(exitOrder[ptr])
      if (r.kind !== 'out') {
        console.error(`❌ 第 ${i + 1} 关：车 ${exitOrder[ptr]} 无法驶出 (${r.kind})`)
        failed = true
        break
      }
      g.parkCar(exitOrder[ptr])
      ptr++
      continue
    }
    if (g.lost) {
      console.error(`❌ 第 ${i + 1} 关：死局！`)
      failed = true
      break
    }
    console.error(`❌ 第 ${i + 1} 关：无法推进（ptr=${ptr}）`)
    failed = true
    break
  }
  if (guard <= 0) {
    console.error(`❌ 第 ${i + 1} 关：模拟超时`)
    failed = true
  }
  if (!failed || g.won) {
    const lv = level
    const longs = lv.cars.filter((c) => c.len === 3).length
    const mys = lv.cars.filter((c) => c.mystery).length
    console.log(
      `✅ 第 ${String(i + 1).padStart(2)} 关  ${lv.w}x${lv.h}  车=${String(lv.cars.length).padStart(2)} (长车${longs} ?车${mys})  乘客=${String(lv.queue.length).padStart(3)}  主题=${lv.theme}`,
    )
  }
  if (failed) break
}

process.exit(failed ? 1 : 0)
