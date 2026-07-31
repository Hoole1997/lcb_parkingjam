# Parking courtyard background v2 — ImageGen prompt

## Final prompt

Create a polished stylized 3D mobile-game courtyard background in a 9:16 portrait canvas for an Android parking puzzle. Use a soft orthographic top-down view, warm ivory paving, mint-green foliage, small coral and yellow flowers, rounded toy-like forms, subtle ambient occlusion, soft daylight, and clean premium casual-game rendering.

This image will be converted into an Android NinePatch. Keep all decorative objects strictly inside the extreme four corners only. The central 76% of the image width and central 80% of the image height must be uninterrupted, uniform ivory paving with very low visual noise. Preserve completely clean horizontal and vertical bands through the exact center so both stretch axes can scale without distorting any object.

Do not place planters, trees, benches, lamps, flower beds, curbs, signs, parking bays, road grids, vehicles, characters, routes, UI, text, logos, or watermarks anywhere in the middle. No focal object in the center. No baked-in shadows crossing the center stretch bands. Edge decorations must feel balanced but sparse and must not form a frame around the usable play area.

Deliver a crisp high-resolution background with no compression artifacts, no blur, and no text.

## Runtime conversion

The generated 941 x 1672 source is converted by `scripts/create_courtyard_nine_patch.py`.

- Horizontal stretch marker: 28%–72% of source width.
- Vertical stretch marker: 32%–68% of source height.
- Content markers: full source bounds, so the drawable adds no implicit layout padding.
- The one-pixel NinePatch control border is transparent except for opaque black markers.

