#!/usr/bin/env python3
"""从 ImageGen 图集中提取语言 BottomSheet 素材并生成 Android NinePatch。"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    PROJECT_ROOT
    / "docs/art_direction/language_sheet_asset_sources/parking_language_sheet_ui_kit_v2_transparent.png"
)
OUTPUT_DIR = PROJECT_ROOT / "app/src/main/res/drawable-xxhdpi"

REGIONS = {
    # 区域与 ImageGen v2 图集一一对应，保留少量透明边缘用于抗锯齿。
    "sheet": (156, 104, 989, 384),
    "group": (156, 446, 989, 676),
    "row_selected": (156, 735, 990, 909),
    "globe": (1088, 135, 1323, 374),
    "check": (1113, 468, 1294, 651),
    "radio": (1126, 745, 1282, 899),
}


def trim_alpha(source: Image.Image, region: tuple[int, int, int, int], padding: int = 3) -> Image.Image:
    crop = source.crop(region)
    bounds = crop.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError(f"No visible pixels found inside region {region}")
    left = max(0, bounds[0] - padding)
    top = max(0, bounds[1] - padding)
    right = min(crop.width, bounds[2] + padding)
    bottom = min(crop.height, bounds[3] + padding)
    return crop.crop((left, top, right, bottom))


def save_nine_patch(
    image: Image.Image,
    output_path: Path,
    stretch_x: tuple[int, int],
    stretch_y: tuple[int, int],
    content_x: tuple[int, int],
    content_y: tuple[int, int],
) -> None:
    """增加 Android 识别的 1px NinePatch 标记，固定圆角、描边和顶部拖拽条。"""
    width, height = image.size
    canvas = Image.new("RGBA", (width + 2, height + 2), (0, 0, 0, 0))
    canvas.paste(image, (1, 1), image)
    draw = ImageDraw.Draw(canvas)
    marker = (0, 0, 0, 255)

    draw.line((stretch_x[0] + 1, 0, stretch_x[1], 0), fill=marker, width=1)
    draw.line((0, stretch_y[0] + 1, 0, stretch_y[1]), fill=marker, width=1)
    draw.line((content_x[0] + 1, height + 1, content_x[1], height + 1), fill=marker, width=1)
    draw.line((width + 1, content_y[0] + 1, width + 1, content_y[1]), fill=marker, width=1)
    canvas.save(output_path, "PNG", optimize=True)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    sprite = Image.open(SOURCE).convert("RGBA")
    if sprite.size != (1536, 1024):
        raise ValueError(f"Unexpected sprite size: {sprite.size}")

    sheet = trim_alpha(sprite, REGIONS["sheet"], padding=0)
    save_nine_patch(
        sheet,
        OUTPUT_DIR / "parking_language_sheet_background.9.png",
        stretch_x=(sheet.width // 2 - 6, sheet.width // 2 + 6),
        # 只拉伸圆角下方的平整区域，避免顶部曲线和描边变形。
        stretch_y=(sheet.height // 2, sheet.height - 8),
        content_x=(48, sheet.width - 48),
        content_y=(26, sheet.height - 8),
    )

    unselected_row = trim_alpha(sprite, REGIONS["group"])
    save_nine_patch(
        unselected_row,
        OUTPUT_DIR / "parking_language_option_unselected.9.png",
        stretch_x=(unselected_row.width // 2 - 5, unselected_row.width // 2 + 5),
        stretch_y=(unselected_row.height // 2 - 3, unselected_row.height // 2 + 3),
        # 三个 Item 的内容区必须一致，选中时文字和图标不能跳动。
        content_x=(1, unselected_row.width - 1),
        content_y=(1, unselected_row.height - 1),
    )

    selected_row = trim_alpha(sprite, REGIONS["row_selected"])
    save_nine_patch(
        selected_row,
        OUTPUT_DIR / "parking_language_option_selected.9.png",
        stretch_x=(selected_row.width // 2 - 5, selected_row.width // 2 + 5),
        stretch_y=(selected_row.height // 2 - 3, selected_row.height // 2 + 3),
        # 行内文字和图标由 XML 控制间距，NinePatch 不额外侵占内容区。
        content_x=(1, selected_row.width - 1),
        content_y=(1, selected_row.height - 1),
    )

    for key, output_name in (
        ("radio", "parking_language_radio_unselected.webp"),
        ("check", "parking_language_radio_selected.webp"),
        ("globe", "parking_language_globe.webp"),
    ):
        icon = trim_alpha(sprite, REGIONS[key], padding=6)
        icon.save(OUTPUT_DIR / output_name, "WEBP", lossless=True, method=6)


if __name__ == "__main__":
    main()
