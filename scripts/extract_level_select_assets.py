#!/usr/bin/env python3
"""Extract level-select UI pieces and build Android-ready transparent assets.

Image generation returns a visible checkerboard instead of an alpha channel. This
script removes that regular neutral background, keeps the warm clay components and
their soft shadows, then writes density-aware PNGs and the CTA Android nine-patch.
"""

from __future__ import annotations

from dataclasses import dataclass
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DRAWABLE_XXHDPI = PROJECT_ROOT / "feature-game/src/main/res/drawable-xxhdpi"
SOURCE_DIR = PROJECT_ROOT / "docs/art_direction/level_select_asset_sources"
TOP_STRIP = SOURCE_DIR / "top_chrome_strip.png"
CARD_STRIP = SOURCE_DIR / "card_state_strip.png"
CTA_STRIP = SOURCE_DIR / "continue_button_source.png"
ICON_STRIP = SOURCE_DIR / "state_icon_strip.png"


@dataclass(frozen=True)
class AssetSpec:
    name: str
    box: tuple[int, int, int, int]
    output_size: tuple[int, int]


def checkerboard_to_alpha(source: Image.Image, minimum_chroma: int = 14) -> Image.Image:
    """Convert a neutral checkerboard crop into a softly antialiased alpha mask."""

    rgb = np.asarray(source.convert("RGB"), dtype=np.int16)
    channel_max = rgb.max(axis=2)
    channel_min = rgb.min(axis=2)
    chroma = channel_max - channel_min

    # The generated checkerboard and its accidental bottom shadow are neutral.
    # Only chromatic clay pixels seed the silhouette, so the gray "bottom veil"
    # seen on device is excluded instead of being baked into every drawable.
    seed = chroma >= minimum_chroma
    foreground = largest_connected_component(seed)
    rows = np.flatnonzero(foreground.any(axis=1))
    if rows.size == 0:
        raise ValueError("No foreground detected in generated asset crop")

    silhouette = np.zeros(seed.shape, dtype=np.uint8)
    for y in rows:
        xs = np.flatnonzero(foreground[y])
        if xs.size:
            # Components are convex UI silhouettes. Filling each scanline keeps
            # pale interior pixels that resemble the checkerboard in isolation.
            silhouette[y, xs[0] : xs[-1] + 1] = 255

    alpha = Image.fromarray(silhouette, mode="L").filter(
        ImageFilter.GaussianBlur(radius=1.15)
    )
    rgba = source.convert("RGBA")
    rgba.putalpha(alpha)
    return trim_transparent(rgba)


def largest_connected_component(mask: np.ndarray) -> np.ndarray:
    """Discard checkerboard compression artifacts not connected to the UI piece."""

    height, width = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    largest: list[tuple[int, int]] = []
    for start_y, start_x in zip(*np.nonzero(mask)):
        if visited[start_y, start_x]:
            continue
        queue: deque[tuple[int, int]] = deque([(start_y, start_x)])
        visited[start_y, start_x] = True
        component: list[tuple[int, int]] = []
        while queue:
            y, x = queue.popleft()
            component.append((y, x))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    if dx == 0 and dy == 0:
                        continue
                    ny, nx = y + dy, x + dx
                    if (
                        0 <= ny < height
                        and 0 <= nx < width
                        and mask[ny, nx]
                        and not visited[ny, nx]
                    ):
                        visited[ny, nx] = True
                        queue.append((ny, nx))
        if len(component) > len(largest):
            largest = component

    result = np.zeros_like(mask, dtype=bool)
    if largest:
        ys, xs = zip(*largest)
        result[np.asarray(ys), np.asarray(xs)] = True
    return result


def trim_transparent(image: Image.Image, padding: int = 14) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError("Generated asset became fully transparent")
    left, top, right, bottom = bbox
    return image.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(image.width, right + padding),
            min(image.height, bottom + padding),
        )
    )


def extract_assets(source_path: Path, specs: list[AssetSpec]) -> None:
    source = Image.open(source_path).convert("RGB")
    for spec in specs:
        isolated = checkerboard_to_alpha(source.crop(spec.box))
        fitted = fit_with_safe_margin(isolated, spec.output_size)
        fitted.save(DRAWABLE_XXHDPI / f"{spec.name}.png", optimize=True)


def fit_with_safe_margin(
    image: Image.Image,
    output_size: tuple[int, int],
    margin: int = 10,
) -> Image.Image:
    """Preserve aspect ratio and an alpha gutter so Android never clips bevels."""

    available = (output_size[0] - margin * 2, output_size[1] - margin * 2)
    fitted = image.copy()
    fitted.thumbnail(available, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", output_size)
    x = (output_size[0] - fitted.width) // 2
    y = (output_size[1] - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def build_cta_nine_patch() -> None:
    source = Image.open(CTA_STRIP).convert("RGB")
    button = checkerboard_to_alpha(source.crop((70, 215, 1720, 700)))
    button = fit_with_safe_margin(button, (720, 195), margin=4)

    # NinePatch reserves a one-pixel control border. Only the uniform coral region
    # after the play icon stretches; rounded ends, icon, bevel and shadow stay fixed.
    nine_patch = Image.new("RGBA", (button.width + 2, button.height + 2))
    nine_patch.alpha_composite(button, (1, 1))
    pixels = nine_patch.load()
    black = (0, 0, 0, 255)
    for x in range(390, 545):
        pixels[x, 0] = black
    for y in range(78, 118):
        pixels[0, y] = black

    # Bottom/right markers define the localized-copy safe area. Start shortly
    # after the baked-in play icon and retain a generous 36dp end inset, so the
    # label is visually centered in the button instead of crowding its right cap.
    for x in range(240, 611):
        pixels[x, nine_patch.height - 1] = black
    for y in range(38, 160):
        pixels[nine_patch.width - 1, y] = black
    nine_patch.save(
        DRAWABLE_XXHDPI / "parking_level_continue_button.9.png",
        optimize=True,
    )


def build_star_counter_nine_patch() -> None:
    """Keep the star/caps fixed and stretch only the counter's empty middle."""

    source = Image.open(
        DRAWABLE_XXHDPI / "parking_level_star_counter.png"
    ).convert("RGBA")
    # The previous 300 px source had a 100dp intrinsic width but the phone header
    # allocated only 90dp. NinePatch cannot shrink fixed caps, so Android clipped
    # the right end. A compact 80dp source stays below every supported container.
    counter = fit_with_safe_margin(source, (240, 105), margin=3)
    nine_patch = Image.new("RGBA", (counter.width + 2, counter.height + 2))
    nine_patch.alpha_composite(counter, (1, 1))
    pixels = nine_patch.load()
    black = (0, 0, 0, 255)

    # The top/left markers define the stretchable neutral area. The baked star
    # and both rounded end caps remain at their original xxhdpi size.
    for x in range(132, 177):
        pixels[x, 0] = black
    for y in range(41, 68):
        pixels[0, y] = black

    # Localized count text is constrained to the blank area right of the star.
    # Keep a full 20dp end inset so the last digit remains visually separated
    # from the beveled cap even after the screenshot is scaled on a small phone.
    for x in range(101, 181):
        pixels[x, nine_patch.height - 1] = black
    for y in range(20, 87):
        pixels[nine_patch.width - 1, y] = black
    nine_patch.save(
        DRAWABLE_XXHDPI / "parking_level_star_counter_bg.9.png",
        optimize=True,
    )


def main() -> None:
    DRAWABLE_XXHDPI.mkdir(parents=True, exist_ok=True)
    extract_assets(
        TOP_STRIP,
        [
            AssetSpec("parking_level_back_button", (35, 105, 410, 555), (174, 186)),
            AssetSpec("parking_level_title_panel", (450, 45, 1500, 650), (570, 292)),
            AssetSpec("parking_level_star_counter", (1460, 145, 2165, 540), (300, 122)),
        ],
    )
    extract_assets(
        CARD_STRIP,
        [
            AssetSpec("parking_level_card_open", (20, 45, 550, 670), (300, 319)),
            AssetSpec("parking_level_card_challenge", (535, 45, 1060, 670), (300, 319)),
            AssetSpec("parking_level_card_current", (1045, 45, 1570, 670), (300, 319)),
            AssetSpec("parking_level_card_locked", (1550, 45, 2085, 670), (300, 319)),
        ],
    )
    extract_assets(
        ICON_STRIP,
        [
            AssetSpec("parking_level_star", (185, 65, 785, 660), (72, 72)),
            AssetSpec("parking_level_lock", (1295, 55, 1840, 670), (72, 84)),
        ],
    )
    build_star_counter_nine_patch()
    build_cta_nine_patch()


if __name__ == "__main__":
    main()
