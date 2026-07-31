# Parking Jam 美术方向

- `parking_jam_ui_v1.png`：ImageGen 生成的竖屏主界面视觉基准。
- `parking_home_ui_v2.png`：Compose 首页的视觉方向参考，不作为运行时整屏贴图加载。
- `parking_home_ui_v3.png`：全新“微缩城市停车楼”首页候选稿；不继承 V2 的绿植边框与奶油色卡片结构。
- `parking_home_ui_v3_prompt.md`：V3 候选稿的完整 ImageGen 提示词。
- `parking_home_ui_v4.png`：保留原有奶油白、薄荷绿和柔和 3D 风格的首页定稿候选；仅呈现已实现的继续游戏、选择关卡与设置入口。
- `parking_home_ui_v4_prompt.md`：V4 定点移除“挑战/车库”后的完整 ImageGen 编辑提示词。
- `parking_home_background_source.png`：V4 首页专用无 UI 庭院背景源图；运行时按比例裁切，不做纵向拉伸。
- `parking_home_hero_chroma_source.png`：V4 首页独立停车场的色键源图；运行时资源已去背并转为无损 WebP。
- `parking_home_runtime_assets_prompt.md`：两张运行时分层素材的完整生成与去背参数。
- `parking_home_runtime_v4_360x640.png`：Compose 实现在 360×640dp 基准画布上的模拟器验收截图。
- `parking_level_map_ui_v2.png`：Compose 选关地图的视觉方向参考，不作为运行时整屏贴图加载。
- `parking_courtyard_background_source.png`：第一版庭院背景源图，仅保留作迭代记录。
- `parking_courtyard_background_source_v2.png`：第二版无花盆背景，仅保留作迭代记录。
- `parking_courtyard_background_runtime_v2.9.png`：第二版运行时 NinePatch 归档，不参与打包。
- `parking_courtyard_background_source_v3.png`：当前 NinePatch 源图；中央为无砖缝、无纹理的平滑底色。
- `parking_courtyard_background_v2_prompt.md`、`parking_courtyard_background_v3_prompt.md`：两次 ImageGen 提示词与对应 NinePatch 参数。
- `parking_asphalt_texture_source.png`：低对比深色停车场纹理源图。
- 风格关键词：正交俯视、玩具质感、奶油白与薄荷绿环境、珊瑚/天蓝/黄色车辆、低噪点软阴影。
- 运行时文字、按钮和状态仍由 Android 原生 UI 绘制，避免把文案烘焙进位图。

## Android 交付资源

最终资源位于 `feature-game/src/main/res/drawable-nodpi/`：

- 六辆统一朝上的透明车辆 PNG，尺寸均为 256×512；运行时通过 Canvas 旋转复用四个方向。
- 941×1672 内容区的无损 nodpi NinePatch 庭院背景；横向 42%–58%、纵向 44%–56% 的纯色区域可伸缩，作为 Activity 根容器 background 使用，外围装饰与砖纹都不会被拉伸。
- 512×512 的停车场 WebP 纹理；由棋盘 Canvas 重复铺放。
- 941×1672 的首页专用庭院 WebP，以及 1131×980 的透明停车场无损 WebP；二者分层绘制，停车场始终使用 `ContentScale.Fit`。

车辆源图使用色键背景生成并在本地转为 Alpha；薄荷绿车辆因与绿色色键接近，单独使用洋红色色键重生成，避免透明破洞。运行时资源已检查尺寸、透明角与有效像素边界。

首页、选关页和游戏页均由 Compose 原生布局绘制。首页背景只承担环境纹理，独立停车场只承担静态主视觉；标题、金币、按钮、进度和所有状态仍为原生动态 UI。首页隐藏时不组合这两张大图，降低常驻内存。

## 重新生成 NinePatch

```shell
python3 scripts/create_courtyard_nine_patch.py \
  docs/art_direction/parking_courtyard_background_source_v3.png \
  feature-game/src/main/res/drawable-nodpi/parking_courtyard_background.9.png
```
