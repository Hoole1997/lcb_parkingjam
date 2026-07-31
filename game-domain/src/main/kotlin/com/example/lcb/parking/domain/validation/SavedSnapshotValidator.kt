package com.example.lcb.parking.domain.validation

import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.rules.CompletionEvaluator

data class SnapshotValidationIssue(
    val code: String,
    val message: String,
)

data class SnapshotValidationReport(val issues: List<SnapshotValidationIssue>) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

/**
 * Cross-checks a decoded save against the immutable level definition that will consume it.
 *
 * DTO validation can prove that a save is internally well formed, but it cannot know the authored
 * capacity, order colors or vehicle set. Keeping these checks in the pure domain module makes restore
 * behavior identical for Preferences, a future database and test fixtures.
 */
object SavedSnapshotValidator {
    fun validate(level: LevelDefinition, snapshot: GameSnapshot): SnapshotValidationReport {
        val issues = mutableListOf<SnapshotValidationIssue>()
        fun issue(code: String, message: String) {
            issues += SnapshotValidationIssue(code, message)
        }

        validateIdentity(level, snapshot, ::issue)
        validateBoard(level, snapshot, ::issue)
        validateParkingLot(level, snapshot, ::issue)

        if (snapshot.revision < 0L) {
            issue("SNAPSHOT_REVISION", "Snapshot revision must be non-negative")
        }
        if (snapshot.transientVehicleLocks.isNotEmpty()) {
            issue("TRANSIENT_LOCKS", "Persisted snapshots must not contain presentation locks")
        }

        // Only evaluate completion after the referenced collections are known to be safe to read.
        val hasShapeIssue = issues.any { validationIssue ->
            validationIssue.code in COMPLETION_BLOCKING_ISSUES
        }
        if (!hasShapeIssue) {
            val rulesSatisfied = CompletionEvaluator.isSatisfied(
                level = level,
                board = snapshot.board,
                parkingLot = snapshot.parkingLot,
            )
            when {
                snapshot.attempt.businessState ==
                    com.example.lcb.parking.domain.model.AttemptBusinessState.COMPLETE &&
                    !rulesSatisfied -> issue(
                    "COMPLETE_WITHOUT_RULES",
                    "Completed attempt does not satisfy its board objective and parking orders",
                )
                snapshot.attempt.businessState ==
                    com.example.lcb.parking.domain.model.AttemptBusinessState.ACTIVE &&
                    rulesSatisfied -> issue(
                    "ACTIVE_AFTER_COMPLETION",
                    "Active attempt already satisfies all completion rules",
                )
            }
        }

        return SnapshotValidationReport(issues)
    }

    private fun validateIdentity(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        issue: (String, String) -> Unit,
    ) {
        if (snapshot.levelId != level.id) {
            issue("LEVEL_ID", "Snapshot level ID does not match loaded content")
        }
        if (snapshot.levelVersion != level.levelVersion) {
            issue("LEVEL_VERSION", "Snapshot level version does not match loaded content")
        }
        if (snapshot.ruleVersion != level.ruleVersion) {
            issue("RULE_VERSION", "Snapshot rule version does not match loaded content")
        }
        if (snapshot.attempt.attemptChainId != snapshot.chain.id) {
            issue("ATTEMPT_CHAIN", "Attempt and chain IDs do not match")
        }
    }

    private fun validateBoard(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        issue: (String, String) -> Unit,
    ) {
        val expectedVehicleIds = level.vehicleById.keys
        val actualVehicleIds = snapshot.board.vehicles.keys
        val missingVehicleIds = expectedVehicleIds - actualVehicleIds
        val extraVehicleIds = actualVehicleIds - expectedVehicleIds
        if (missingVehicleIds.isNotEmpty()) {
            issue(
                "BOARD_VEHICLES_MISSING",
                "Snapshot is missing vehicles: ${missingVehicleIds.sortedVehicleValues()}",
            )
        }
        if (extraVehicleIds.isNotEmpty()) {
            issue(
                "BOARD_VEHICLES_EXTRA",
                "Snapshot contains unknown vehicles: ${extraVehicleIds.sortedVehicleValues()}",
            )
        }

        val knownGateIds = level.gateById.keys
        val extraGateIds = snapshot.board.openGateIds - knownGateIds
        if (extraGateIds.isNotEmpty()) {
            issue(
                "OPEN_GATES_EXTRA",
                "Snapshot contains unknown open gates: ${extraGateIds.map { it.value }.sorted()}",
            )
        }

        val knownExitIds = level.exits.mapTo(linkedSetOf()) { it.id }
        val committedSequences = mutableListOf<Long>()
        snapshot.board.vehicles.forEach { (vehicleId, state) ->
            when (state) {
                is VehicleRuleState.ExitCommitted -> {
                    if (state.exitId !in knownExitIds) {
                        issue(
                            "EXIT_ID_UNKNOWN",
                            "Vehicle ${vehicleId.value} references unknown exit ${state.exitId.value}",
                        )
                    }
                    if (state.commitSequence <= 0L) {
                        issue(
                            "COMMIT_SEQUENCE",
                            "Vehicle ${vehicleId.value} has a non-positive commit sequence",
                        )
                    }
                    committedSequences += state.commitSequence
                }
                is VehicleRuleState.Locked -> {
                    val authoredKey = level.vehicleById[vehicleId]?.lockedBy
                    if (authoredKey != state.keyVehicleId) {
                        issue(
                            "LOCK_KEY_MISMATCH",
                            "Vehicle ${vehicleId.value} lock key differs from level content",
                        )
                    }
                }
                VehicleRuleState.Towed -> {
                    if (level.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
                        issue("V2_TOWED_VEHICLE", "V2 snapshot contains a disabled tow result")
                    }
                }
                VehicleRuleState.Parked -> Unit
            }
        }
        if (committedSequences.distinct().size != committedSequences.size) {
            issue("COMMIT_SEQUENCE_DUPLICATE", "Exit commit sequences must be unique")
        }
        val maxCommitSequence = committedSequences.maxOrNull() ?: 0L
        if (snapshot.board.nextCommitSequence <= maxCommitSequence) {
            issue(
                "NEXT_COMMIT_SEQUENCE",
                "nextCommitSequence must be greater than every committed vehicle sequence",
            )
        }
    }

    private fun validateParkingLot(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        issue: (String, String) -> Unit,
    ) {
        val rules = level.parkingRules
        val parking = snapshot.parkingLot
        if (parking.slots.size != rules.capacity) {
            issue(
                "PARKING_CAPACITY",
                "Snapshot has ${parking.slots.size} slots but level requires ${rules.capacity}",
            )
        }

        val expectedOrderIds = rules.orders.mapTo(linkedSetOf()) { it.id }
        val actualOrderIds = parking.fulfilledVehicleIdsByOrder.keys
        val missingOrderIds = expectedOrderIds - actualOrderIds
        val extraOrderIds = actualOrderIds - expectedOrderIds
        if (missingOrderIds.isNotEmpty()) {
            issue(
                "PARKING_ORDERS_MISSING",
                "Snapshot is missing orders: ${missingOrderIds.sortedOrderValues()}",
            )
        }
        if (extraOrderIds.isNotEmpty()) {
            issue(
                "PARKING_ORDERS_EXTRA",
                "Snapshot contains unknown orders: ${extraOrderIds.sortedOrderValues()}",
            )
        }

        val waitingIds = mutableListOf<VehicleId>()
        val arrivalSequences = mutableListOf<Long>()
        parking.slots.forEachIndexed { slotIndex, waiting ->
            if (waiting == null) return@forEachIndexed
            waitingIds += waiting.vehicleId
            arrivalSequences += waiting.arrivalSequence
            if (waiting.arrivalSequence <= 0L || waiting.arrivalSequence >= parking.nextArrivalSequence) {
                issue(
                    "ARRIVAL_SEQUENCE",
                    "Waiting vehicle ${waiting.vehicleId.value} has invalid sequence at slot $slotIndex",
                )
            }
        }
        if (waitingIds.distinct().size != waitingIds.size) {
            issue("WAITING_VEHICLE_DUPLICATE", "A vehicle occupies multiple waiting slots")
        }
        if (arrivalSequences.distinct().size != arrivalSequences.size) {
            issue("ARRIVAL_SEQUENCE_DUPLICATE", "Waiting arrival sequences must be unique")
        }
        val maxArrivalSequence = arrivalSequences.maxOrNull() ?: 0L
        if (parking.nextArrivalSequence <= maxArrivalSequence || parking.nextArrivalSequence <= 0L) {
            issue(
                "NEXT_ARRIVAL_SEQUENCE",
                "nextArrivalSequence must be positive and greater than all waiting sequences",
            )
        }

        val fulfilledIds = mutableListOf<VehicleId>()
        var earlierOrderIncomplete = false
        rules.orders.forEach { order ->
            val ids = parking.fulfilledVehicleIdsByOrder[order.id].orEmpty()
            fulfilledIds += ids
            if (ids.size > order.requiredCount) {
                issue(
                    "ORDER_OVERFULFILLED",
                    "Order ${order.id.value} has ${ids.size}/${order.requiredCount} vehicles",
                )
            }
            if (earlierOrderIncomplete && ids.isNotEmpty()) {
                issue(
                    "ORDER_OUT_OF_SEQUENCE",
                    "Order ${order.id.value} has progress before an earlier order is complete",
                )
            }
            ids.forEach { vehicleId ->
                val vehicle = level.vehicleById[vehicleId]
                when {
                    vehicle == null -> issue(
                        "ORDER_VEHICLE_UNKNOWN",
                        "Order ${order.id.value} references unknown vehicle ${vehicleId.value}",
                    )
                    vehicle.color != order.color -> issue(
                        "ORDER_COLOR_MISMATCH",
                        "Vehicle ${vehicleId.value} does not match ${order.color} order ${order.id.value}",
                    )
                }
            }
            if (ids.size < order.requiredCount) earlierOrderIncomplete = true
        }
        if (fulfilledIds.distinct().size != fulfilledIds.size) {
            issue("FULFILLED_VEHICLE_DUPLICATE", "A vehicle fulfills multiple parking orders")
        }

        val routedIds = waitingIds + fulfilledIds
        if (routedIds.distinct().size != routedIds.size) {
            issue(
                "PARKING_DESTINATION_DUPLICATE",
                "A vehicle occurs in both a waiting slot and an order",
            )
        }
        routedIds.forEach { vehicleId ->
            when {
                vehicleId !in level.vehicleById -> issue(
                    "PARKING_VEHICLE_UNKNOWN",
                    "Parking state references unknown vehicle ${vehicleId.value}",
                )
                snapshot.board.vehicles[vehicleId] !is VehicleRuleState.ExitCommitted -> issue(
                    "PARKING_VEHICLE_NOT_EXITED",
                    "Parking vehicle ${vehicleId.value} is not exit-committed on the board",
                )
            }
        }

        val allOrdersComplete = rules.orders.all { order ->
            parking.fulfilledVehicleIdsByOrder[order.id].orEmpty().size == order.requiredCount
        }
        if (!allOrdersComplete) {
            val untrackedExitedIds = snapshot.board.vehicles
                .filterValues { state -> state is VehicleRuleState.ExitCommitted }
                .keys - routedIds.toSet()
            if (untrackedExitedIds.isNotEmpty()) {
                issue(
                    "EXITED_VEHICLE_UNROUTED",
                    "Exited vehicles lack a parking destination: ${untrackedExitedIds.sortedVehicleValues()}",
                )
            }

            val activeOrder = rules.orders.firstOrNull { order ->
                parking.fulfilledVehicleIdsByOrder[order.id].orEmpty().size < order.requiredCount
            }
            if (activeOrder != null) {
                val dispatchableWaitingIds = waitingIds.filter { vehicleId ->
                    level.vehicleById[vehicleId]?.color == activeOrder.color
                }
                if (dispatchableWaitingIds.isNotEmpty()) {
                    issue(
                        "WAITING_CURRENT_COLOR",
                        "Current order has dispatchable waiting vehicles: ${dispatchableWaitingIds.map(VehicleId::value).sorted()}",
                    )
                }
            }
        }
    }

    private fun Set<VehicleId>.sortedVehicleValues(): List<String> = map(VehicleId::value).sorted()

    private fun Set<OrderId>.sortedOrderValues(): List<String> = map(OrderId::value).sorted()

    private const val PARKING_ORDER_RULE_VERSION = 2

    private val COMPLETION_BLOCKING_ISSUES = setOf(
        "LEVEL_ID",
        "LEVEL_VERSION",
        "RULE_VERSION",
        "BOARD_VEHICLES_MISSING",
        "BOARD_VEHICLES_EXTRA",
        "PARKING_CAPACITY",
        "PARKING_ORDERS_MISSING",
        "PARKING_ORDERS_EXTRA",
    )
}
