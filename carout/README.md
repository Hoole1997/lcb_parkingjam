# 🚗 车水马龙（Car Jam）

Car Jam 类停车调度解谜游戏（参考 Parking Jam / Car Jam Solver 玩法）：

- 底部**车阵**：每辆车顶有箭头，点击后沿箭头直行——路通驶出，路堵撞回
- 驶出的车自动开到**停车位**，顶部**排队的同色乘客**依次跑来上车
- 小车 4 座、长车 6 座，坐满驶离腾出车位
- **车位全被"不匹配颜色"的车占满 = 失败**——出车顺序就是策略
- 灰色 **?** 神秘车驶出前不显示颜色；30 关难度递增；4 套主题场景
  （阳光公园 / 碧海沙滩 / 霓虹夜城 / 冰雪世界）；激励广告道具 + 进度存档

技术栈：TypeScript + Canvas 2D（无引擎、无素材，程序化绘制 + WebAudio 合成音效），
Capacitor 7 打包 Android APK。

## 关卡生成

`src/levelgen.ts` **逆向倒车生成**：按 1..N 把车"沿箭头反方向倒入"场内，倒入时
其驶出路径必须畅通 → 按 N..1 点车必然全部驶出，**数学上保证 100% 可解**。
乘客队列按可行出车顺序展开，再做不重叠相邻块交换增加变化（2 车位内仍可解，
实际给 5 个车位作缓冲）。种子随机——所有玩家同一关卡布局一致。

## 目录结构

```
src/
├── main.ts      场景管理、三区布局、路径动画、乘客调度、输入、UI
├── game.ts      玩法状态机：车阵/车位/队列/上车/胜负判定
├── levelgen.ts  逆向关卡生成器（种子随机，30 关难度曲线）
├── render.ts    绘制原语：车辆精灵/小人/主题场景/大门/UI
├── audio.ts     WebAudio 程序化音效
└── storage.ts   关卡进度存档
scripts/
├── verify-gen.ts  模拟通关验证全部 30 关可解
└── cdp.mjs        CDP 调试工具（eval/tap/swipe/nav/shot/wait）
```

## 常用命令

```bash
npm run dev            # 浏览器调试 (调试参数: ?lv=5 直接进关; &auto=1 自动通关)
npm run verify-levels  # 验证 30 关全部可通关
npm run build          # 构建 web 产物

# 打 APK
npx cap sync android
cd android && ./gradlew assembleDebug assembleRelease
# 产物:
#   android/app/build/outputs/apk/debug/app-debug.apk
#   android/app/build/outputs/apk/release/app-release.apk  (签名发布版)
```

## 签名

- 密钥库：`android/keystore/release.keystore`
- 配置：`android/keystore.properties`（**妥善保管，勿提交公开仓库**）

## 环境依赖

- Node 18+，JDK 21（`android/gradle.properties` 的 `org.gradle.java.home`
  指向 Android Studio 自带 JBR）
- Android SDK（`ANDROID_HOME`）

## 调难度 / 加关卡

改 `src/levelgen.ts` 的 `cfgFor()`（车数、长车/神秘车比例、队列打乱概率、
网格尺寸）与 `LEVEL_COUNT`，然后 `npm run verify-levels` 确认可解。
