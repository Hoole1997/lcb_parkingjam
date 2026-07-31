#!/usr/bin/env python3
"""Shared deterministic authoring rules for the parking-strategy level schema V2.

The production app never derives vehicle colors from list positions. This module is used only by
offline content generators: it groups the authored canonical solution into ordered color batches,
writes every vehicle color explicitly, and validates that the canonical sequence can always bypass
the finite waiting lot by matching the active order.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Iterable


LEVEL_SCHEMA_VERSION = 2
LEVEL_RULE_VERSION = 2
LEVEL_CONTENT_VERSION = 2

PALETTE = ("coral", "blue", "yellow", "purple", "mint", "red")


@dataclass(frozen=True)
class ParkingProgression:
    palette_size: int
    waiting_capacity: int
    max_order_size: int


def progression_for(level_number: int) -> ParkingProgression:
    """Returns deterministic content knobs for one mainline level."""
    if not 1 <= level_number <= 30:
        raise ValueError(f"Unsupported mainline level number: {level_number}")

    if level_number == 1:
        palette_size = 1
    elif level_number <= 3:
        palette_size = 2
    elif level_number <= 8:
        palette_size = 3
    elif level_number <= 16:
        palette_size = 4
    elif level_number <= 25:
        palette_size = 5
    else:
        palette_size = 6

    if level_number <= 2:
        capacity = 2
    elif level_number <= 5:
        capacity = 3
    elif level_number <= 15:
        capacity = 4
    else:
        # Five waiting slots fit the narrowest supported phone without horizontal scrolling.
        capacity = 5

    if level_number <= 2:
        max_order_size = 1
    elif level_number <= 6:
        max_order_size = 2
    elif level_number <= 16:
        max_order_size = 3
    elif level_number <= 25:
        max_order_size = 4
    else:
        max_order_size = 5

    return ParkingProgression(
        palette_size=palette_size,
        waiting_capacity=capacity,
        max_order_size=max_order_size,
    )


def balanced_group_sizes(
    item_count: int,
    palette_size: int,
    max_order_size: int,
) -> list[int]:
    """Splits a solution into balanced, non-empty, bounded consecutive orders."""
    if item_count <= 0:
        raise ValueError("Canonical solution must not be empty")
    if palette_size <= 0 or max_order_size <= 0:
        raise ValueError("Palette and order sizes must be positive")

    minimum_groups_for_capacity = math.ceil(item_count / max_order_size)
    group_count = min(item_count, max(palette_size, minimum_groups_for_capacity))
    base_size, remainder = divmod(item_count, group_count)
    sizes = [base_size + (1 if index < remainder else 0) for index in range(group_count)]
    if any(size <= 0 or size > max_order_size for size in sizes):
        raise AssertionError(f"Invalid generated order sizes: {sizes}")
    return sizes


def apply_parking_schema_v2(level: dict[str, Any]) -> dict[str, Any]:
    """Mutates and returns one parsed level document using the deterministic V2 strategy."""
    level_number = _required_positive_int(level, "display_number")
    level_id = _required_string(level, "level_id")
    canonical = _required_string_list(level, "canonical_solution")
    vehicles = level.get("vehicles")
    if not isinstance(vehicles, list) or not vehicles:
        raise ValueError(f"{level_id}: vehicles must be a non-empty list")

    vehicle_by_id: dict[str, dict[str, Any]] = {}
    for vehicle in vehicles:
        if not isinstance(vehicle, dict):
            raise ValueError(f"{level_id}: vehicle entry must be an object")
        vehicle_id = _required_string(vehicle, "vehicle_id")
        if vehicle_id in vehicle_by_id:
            raise ValueError(f"{level_id}: duplicate vehicle_id={vehicle_id}")
        vehicle_by_id[vehicle_id] = vehicle
    if len(set(canonical)) != len(canonical):
        raise ValueError(f"{level_id}: canonical_solution contains duplicate vehicles")
    unknown_solution_ids = [
        vehicle_id
        for vehicle_id in canonical
        if vehicle_id not in vehicle_by_id
    ]
    if unknown_solution_ids:
        raise ValueError(
            f"{level_id}: canonical_solution contains unknown IDs {unknown_solution_ids}"
        )

    progression = progression_for(level_number)
    palette = PALETTE[: progression.palette_size]
    sizes = balanced_group_sizes(
        item_count=len(canonical),
        palette_size=progression.palette_size,
        max_order_size=progression.max_order_size,
    )

    orders: list[dict[str, Any]] = []
    colors_by_vehicle: dict[str, str] = {}
    canonical_offset = 0
    for order_index, size in enumerate(sizes):
        color_id = palette[order_index % len(palette)]
        order_vehicle_ids = canonical[canonical_offset : canonical_offset + size]
        canonical_offset += size
        for vehicle_id in order_vehicle_ids:
            colors_by_vehicle[vehicle_id] = color_id
        orders.append(
            {
                "order_id": f"order_{order_index + 1:02d}",
                "color_id": color_id,
                "required_count": size,
            }
        )

    # Rescue levels intentionally order only A/B/C/R. Distractors use colors absent from all
    # orders, so they remain visually meaningful without being able to satisfy a rescue order.
    order_colors = {order["color_id"] for order in orders}
    distractor_palette = tuple(color for color in palette if color not in order_colors)
    if len(canonical) < len(vehicles) and not distractor_palette:
        raise ValueError(f"{level_id}: rescue distractors require a color absent from orders")
    distractor_index = 0
    for vehicle in vehicles:
        vehicle_id = vehicle["vehicle_id"]
        color_id = colors_by_vehicle.get(vehicle_id)
        if color_id is None:
            color_id = distractor_palette[distractor_index % len(distractor_palette)]
            distractor_index += 1
        vehicle["color_id"] = color_id

    level["schema_version"] = LEVEL_SCHEMA_VERSION
    level["level_version"] = LEVEL_CONTENT_VERSION
    level["rule_version"] = LEVEL_RULE_VERSION
    level["initial_safety"] = {"mode": "unlimited"}
    level["parking_rules"] = {
        "capacity": progression.waiting_capacity,
        "overflow_policy": "reject_exit",
        "orders": orders,
    }
    validate_parking_schema_v2(level)
    return level


def validate_parking_schema_v2(level: dict[str, Any]) -> None:
    """Checks authoring invariants without reproducing the runtime reducer."""
    level_id = _required_string(level, "level_id")
    if level.get("schema_version") != LEVEL_SCHEMA_VERSION:
        raise ValueError(f"{level_id}: schema_version must be {LEVEL_SCHEMA_VERSION}")
    if level.get("rule_version") != LEVEL_RULE_VERSION:
        raise ValueError(f"{level_id}: rule_version must be {LEVEL_RULE_VERSION}")

    vehicles = level.get("vehicles")
    if not isinstance(vehicles, list) or not vehicles:
        raise ValueError(f"{level_id}: vehicles must be a non-empty list")
    color_by_vehicle: dict[str, str] = {}
    for vehicle in vehicles:
        vehicle_id = _required_string(vehicle, "vehicle_id")
        color_id = _required_string(vehicle, "color_id")
        if color_id not in PALETTE:
            raise ValueError(f"{level_id}: unsupported vehicle color {color_id}")
        color_by_vehicle[vehicle_id] = color_id

    parking_rules = level.get("parking_rules")
    if not isinstance(parking_rules, dict):
        raise ValueError(f"{level_id}: parking_rules must be an object")
    capacity = _required_positive_int(parking_rules, "capacity")
    expected_capacity = progression_for(
        _required_positive_int(level, "display_number")
    ).waiting_capacity
    if capacity != expected_capacity:
        raise ValueError(
            f"{level_id}: expected parking capacity {expected_capacity}, found {capacity}"
        )
    if parking_rules.get("overflow_policy") != "reject_exit":
        raise ValueError(f"{level_id}: overflow_policy must be reject_exit")
    orders = parking_rules.get("orders")
    if not isinstance(orders, list) or not orders:
        raise ValueError(f"{level_id}: parking orders must be non-empty")

    canonical = _required_string_list(level, "canonical_solution")
    canonical_offset = 0
    seen_order_ids: set[str] = set()
    for order in orders:
        if not isinstance(order, dict):
            raise ValueError(f"{level_id}: parking order must be an object")
        order_id = _required_string(order, "order_id")
        if order_id in seen_order_ids:
            raise ValueError(f"{level_id}: duplicate order_id={order_id}")
        seen_order_ids.add(order_id)
        color_id = _required_string(order, "color_id")
        required_count = _required_positive_int(order, "required_count")
        source_vehicle_ids = canonical[canonical_offset : canonical_offset + required_count]
        if len(source_vehicle_ids) != required_count:
            raise ValueError(f"{level_id}: order counts exceed canonical_solution")
        mismatched = [
            vehicle_id
            for vehicle_id in source_vehicle_ids
            if color_by_vehicle.get(vehicle_id) != color_id
        ]
        if mismatched:
            raise ValueError(
                f"{level_id}: canonical vehicles {mismatched} do not match order color {color_id}"
            )
        canonical_offset += required_count
    if canonical_offset != len(canonical):
        raise ValueError(
            f"{level_id}: order count {canonical_offset} does not cover "
            f"canonical size {len(canonical)}"
        )

    canonical_ids = set(canonical)
    distractor_colors = {
        color_by_vehicle[vehicle_id]
        for vehicle_id in color_by_vehicle.keys() - canonical_ids
    }
    order_colors = {_required_string(order, "color_id") for order in orders}
    if distractor_colors & order_colors:
        raise ValueError(
            f"{level_id}: non-canonical distractor colors overlap ordered colors"
        )


def _required_string(document: dict[str, Any], field: str) -> str:
    value = document.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} must be a non-empty string")
    return value


def _required_positive_int(document: dict[str, Any], field: str) -> int:
    value = document.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _required_string_list(document: dict[str, Any], field: str) -> list[str]:
    value = document.get(field)
    if (
        not isinstance(value, list)
        or not value
        or not all(isinstance(item, str) and item for item in value)
    ):
        raise ValueError(f"{field} must be a non-empty string list")
    return value


def validate_level_pack(levels: Iterable[dict[str, Any]]) -> None:
    """Validates a contiguous 30-level pack and every V2 parking invariant."""
    documents = list(levels)
    if len(documents) != 30:
        raise ValueError(f"Expected 30 levels, found {len(documents)}")
    numbers = sorted(_required_positive_int(level, "display_number") for level in documents)
    if numbers != list(range(1, 31)):
        raise ValueError(f"Mainline levels are not contiguous: {numbers}")
    for level in documents:
        validate_parking_schema_v2(level)
