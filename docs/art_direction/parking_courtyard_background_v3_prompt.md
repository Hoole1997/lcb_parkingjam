# Parking courtyard background v3 — ImageGen prompt

## Final prompt

Taxonomy: stylized-concept. Create a production-ready Android mobile-game background source, portrait 9:16, for a cheerful parking puzzle. Polished premium casual-game 3D illustration, soft orthographic top-down view. The base surface must be a completely smooth, seamless, matte warm-ivory courtyard floor: NO paving stones, NO bricks, NO grout lines, NO tile pattern, NO repeated texture, NO cracks, NO speckles, and NO visible brush texture.

This will become an Android NinePatch, so preserve a perfectly uniform stretch-safe cross: the full-height vertical strip from 42% to 58% of image width AND the full-width horizontal strip from 44% to 56% of image height must be identical flat ivory color from edge to edge, with no gradient, shadows, seams, objects, or texture.

Keep all decoration strictly inside the extreme 16% square of each of the four corners: small rounded toy-like mint and fresh-green leaves with a few coral, yellow, and white flowers. Contain every plant and every cast shadow inside those corner zones; absolutely no shadow may extend into the central cross or usable middle. Keep the entire central 78% width and 82% height visually empty and calm. Decorations should be sparse, balanced, and not form a frame.

No planters, flower pots, benches, lamps, curbs, signs, parking bays, roads, grids, vehicles, characters, UI, text, logo, or watermark. Crisp high-resolution edges, clean light, no compression artifacts, no depth-of-field blur. Output only the background artwork.

## Runtime conversion

- Horizontal stretch marker: 42%–58% of source width.
- Vertical stretch marker: 44%–56% of source height.
- The source is packaged under `drawable-nodpi`, preventing density-based bitmap upscaling and a second resampling pass.
- Content markers span the full source bounds, so the drawable adds no implicit layout padding.
