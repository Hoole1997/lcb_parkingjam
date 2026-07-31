package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.BoardSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState

/** 原棋盘目标与全部颜色订单同时满足时才允许结算。 */
object CompletionEvaluator {
    fun isSatisfied(
        level: LevelDefinition,
        board: BoardSnapshot,
        parkingLot: ParkingLotSnapshot,
    ): Boolean {
        val originalSatisfied = originalObjectiveSatisfied(level, board)
        return if (level.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            originalSatisfied && ParkingOrderRouter.allOrdersComplete(level.parkingRules, parkingLot)
        } else {
            originalSatisfied
        }
    }

    private fun originalObjectiveSatisfied(level: LevelDefinition, board: BoardSnapshot): Boolean {
        fun cleared(vehicleId: VehicleId): Boolean = when (board.vehicles[vehicleId]) {
            is VehicleRuleState.ExitCommitted, VehicleRuleState.Towed -> true
            else -> false
        }
        fun driven(vehicleId: VehicleId): Boolean =
            board.vehicles[vehicleId] is VehicleRuleState.ExitCommitted

        return when (val objective = level.objective) {
            is LevelObjective.ClearAll -> objective.requiredVehicleIds.all(::cleared)
            is LevelObjective.RescueTarget -> driven(objective.targetVehicleId)
            is LevelObjective.BossClear -> {
                objective.requiredVehicleIds.all(::cleared) &&
                    objective.requiredOpenGateIds.all(board.openGateIds::contains) &&
                    objective.rescueTargetVehicleId?.let(::driven) != false
            }
        }
    }

    private const val PARKING_ORDER_RULE_VERSION = 2
}
