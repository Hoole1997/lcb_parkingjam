#!/usr/bin/env python3
"""Create the courtyard NinePatch with stretch markers confined to its uniform center.

The generated art keeps decorations and their shadows in the corners. Its smooth center
contains no seams or texture, so unusual aspect ratios cannot distort a visible pattern.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


def build_nine_patch(source_path: Path, output_path: Path) -> None:
    source = Image.open(source_path).convert("RGBA")
    width, height = source.size
    output = Image.new("RGBA", (width + 2, height + 2), (0, 0, 0, 0))
    output.paste(source, (1, 1))

    black = (0, 0, 0, 255)
    pixels = output.load()

    # V3 reserves this exact uniform cross in the ImageGen prompt for NinePatch scaling.
    stretch_left = round(width * 0.42) + 1
    stretch_right = round(width * 0.58) + 1
    stretch_top = round(height * 0.44) + 1
    stretch_bottom = round(height * 0.56) + 1

    for x in range(stretch_left, stretch_right + 1):
        pixels[x, 0] = black
    for y in range(stretch_top, stretch_bottom + 1):
        pixels[0, y] = black

    # Full content markers produce zero intrinsic padding for the Compose host.
    for x in range(1, width + 1):
        pixels[x, height + 1] = black
    for y in range(1, height + 1):
        pixels[width + 1, y] = black

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output.save(output_path, format="PNG", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build_nine_patch(args.source, args.output)


if __name__ == "__main__":
    main()
