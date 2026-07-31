# ImageGen v2 精灵提示词

三套素材均使用内置 ImageGen 生成。`game-enter-fixed-real-device-2.png` 仅作为 2.5D
玩具材质与花园游戏氛围的风格参考，不作为编辑目标。

## 候车乘客

```text
Use case: stylized-concept
Asset type: production sprite sheet for an Android parking puzzle game
Primary request: create exactly six isolated full-body waiting passenger character sprites in one horizontal row, evenly spaced, all the same scale and facing straight toward the camera in a relaxed neutral waiting pose.
Subject: six cute toy-like passengers with rounded heads, small simple facial features, short arms and feet, subtle dimensional clothing and polished mobile-game quality. Clothing colors from left to right: coral red, azure blue, golden yellow, leaf green, deep blue-violet, peach rose.
Style/medium: premium 2.5D clay toy game asset, soft bevels, clean highlights, friendly casual mobile puzzle aesthetic matching the reference.
Composition/framing: 3:2 landscape sprite sheet, one strict horizontal row of six, each character centered inside an equal invisible cell, generous separation and padding, full body visible, no overlap, no cropping.
Scene/backdrop: perfectly flat solid #FF00FF chroma-key background with no gradient, texture, floor, horizon, reflection, or lighting variation.
Constraints: no cast shadows, contact shadows, text, numbers, UI, props, logos, or watermark; crisp edges; do not use #FF00FF in the characters.
```

## 短车

```text
Use case: stylized-concept
Asset type: production top-down vehicle sprite sheet for an Android parking puzzle game
Primary request: create exactly six isolated compact hatchback sprites in one horizontal row, evenly spaced, identical design and size, strict orthographic top-down view, every car pointing upward.
Subject: premium cute compact hatchback with shaped hood, windshield, roof, rear window, side mirrors, visible side wheels, headlights and tail lights. Colors from left to right: coral red, azure blue, golden yellow, leaf green, deep blue-violet, peach rose.
Style/medium: polished 2.5D clay-toy mobile game sprite with molded-plastic materials, soft bevels and controlled highlights matching the reference.
Scene/backdrop: perfectly flat solid #FF00FF chroma-key background.
Constraints: no shadows, arrows, question marks, text, UI, people, props, logos, or watermark; no neon magenta in the vehicles; exact six-car count and color order.
```

## 长车／巴士

```text
Use case: stylized-concept
Asset type: production top-down long-vehicle sprite sheet for an Android parking puzzle game
Primary request: create exactly six isolated long minibus sprites in one horizontal row, identical purpose-built minibus design and size, strict orthographic top-down view, every minibus pointing upward. They must be genuine long vehicles, not stretched compact cars.
Subject: cute long shuttle minibus with long rectangular body, short hood, large windshield, raised roof, rear window, separate side passenger windows, spaced side wheels, headlights and tail lights. Colors from left to right: coral red, azure blue, golden yellow, leaf green, deep blue-violet, peach rose.
Style/medium: premium polished 2.5D clay-toy mobile game sprite, visually distinct from the compact car.
Scene/backdrop: perfectly flat solid #FF00FF chroma-key background.
Constraints: no shadows, arrows, question marks, text, UI, people, props, logos, or watermark; no neon magenta in the vehicles; exact six-minibus count and color order.
```

透明化使用 `remove_chroma_key.py`：自动采样边界色、软蒙版、透明阈值 10、不透明阈值
72、边缘收缩 1px。运行时只打包纵向裁剪后的三张 RGBA 图集，原始生成图和全尺寸透明图仅保存在
本目录用于追溯。
