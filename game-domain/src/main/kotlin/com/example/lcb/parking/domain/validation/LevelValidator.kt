package com.example.lcb.parking.domain.validation

import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType

data class LevelValidationIssue(
    val code: String,
    val message: String,
)

data class LevelValidationReport(val issues: List<LevelValidationIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

/** Fast structural checks shared by built-in content loading and offline publishing tools. */
object LevelValidator {
    private const val MAX_BOARD_WIDTH = 10
    private const val MAX_BOARD_HEIGHT = 12

    fun validateStructure(level: LevelDefinition): LevelValidationReport {
        val issues = mutableListOf<LevelValidationIssue>()
        fun issue(code: String, message: String) {
            issues += LevelValidationIssue(code, message)
        }

        if (level.levelVersion <= 0) issue("LEVEL_VERSION", "levelVersion must be positive")
        if (level.ruleVersion <= 0) issue("RULE_VERSION", "ruleVersion must be positive")
        if (level.displayNumber <= 0) issue("DISPLAY_NUMBER", "displayNumber must be positive")
        if (level.chapterId.isBlank()) issue("CHAPTER_ID", "chapterId must not be blank")
        if (level.board.width !in 1..MAX_BOARD_WIDTH || level.board.height !in 1..MAX_BOARD_HEIGHT) {
            issue(
                "BOARD_SIZE",
                "Board ${level.board.width}x${level.board.height} is outside supported bounds",
            )
        }

        val duplicateVehicleIds = level.vehicles.groupingBy(VehicleDefinition::id)
            .eachCount().filterValues { it > 1 }.keys
        duplicateVehicleIds.forEach { issue("VEHICLE_ID_DUPLICATE", "Duplicate vehicle ${it.value}") }

        val occupied = mutableMapOf<Cell, VehicleId>()
        level.vehicles.forEach { vehicle ->
            if (vehicle.length <= 0) {
                issue("VEHICLE_LENGTH", "Vehicle ${vehicle.id.value} has invalid length")
            }
            vehicle.occupiedCells().forEach { cell ->
                if (!level.board.contains(cell)) {
                    issue("VEHICLE_OUT_OF_BOUNDS", "Vehicle ${vehicle.id.value} occupies $cell")
                }
                val previous = occupied.putIfAbsent(cell, vehicle.id)
                if (previous != null && previous != vehicle.id) {
                    issue(
                        "VEHICLE_OVERLAP",
                        "Vehicles ${previous.value} and ${vehicle.id.value} overlap at $cell",
                    )
                }
            }
            if (!hasAlignedExit(level, vehicle)) {
                issue("EXIT_UNREACHABLE", "Vehicle ${vehicle.id.value} has no aligned compatible exit")
            }
        }

        val exitIds = mutableSetOf<String>()
        level.exits.forEach { exit ->
            if (!exitIds.add(exit.id.value)) issue("EXIT_ID_DUPLICATE", "Duplicate exit ${exit.id.value}")
            if (!level.board.contains(exit.boundaryCell) || !isOnExpectedEdge(level, exit.boundaryCell, exit.direction)) {
                issue("EXIT_BOUNDARY", "Exit ${exit.id.value} is not on its declared edge")
            }
            if (exit.allowedVehicleTypes.isEmpty()) {
                issue("EXIT_TYPES", "Exit ${exit.id.value} allows no vehicle type")
            }
        }

        val blockedCells = mutableSetOf<Cell>()
        level.walls.forEach { wall ->
            if (wall.id.isBlank()) issue("WALL_ID", "Wall ID must not be blank")
            wall.cells.forEach { cell ->
                if (!level.board.contains(cell)) issue("WALL_OUT_OF_BOUNDS", "Wall ${wall.id} occupies $cell")
                if (occupied.containsKey(cell)) issue("WALL_OVERLAP", "Wall ${wall.id} overlaps a vehicle at $cell")
                blockedCells += cell
            }
        }

        val gateIds = level.gates.map { it.id }.toSet()
        if (gateIds.size != level.gates.size) issue("GATE_ID_DUPLICATE", "Gate IDs must be unique")
        level.gates.forEach { gate ->
            gate.cells.forEach { cell ->
                if (!level.board.contains(cell)) issue("GATE_OUT_OF_BOUNDS", "Gate ${gate.id.value} occupies $cell")
                if (occupied.containsKey(cell)) issue("GATE_OVERLAP", "Gate ${gate.id.value} overlaps a vehicle at $cell")
                if (cell in blockedCells) issue("GATE_WALL_OVERLAP", "Gate ${gate.id.value} overlaps a wall at $cell")
            }
        }

        val vehicleById = level.vehicles.associateBy(VehicleDefinition::id)

        if (level.parkingRules.capacity <= 0) {
            issue("PARKING_CAPACITY", "Parking capacity must be positive")
        }
        if (level.parkingRules.orders.isEmpty()) {
            issue("PARKING_ORDERS_EMPTY", "At least one color order is required")
        }
        val duplicateOrderIds = level.parkingRules.orders
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateOrderIds.forEach { orderId ->
            issue("ORDER_ID_DUPLICATE", "Duplicate order ${orderId.value}")
        }
        level.parkingRules.orders.forEach { order ->
            if (order.requiredCount <= 0) {
                issue("ORDER_COUNT", "Order ${order.id.value} must require at least one vehicle")
            }
        }
        level.parkingRules.orders.zipWithNext().forEach { (first, second) ->
            if (first.color == second.color) {
                issue(
                    "ORDER_COLOR_ADJACENT",
                    "Adjacent ${first.color} orders should be merged",
                )
            }
        }
        val vehicleCountByColor = level.vehicles.groupingBy(VehicleDefinition::color).eachCount()
        val demandByColor = level.parkingRules.orders
            .groupingBy { it.color }
            .fold(0) { total, order -> total + order.requiredCount }
        demandByColor.forEach { (color, demand) ->
            val available = vehicleCountByColor[color] ?: 0
            if (demand > available) {
                issue(
                    "ORDER_COLOR_SUPPLY",
                    "Orders require $demand $color vehicles but level contains $available",
                )
            }
        }

        level.pressurePlates.forEach { plate ->
            if (plate.triggeringVehicleId !in vehicleById) {
                issue("PLATE_TRIGGER", "Plate ${plate.id.value} references a missing vehicle")
            }
            plate.opensGateIds.filterNot(gateIds::contains).forEach { gateId ->
                issue("PLATE_GATE", "Plate ${plate.id.value} references missing gate ${gateId.value}")
            }
            plate.cells.forEach { cell ->
                if (!level.board.contains(cell)) issue("PLATE_OUT_OF_BOUNDS", "Plate ${plate.id.value} occupies $cell")
            }
        }

        level.vehicles.forEach { vehicle ->
            val keyId = vehicle.lockedBy ?: return@forEach
            val keyVehicle = vehicleById[keyId]
            if (keyVehicle == null) {
                issue("LOCK_KEY_MISSING", "Vehicle ${vehicle.id.value} references missing key ${keyId.value}")
            } else if (keyVehicle.type != VehicleType.KEY_CAR) {
                issue("LOCK_KEY_TYPE", "Vehicle ${keyId.value} is not a key car")
            }
        }
        findLockCycle(level.vehicles)?.let { cycle ->
            issue("LOCK_CYCLE", "Lock dependency contains cycle at ${cycle.value}")
        }

        val requiredIds = level.objective.requiredVehicleIds
        requiredIds.filterNot(vehicleById::containsKey).forEach { id ->
            issue("OBJECTIVE_VEHICLE", "Objective references missing vehicle ${id.value}")
        }
        when (val objective = level.objective) {
            is LevelObjective.RescueTarget -> {
                val target = vehicleById[objective.targetVehicleId]
                if (target != null && (!target.towProhibited || target.type != VehicleType.RESCUE)) {
                    issue("RESCUE_TARGET", "Rescue target must be a tow-prohibited rescue vehicle")
                }
            }
            is LevelObjective.BossClear -> objective.requiredOpenGateIds.filterNot(gateIds::contains)
                .forEach { issue("OBJECTIVE_GATE", "Objective references missing gate ${it.value}") }
            is LevelObjective.ClearAll -> Unit
        }

        level.canonicalSolution.forEach { action ->
            val vehicleId = when (action) {
                is CanonicalAction.ExitVehicle -> action.vehicleId
            }
            if (vehicleId !in vehicleById) {
                issue("CANONICAL_VEHICLE", "Canonical solution references ${vehicleId.value}")
            }
        }
        if (level.canonicalSolution.isEmpty() && requiredIds.isNotEmpty()) {
            issue("CANONICAL_EMPTY", "A non-empty objective requires a canonical solution")
        }
        if (level.displayNumber in 1..5 && level.initialSafety !is InitialSafety.TutorialUnlimited) {
            issue("TUTORIAL_SAFETY", "L1-L5 must use unlimited tutorial safety")
        }
        if (level.initialSafety is InitialSafety.Limited && level.initialSafety.points <= 0) {
            issue("INITIAL_SAFETY", "Limited safety must be positive")
        }

        return LevelValidationReport(issues)
    }

    private fun hasAlignedExit(level: LevelDefinition, vehicle: VehicleDefinition): Boolean {
        val lane = vehicle.headCell()
        return level.exits.any { exit ->
            exit.direction == vehicle.direction &&
                vehicle.type in exit.allowedVehicleTypes &&
                when (vehicle.direction) {
                    Direction.NORTH, Direction.SOUTH -> exit.boundaryCell.x == lane.x
                    Direction.EAST, Direction.WEST -> exit.boundaryCell.y == lane.y
                }
        }
    }

    private fun isOnExpectedEdge(level: LevelDefinition, cell: Cell, direction: Direction): Boolean =
        when (direction) {
            Direction.NORTH -> cell.y == 0
            Direction.EAST -> cell.x == level.board.width - 1
            Direction.SOUTH -> cell.y == level.board.height - 1
            Direction.WEST -> cell.x == 0
        }

    private fun findLockCycle(vehicles: List<VehicleDefinition>): VehicleId? {
        val dependency = vehicles.associate { it.id to it.lockedBy }
        val fullyVisited = mutableSetOf<VehicleId>()
        dependency.keys.forEach { start ->
            val path = mutableSetOf<VehicleId>()
            var cursor: VehicleId? = start
            while (cursor != null && cursor !in fullyVisited) {
                if (!path.add(cursor)) return cursor
                cursor = dependency[cursor]
            }
            fullyVisited += path
        }
        return null
    }
}
