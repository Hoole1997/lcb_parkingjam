#!/usr/bin/env python3
"""Generate the static main_006..main_030 level pack.

The layout generator supports vehicles, straight exits and objectives. It expresses geometric
difficulty through independent outward-facing vehicle chains, then applies the shared V2 parking
strategy authoring rules. Every chain is monotonic: removing its edge vehicle can only expose the
next vehicle, so generated layouts remain compatible with production solution validation.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from parking_level_v2 import apply_parking_schema_v2


Direction = Literal["north", "east", "south", "west"]
Orientation = Literal["vertical", "horizontal"]


@dataclass(frozen=True)
class LevelSpec:
    number: int
    width: int
    height: int
    group_sizes: tuple[int, ...]
    difficulty: str
    orientation: Orientation
    truck_count: int = 0
    mode: str = "normal"
    reward_profile: str = "main_default"
    rescue_length: int = 0

    @property
    def vehicle_count(self) -> int:
        return sum(self.group_sizes)


# Group sizes are dependency-chain lengths. Their count is the exact number of safe first moves.
# Dimensions, vehicle counts, modes and difficulty tiers follow the PRD table wherever the current
# static rules can represent them without pretending that an unsupported mechanic exists.
LEVEL_SPECS = (
    LevelSpec(6, 6, 7, (2, 2, 1, 1), "d2", "horizontal"),
    LevelSpec(7, 6, 7, (3, 2, 1, 1), "d2", "vertical"),
    LevelSpec(8, 6, 7, (3, 2, 2, 1), "d2", "horizontal"),
    LevelSpec(9, 6, 8, (3, 2, 1, 1, 1), "d3", "vertical", truck_count=1),
    LevelSpec(10, 7, 8, (3, 3, 2, 2, 2), "d4", "horizontal", truck_count=2, mode="boss", reward_profile="boss_60"),
    LevelSpec(11, 6, 8, (3, 2, 1), "d2", "vertical"),
    LevelSpec(12, 6, 8, (3, 2, 2, 1), "d2", "horizontal"),
    LevelSpec(13, 7, 8, (2, 2, 1, 1, 1, 1, 1), "d2", "vertical"),
    LevelSpec(14, 7, 8, (3, 2, 2, 1, 1, 1), "d3", "horizontal"),
    LevelSpec(15, 7, 8, (2, 2, 2, 2, 1, 1, 1, 1), "d1", "vertical"),
    LevelSpec(16, 7, 8, (3, 2, 2, 1), "d2", "horizontal"),
    LevelSpec(17, 7, 8, (4, 2, 2, 1, 1), "d3", "vertical"),
    LevelSpec(18, 7, 8, (2, 2, 2, 1, 1, 1), "d2", "horizontal"),
    LevelSpec(19, 7, 9, (2, 2, 2, 2, 1, 1, 1, 1), "d3", "vertical", truck_count=2),
    LevelSpec(20, 8, 9, (3, 3, 3, 3, 3, 3), "d4", "horizontal", truck_count=2, mode="boss", reward_profile="boss_60"),
    LevelSpec(21, 7, 9, (2, 2, 1, 1, 1, 1, 1, 1, 1, 1), "d3", "vertical"),
    LevelSpec(22, 7, 9, (4, 1, 1, 1, 1, 1, 1), "d2", "vertical", mode="rescue", rescue_length=2),
    LevelSpec(23, 7, 9, (2, 2, 2, 2, 1, 1, 1, 1, 1), "d1", "horizontal"),
    LevelSpec(24, 7, 9, (3, 2, 2, 1, 1, 1, 1), "d2", "vertical"),
    LevelSpec(25, 7, 9, (4, 2, 1, 1, 1, 1, 1, 1, 1), "d3", "vertical", mode="rescue", rescue_length=2),
    # Three groups cannot physically hold 14 vehicles (including two trucks) on an 8x9 board.
    # Four deep chains preserve the intended hard-preview pressure without introducing overlap.
    LevelSpec(26, 8, 9, (4, 4, 3, 3), "d4", "vertical", truck_count=2, mode="hard_preview"),
    LevelSpec(27, 8, 9, (3, 3, 2, 2, 2, 2), "d3", "horizontal"),
    LevelSpec(28, 8, 10, (3, 3, 3, 2, 2, 2, 2), "d4", "vertical", truck_count=2),
    LevelSpec(29, 8, 10, (2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1), "d1", "horizontal"),
    LevelSpec(30, 8, 10, (4, 4, 4, 4, 3, 3, 2), "d5", "vertical", truck_count=3, mode="rescue", reward_profile="boss_120", rescue_length=3),
)


@dataclass
class VehicleSeed:
    vehicle_id: str
    vehicle_type: str = "car"
    length: int = 2


@dataclass(frozen=True)
class LaneSlot:
    lane: int
    direction: Direction


def vehicle_id_pool(reserve_rescue: bool) -> list[str]:
    ids = [chr(code) for code in range(ord("A"), ord("Z") + 1)]
    if reserve_rescue:
        ids.remove("R")
    return ids


def build_groups(spec: LevelSpec) -> list[list[VehicleSeed]]:
    reserve_rescue = spec.rescue_length > 0
    ids = iter(vehicle_id_pool(reserve_rescue))
    groups: list[list[VehicleSeed]] = []
    for group_index, group_size in enumerate(spec.group_sizes):
        group: list[VehicleSeed] = []
        for item_index in range(group_size):
            is_rescue = reserve_rescue and group_index == 0 and item_index == group_size - 1
            vehicle_id = "R" if is_rescue else next(ids)
            group.append(
                VehicleSeed(
                    vehicle_id=vehicle_id,
                    vehicle_type="rescue" if is_rescue else "car",
                    length=spec.rescue_length if is_rescue else 2,
                )
            )
        groups.append(group)

    # Spread long vehicles across different chains to preserve the configured safe-move count.
    for group_index in range(spec.truck_count):
        seed = groups[group_index][0]
        seed.vehicle_type = "truck"
        seed.length = 3
    return groups


def assign_lane_slots(
    groups: list[list[VehicleSeed]],
    orientation: Orientation,
    lane_count: int,
    span: int,
) -> list[LaneSlot]:
    negative: Direction = "north" if orientation == "vertical" else "west"
    positive: Direction = "south" if orientation == "vertical" else "east"
    totals = [0] * lane_count
    directions_by_lane: list[set[Direction]] = [set() for _ in range(lane_count)]
    slots: list[LaneSlot] = []

    primary_count = min(len(groups), lane_count)
    for group_index in range(primary_count):
        direction = negative if group_index % 2 == 0 else positive
        group_length = sum(seed.length for seed in groups[group_index])
        if group_length > span:
            raise ValueError(f"Group {group_index} does not fit span {span}")
        totals[group_index] = group_length
        directions_by_lane[group_index].add(direction)
        slots.append(LaneSlot(group_index, direction))

    for group_index in range(primary_count, len(groups)):
        group_length = sum(seed.length for seed in groups[group_index])
        candidates = sorted(
            (
                (totals[lane], lane)
                for lane in range(lane_count)
                if len(directions_by_lane[lane]) == 1 and totals[lane] + group_length <= span
            )
        )
        if not candidates:
            raise ValueError(f"No lane can fit group {group_index} in level layout")
        _, lane = candidates[0]
        existing_direction = next(iter(directions_by_lane[lane]))
        direction = positive if existing_direction == negative else negative
        totals[lane] += group_length
        directions_by_lane[lane].add(direction)
        slots.append(LaneSlot(lane, direction))
    return slots


def place_group(
    group: list[VehicleSeed],
    slot: LaneSlot,
    orientation: Orientation,
    span: int,
    required_ids: set[str],
) -> list[dict]:
    placed: list[dict] = []
    consumed = 0
    starts_at_minimum = slot.direction in {"north", "west"}
    for seed in group:
        position = consumed if starts_at_minimum else span - consumed - seed.length
        anchor = (
            {"x": slot.lane, "y": position}
            if orientation == "vertical"
            else {"x": position, "y": slot.lane}
        )
        placed.append(
            {
                "vehicle_id": seed.vehicle_id,
                "type": seed.vehicle_type,
                "anchor": anchor,
                "direction": slot.direction,
                "length": seed.length,
                "required": seed.vehicle_id in required_ids,
                "tow_prohibited": seed.vehicle_type == "rescue",
            }
        )
        consumed += seed.length
    return placed


def canonical_solution(groups: list[list[VehicleSeed]], rescue: bool) -> list[str]:
    if rescue:
        # The rescue vehicle is deliberately the deepest vehicle in the first dependency chain.
        return [seed.vehicle_id for seed in groups[0]]

    solution: list[str] = []
    for depth in range(max(map(len, groups))):
        for group in groups:
            if depth < len(group):
                solution.append(group[depth].vehicle_id)
    return solution


def build_level(spec: LevelSpec) -> dict:
    groups = build_groups(spec)
    is_rescue = spec.rescue_length > 0
    all_ids = {seed.vehicle_id for group in groups for seed in group}
    required_ids = {"R"} if is_rescue else all_ids
    lane_count = spec.width if spec.orientation == "vertical" else spec.height
    span = spec.height if spec.orientation == "vertical" else spec.width
    slots = assign_lane_slots(groups, spec.orientation, lane_count, span)
    vehicles = [
        vehicle
        for group, slot in zip(groups, slots)
        for vehicle in place_group(group, slot, spec.orientation, span, required_ids)
    ]
    solution = canonical_solution(groups, is_rescue)
    allowed_types = [
        vehicle_type
        for vehicle_type in ("car", "truck", "rescue")
        if any(vehicle["type"] == vehicle_type for vehicle in vehicles)
    ]
    if spec.orientation == "vertical":
        exits = [
            {
                "exit_id": "north_full",
                "direction": "north",
                "offset": 0,
                "length": spec.width,
                "allowed_vehicle_types": allowed_types,
            },
            {
                "exit_id": "south_full",
                "direction": "south",
                "offset": 0,
                "length": spec.width,
                "allowed_vehicle_types": allowed_types,
            },
        ]
    else:
        exits = [
            {
                "exit_id": "east_full",
                "direction": "east",
                "offset": 0,
                "length": spec.height,
                "allowed_vehicle_types": allowed_types,
            },
            {
                "exit_id": "west_full",
                "direction": "west",
                "offset": 0,
                "length": spec.height,
                "allowed_vehicle_types": allowed_types,
            },
        ]

    if is_rescue:
        objective = {"type": "rescue_target", "required_vehicle_ids": ["R"]}
    elif spec.mode == "boss":
        objective = {"type": "boss_clear", "required_vehicle_ids": sorted(required_ids)}
    else:
        objective = {"type": "clear_all", "required_vehicle_ids": sorted(required_ids)}

    occupied_cells = sum(vehicle["length"] for vehicle in vehicles)
    tags = ["rescue" if is_rescue else ("hard" if spec.mode == "hard_preview" else spec.mode)]
    if spec.mode == "rescue" and spec.number == 30:
        tags.append("boss")
    tags.append("dependency")
    if spec.difficulty == "d1":
        tags.append("flow")

    prerequisite_number = 25 if spec.number == 27 else spec.number - 1
    level = {
        "schema_version": 2,
        "level_id": f"main_{spec.number:03d}",
        "level_version": 2,
        "rule_version": 2,
        "chapter_id": "chapter_01",
        "display_number": spec.number,
        "mode": spec.mode,
        "difficulty_tier": spec.difficulty,
        "progression": {
            "prerequisite_level_ids": [f"main_{prerequisite_number:03d}"],
            "skippable": spec.number == 26,
        },
        "board": {"width": spec.width, "height": spec.height},
        "objective": objective,
        "vehicles": vehicles,
        "exits": exits,
        "initial_safety": {"mode": "unlimited"},
        "reward_profile_id": spec.reward_profile,
        "tutorial_directives": [],
        "canonical_solution": solution,
        "difficulty_metrics": {
            "vehicle_count": spec.vehicle_count,
            "required_count": len(required_ids),
            "solution_steps": len(solution),
            "dependency_depth": max(spec.group_sizes),
            "safe_first_moves": len(spec.group_sizes),
            "false_affordances": spec.vehicle_count - len(spec.group_sizes),
            "mechanic_count": 0,
            "board_density": round(occupied_cells / (spec.width * spec.height), 4),
            "branching_mean": round(max(1.0, len(spec.group_sizes) * 0.65), 1),
        },
        "content_tags": tags,
    }
    return apply_parking_schema_v2(level)


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    output_directory = project_root / "game-data" / "src" / "main" / "assets" / "levels"
    for spec in LEVEL_SPECS:
        level = build_level(spec)
        output_path = output_directory / f"main_{spec.number:03d}.json"
        output_path.write_text(
            json.dumps(level, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
