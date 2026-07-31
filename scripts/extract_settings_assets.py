#!/usr/bin/env python3
"""从 ImageGen 设置页图集中无损提取 Android 运行时素材。"""

from __future__ import annotations

from pathlib import Path

from PIL import Image


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = PROJECT_ROOT / "docs/art_direction/settings_asset_sources"
OUTPUT_DIR = PROJECT_ROOT / "feature-game/src/main/res/drawable-nodpi"

SPRITE_SOURCE = SOURCE_DIR / "parking_settings_ui_kit_transparent.png"
BACKGROUND_SOURCE = SOURCE_DIR / "parking_settings_background_source.png"

# 每个区域之间保留了大块透明隔离带；先限定区域，再按 alpha 边界精确裁切，避免阴影串图。
COMPONENT_REGIONS = {
    "parking_settings_title_plaque": (220, 35, 1035, 245),
    "parking_settings_back_button": (535, 245, 715, 415),
    "parking_settings_row_language": (205, 395, 1045, 605),
    "parking_settings_row_feedback": (205, 600, 1045, 810),
    "parking_settings_row_privacy": (205, 805, 1045, 1015),
    "parking_settings_row_version": (205, 1010, 1045, 1220),
}


def crop_component(source: Image.Image, region: tuple[int, int, int, int]) -> Image.Image:
    crop = source.crop(region)
    alpha_bounds = crop.getchannel("A").getbbox()
    if alpha_bounds is None:
        raise ValueError(f"No visible pixels found inside region {region}")

    # 透明安全边防止缩放采样时切掉柔和阴影或抗锯齿像素。
    padding = 8
    left = max(0, alpha_bounds[0] - padding)
    top = max(0, alpha_bounds[1] - padding)
    right = min(crop.width, alpha_bounds[2] + padding)
    bottom = min(crop.height, alpha_bounds[3] + padding)
    return crop.crop((left, top, right, bottom))


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    sprite = Image.open(SPRITE_SOURCE).convert("RGBA")
    if sprite.size != (1254, 1254):
        raise ValueError(f"Unexpected sprite size: {sprite.size}")

    for asset_name, region in COMPONENT_REGIONS.items():
        component = crop_component(sprite, region)
        output_path = OUTPUT_DIR / f"{asset_name}.webp"
        component.save(output_path, "WEBP", lossless=True, method=6)

    background = Image.open(BACKGROUND_SOURCE).convert("RGB")
    if background.size != (941, 1672):
        raise ValueError(f"Unexpected background size: {background.size}")
    background.save(
        OUTPUT_DIR / "parking_settings_courtyard.webp",
        "WEBP",
        quality=92,
        method=6,
    )


if __name__ == "__main__":
    main()
