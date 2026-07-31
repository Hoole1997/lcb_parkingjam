// 通过 CDP 检查页面状态 / 执行 JS / 注入触摸事件 / 截图
// 用法: node scripts/cdp.mjs [--port 9222] <cmd> ...
//   eval "js"            执行 JS
//   tap x y              注入触摸（CSS 坐标）
//   swipe x y dx dy      滑动
//   nav url              跳转页面
//   shot out.png         截图
//   wait ms              等待
// 可用分号串联: node scripts/cdp.mjs nav "http://..." \; wait 3000 \; shot a.png
import WebSocket from 'ws'
import { writeFileSync } from 'node:fs'

const argv = process.argv.slice(2)
let port = 9222
if (argv[0] === '--port') {
  port = +argv[1]
  argv.splice(0, 2)
}

// 按 ; 分组命令
const groups = []
let cur = []
for (const a of argv) {
  if (a === ';') {
    if (cur.length) groups.push(cur)
    cur = []
  } else cur.push(a)
}
if (cur.length) groups.push(cur)

const list = await fetch(`http://localhost:${port}/json`).then((r) => r.json())
const page = list.find((t) => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl, { perMessageDeflate: false })
let id = 0
const pending = new Map()

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const mid = ++id
    pending.set(mid, { resolve, reject })
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
}

ws.on('message', (data) => {
  const msg = JSON.parse(data)
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id).resolve(msg.result ?? msg.error)
    pending.delete(msg.id)
  }
})

await new Promise((r) => ws.on('open', r))

for (const [cmd, ...args] of groups) {
  if (cmd === 'eval') {
    const r = await send('Runtime.evaluate', { expression: args[0], returnByValue: true })
    console.log(JSON.stringify(r?.result?.value ?? r, null, 1))
  } else if (cmd === 'tap') {
    const [x, y] = args.map(Number)
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
    await new Promise((r) => setTimeout(r, 60))
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    console.log(`tapped ${x},${y}`)
  } else if (cmd === 'swipe') {
    const [x, y, dx, dy] = args.map(Number)
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
    for (let i = 1; i <= 5; i++) {
      await new Promise((r) => setTimeout(r, 30))
      await send('Input.dispatchTouchEvent', {
        type: 'touchMove',
        touchPoints: [{ x: x + (dx * i) / 5, y: y + (dy * i) / 5 }],
      })
    }
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    console.log(`swiped ${x},${y} -> +${dx},+${dy}`)
  } else if (cmd === 'nav') {
    await send('Page.enable')
    await send('Page.navigate', { url: args[0] })
    console.log(`nav ${args[0]}`)
  } else if (cmd === 'shot') {
    const r = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(args[0], Buffer.from(r.data, 'base64'))
    console.log(`shot -> ${args[0]}`)
  } else if (cmd === 'wait') {
    await new Promise((r) => setTimeout(r, +args[0]))
  }
}

ws.close()
