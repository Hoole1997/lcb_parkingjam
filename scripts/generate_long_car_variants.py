#!/usr/bin/env python3
"""Build memory-bounded long-vehicle PNG variants from one approved master.

The source master is deliberately generated only once so every paint variant keeps
the same silhouette, windows and wheel placement.  Recolouring is limited to the
warm, saturated body pixels; glass, lamps, tyres and neutral highlights are kept.
"""

from __future__ import annotations

import argparse
import colorsys
from pathlib import Path

from PIL import Image


TARGET_PALETTE: dict[str, tuple[int, int, int]] = {
    "coral": (255, 128, 103),
    "blue": (91, 196, 239),
    "yellow": (255, 200, 61),
    "purple": (187, 128, 227),
    "mint": (131, 221, 176),
    "red": (255, 86, 82),
}

REFERENCE_CORAL = TARGET_PALETTE["coral"]
CANVAS_SIZE = (256, 768)
CONTENT_INSET = 8


def circular_hue_delta(value: float, reference: float) -> float:
    """Return the shortest signed hue distance in the [-0.5, 0.5] range."""
    return (value - reference + 0.5) % 1.0 - 0.5


def recolour_body(image: Image.Image, target_rgb: tuple[int, int, int]) -> Image.Image:
    """Recolour body paint while preserving neutral vehicle details."""
    source_h, source_s, source_v = colorsys.rgb_to_hsv(
        *(channel / 255.0 for channel in REFERENCE_CORAL),
    )
    target_h, target_s, target_v = colorsys.rgb_to_hsv(
        *(channel / 255.0 for channel in target_rgb),
    )
    result: list[tuple[int, int, int, int]] = []
    for red, green, blue, alpha in image.get_flattened_data():
        if alpha == 0:
            result.append((0, 0, 0, 0))
            continue

        hue, saturation, value = colorsys.rgb_to_hsv(red / 255.0, green / 255.0, blue / 255.0)
        hue_delta = circular_hue_delta(hue, source_h)
        is_body_paint = saturation >= 0.12 and abs(hue_delta) <= 0.15 and value >= 0.12
        if not is_body_paint:
            result.append((red, green, blue, alpha))
            continue

        # Preserve local highlight/shadow variation instead of replacing pixels
        # with a flat colour.  A small fraction of the original hue variation is
        # retained so curved panels continue to read as three-dimensional.
        adjusted_hue = (target_h + hue_delta * 0.18) % 1.0
        adjusted_saturation = min(1.0, saturation * target_s / max(source_s, 0.01))
        adjusted_value = min(1.0, value * target_v / max(source_v, 0.01))
        out_red, out_green, out_blue = colorsys.hsv_to_rgb(
            adjusted_hue,
            adjusted_saturation,
            adjusted_value,
        )
        result.append(
            (
                round(out_red * 255),
                round(out_green * 255),
                round(out_blue * 255),
                alpha,
            ),
        )

    output = Image.new("RGBA", image.size)
    output.putdata(result)
    return output


def fit_to_canvas(image: Image.Image) -> Image.Image:
    """Trim transparent generation padding and fit without changing aspect ratio."""
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("Long-car master contains no visible pixels")
    cropped = image.crop(bounds)
    available_width = CANVAS_SIZE[0] - CONTENT_INSET * 2
    available_height = CANVAS_SIZE[1] - CONTENT_INSET * 2
    scale = min(available_width / cropped.width, available_height / cropped.height)
    output_size = (
        max(1, round(cropped.width * scale)),
        max(1, round(cropped.height * scale)),
    )
    resized = cropped.resize(output_size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(
        resized,
        (
            (CANVAS_SIZE[0] - resized.width) // 2,
            (CANVAS_SIZE[1] - resized.height) // 2,
        ),
    )
    return canvas


def generate(source: Path, output_dir: Path) -> None:
    master = fit_to_canvas(Image.open(source).convert("RGBA"))
    output_dir.mkdir(parents=True, exist_ok=True)
    for name, colour in TARGET_PALETTE.items():
        variant = master if name == "coral" else recolour_body(master, colour)
        destination = output_dir / f"parking_long_car_{name}.png"
        variant.save(destination, format="PNG", optimize=True)
        print(destination)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    generate(args.source, args.output_dir)


if __name__ == "__main__":
    main()
