# V4 首页运行时素材生成说明

参考图：`parking_home_ui_v4.png`

## 庭院背景

```text
Edit the supplied approved Parking Jam home-screen reference into a production background asset only.

Preserve the exact same warm cream courtyard, soft premium 2.5D clay-game art direction, palette,
lighting, tiny rounded-square paving texture, and restrained garden feeling. Remove every UI element,
all text, title plaque, coin pill, settings button, parking board, cars, arrows, buttons, progress panel,
and icons.

Output a portrait 9:16 full-bleed courtyard background. Keep the entire central 78% of the canvas
visually quiet and free of objects. Use only very small, sparse mint planters or leaf clusters tucked
against the extreme outer corners/edges. No text, logos, or watermark.
```

运行时将源 PNG 无损转换为 `parking_home_courtyard.webp`，Compose 使用
`ContentScale.Crop`，因此不同长宽比只裁切边缘，不拉伸地砖和花盆。

## 独立停车场

```text
Create a production-ready isolated hero asset based on the supplied approved Parking Jam home-screen
reference. Reproduce only the central top-down miniature parking-board scene: rounded mint-green raised
frame, pale parking pavement, white bay lines, chunky coral/blue/yellow/mint cars, four round shrubs,
and the right-side exit lane with white directional arrow. Preserve the same soft premium 2.5D clay-game
style, proportions, camera angle, composition, palette, and visual density.

Place the complete subject on a perfectly flat #FF00FF chroma-key background with no cast shadow,
glow, reflection, or haze outside the subject. Do not use magenta inside the subject. No text, logo,
or watermark.
```

去背使用：

```shell
python3 /Users/apple/.codex/skills/.system/imagegen/scripts/remove_chroma_key.py \
  --input parking_home_hero_chroma_source.png \
  --out parking_home_hero.png \
  --auto-key border --soft-matte \
  --transparent-threshold 12 --opaque-threshold 220 --despill
```

最终裁掉透明冗余边缘，并无损转换为 `parking_home_hero.webp`。运行时固定使用
`ContentScale.Fit`，不允许 `FillBounds`。
