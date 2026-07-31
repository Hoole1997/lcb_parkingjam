package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.BoardSnapshot
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.ExitDefinition
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState

/**
 * 只负责整数格路径判定。停车位、颜色订单、动画和 Android 像素都不能进入此组件。
 */
object BoardPathResolver {
    fun resolve(
        level: LevelDefinition,
        board: BoardSnapshot,
        vehicle: VehicleDefinition,
    ): BoardPathResult {
        val boundary = boundaryCell(level, vehicle.headCell(), vehicle.direction)
        val exit = level.exits.firstOrNull {
            it.boundaryCell == boundary &&
                it.direction == vehicle.direction &&
                vehicle.type in it.allowedVehicleTypes
        } ?: return BoardPathResult.Blocked(
            kind = CollisionKind.CLOSED_BOUNDARY,
            clearCellCount = 0,
        )

        val path = buildList {
            var cursor = vehicle.headCell().step(vehicle.direction)
            while (level.board.contains(cursor)) {
                add(cursor)
                cursor = cursor.step(vehicle.direction)
            }
        }
        val occupiedByOther = buildMap<Cell, VehicleId> {
            level.vehicles.forEach { other ->
                if (other.id != vehicle.id && board.vehicles[other.id].isOnBoard()) {
                    other.occupiedCells().forEach { put(it, other.id) }
                }
            }
        }
        val wallCells = level.walls.flatMapTo(mutableSetOf()) { it.cells }
        val closedGateCells = level.gates
            .asSequence()
            .filterNot { it.id in board.openGateIds }
            .flatMap { it.cells.asSequence() }
            .toSet()

        path.forEachIndexed { index, cell ->
            when {
                occupiedByOther.containsKey(cell) -> return BoardPathResult.Blocked(
                    kind = CollisionKind.VEHICLE,
                    clearCellCount = index,
                    blockerVehicleId = occupiedByOther[cell],
                )
                cell in wallCells -> return BoardPathResult.Blocked(
                    kind = CollisionKind.WALL,
                    clearCellCount = index,
                )
                cell in closedGateCells -> return BoardPathResult.Blocked(
                    kind = CollisionKind.CLOSED_GATE,
                    clearCellCount = index,
                )
            }
        }
        return BoardPathResult.Open(exit, path)
    }

    private fun boundaryCell(level: LevelDefinition, head: Cell, direction: Direction): Cell =
        when (direction) {
            Direction.NORTH -> Cell(head.x, 0)
            Direction.SOUTH -> Cell(head.x, level.board.height - 1)
            Direction.WEST -> Cell(0, head.y)
            Direction.EAST -> Cell(level.board.width - 1, head.y)
        }

    private fun VehicleRuleState?.isOnBoard(): Boolean =
        this == VehicleRuleState.OnBoard || this is VehicleRuleState.Locked
}

sealed interface BoardPathResult {
    data class Open(
        val exit: ExitDefinition,
        val cells: List<Cell>,
    ) : BoardPathResult

    data class Blocked(
        val kind: CollisionKind,
        /** 阻挡格之前可安全前冲的完整格数。 */
        val clearCellCount: Int,
        val blockerVehicleId: VehicleId? = null,
    ) : BoardPathResult
}
