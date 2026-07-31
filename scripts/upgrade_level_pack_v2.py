#!/usr/bin/env python3
"""Upgrade and validate all checked-in mainline levels to parking-strategy schema V2."""

from __future__ import annotations

import json
from pathlib import Path

from parking_level_v2 import apply_parking_schema_v2, validate_level_pack


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    levels_directory = project_root / "game-data" / "src" / "main" / "assets" / "levels"
    level_paths = sorted(levels_directory.glob("main_*.json"))
    levels = [
        apply_parking_schema_v2(json.loads(path.read_text(encoding="utf-8")))
        for path in level_paths
    ]
    validate_level_pack(levels)
    for path, level in zip(level_paths, levels):
        path.write_text(
            json.dumps(level, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
