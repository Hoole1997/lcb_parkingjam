package com.example.lcb.parking.data.state

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptChainSnapshot
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.AttemptSnapshot
import com.example.lcb.parking.domain.model.BoardSnapshot
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.GateId
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.RewardTransactionId
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.ToolInventory
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.WaitingVehicle
import com.example.lcb.parking.domain.rules.AttemptResult
import com.example.lcb.parking.domain.rules.CollisionKind
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.rules.ToolKind

internal object GameStateMapper {

    fun toDto(snapshot: GameSnapshot): GameSnapshotDto {
        val stableSnapshot = snapshot.stableForPersistence()
        return GameSnapshotDto(
            levelId = stableSnapshot.levelId.value,
            levelVersion = stableSnapshot.levelVersion,
            ruleVersion = stableSnapshot.ruleVersion,
            board = BoardSnapshotDto(
                vehicles = stableSnapshot.board.vehicles.entries
                    .sortedBy { it.key.value }
                    .map { (vehicleId, state) -> state.toDto(vehicleId) },
                openGateIds = stableSnapshot.board.openGateIds.map { it.value }.sorted(),
                nextCommitSequence = stableSnapshot.board.nextCommitSequence,
            ),
            parkingLot = ParkingLotSnapshotDto(
                slots = stableSnapshot.parkingLot.slots.map { waiting ->
                    waiting?.let { WaitingVehicleDto(it.vehicleId.value, it.arrivalSequence) }
                },
                fulfilledOrders = stableSnapshot.parkingLot.fulfilledVehicleIdsByOrder.entries
                    .sortedBy { it.key.value }
                    .map { (orderId, vehicleIds) ->
                        FulfilledOrderDto(
                            orderId = orderId.value,
                            vehicleIds = vehicleIds.map(VehicleId::value),
                        )
                    },
                nextArrivalSequence = stableSnapshot.parkingLot.nextArrivalSequence,
            ),
            attempt = AttemptSnapshotDto(
                attemptId = stableSnapshot.attempt.attemptId.value,
                attemptChainId = stableSnapshot.attempt.attemptChainId.value,
                parentAttemptId = stableSnapshot.attempt.parentAttemptId?.value,
                businessState = stableSnapshot.attempt.businessState.name.lowercase(),
                presentationState = stableSnapshot.attempt.presentationState.name.lowercase(),
            ),
            chain = AttemptChainSnapshotDto(
                id = stableSnapshot.chain.id.value,
                collisionCount = stableSnapshot.chain.collisionCount,
                tutorialMistakeCount = stableSnapshot.chain.tutorialMistakeCount,
                shieldUsed = stableSnapshot.chain.shieldUsed,
                towUsed = stableSnapshot.chain.towUsed,
                continueUsed = stableSnapshot.chain.continueUsed,
            ),
            safety = stableSnapshot.safety.toDto(),
            paused = stableSnapshot.paused,
            revision = stableSnapshot.revision,
        )
    }

    fun toDomain(dto: GameSnapshotDto): GameSnapshot {
        require(dto.revision >= 0L) { "Snapshot revision must be non-negative" }
        require(dto.levelVersion > 0) { "Snapshot levelVersion must be positive" }
        require(dto.ruleVersion > 0) { "Snapshot ruleVersion must be positive" }
        require(dto.board.nextCommitSequence > 0L) { "nextCommitSequence must be positive" }
        require(dto.parkingLot.nextArrivalSequence > 0L) { "nextArrivalSequence must be positive" }
        require(dto.chain.collisionCount >= 0) { "collisionCount must be non-negative" }
        require(dto.chain.tutorialMistakeCount >= 0) { "tutorialMistakeCount must be non-negative" }
        require(dto.attempt.attemptChainId == dto.chain.id) { "Attempt and chain IDs differ" }
        val vehicleStates = dto.board.vehicles.associate { state ->
            VehicleId(state.vehicleId) to state.toDomain()
        }
        require(vehicleStates.size == dto.board.vehicles.size) { "Duplicate vehicle state" }
        val parkingLot = dto.parkingLot.toDomain(vehicleStates)
        return GameSnapshot(
            levelId = LevelId(dto.levelId),
            levelVersion = dto.levelVersion,
            ruleVersion = dto.ruleVersion,
            board = BoardSnapshot(
                vehicles = vehicleStates,
                openGateIds = dto.board.openGateIds.mapTo(linkedSetOf(), ::GateId),
                nextCommitSequence = dto.board.nextCommitSequence,
            ),
            parkingLot = parkingLot,
            attempt = AttemptSnapshot(
                attemptId = AttemptId(dto.attempt.attemptId),
                attemptChainId = AttemptChainId(dto.attempt.attemptChainId),
                parentAttemptId = dto.attempt.parentAttemptId?.let(::AttemptId),
                businessState = enumValue(dto.attempt.businessState, "attempt business state"),
                presentationState = enumValue(dto.attempt.presentationState, "attempt presentation state"),
            ),
            chain = AttemptChainSnapshot(
                id = AttemptChainId(dto.chain.id),
                collisionCount = dto.chain.collisionCount,
                tutorialMistakeCount = dto.chain.tutorialMistakeCount,
                shieldUsed = dto.chain.shieldUsed,
                towUsed = dto.chain.towUsed,
                continueUsed = dto.chain.continueUsed,
            ),
            safety = dto.safety.toDomain(),
            paused = dto.paused,
            revision = dto.revision,
        )
    }

    /** Restores parking state only after checking identity, sequence and board-state invariants. */
    private fun ParkingLotSnapshotDto.toDomain(
        vehicleStates: Map<VehicleId, VehicleRuleState>,
    ): ParkingLotSnapshot {
        require(slots.isNotEmpty()) { "Parking slots must not be empty" }
        require(fulfilledOrders.isNotEmpty()) { "Fulfilled orders must not be empty" }
        val waitingVehicles = slots.map { waiting ->
            waiting?.let {
                require(it.arrivalSequence > 0L) { "Waiting arrivalSequence must be positive" }
                require(it.arrivalSequence < nextArrivalSequence) {
                    "Waiting arrivalSequence must precede nextArrivalSequence"
                }
                WaitingVehicle(VehicleId(it.vehicleId), it.arrivalSequence)
            }
        }
        val waitingIds = waitingVehicles.mapNotNull { it?.vehicleId }
        require(waitingIds.distinct().size == waitingIds.size) { "Duplicate waiting vehicle" }
        val arrivalSequences = waitingVehicles.mapNotNull { it?.arrivalSequence }
        require(arrivalSequences.distinct().size == arrivalSequences.size) {
            "Duplicate waiting arrival sequence"
        }

        val fulfilled = fulfilledOrders.associate { order ->
            OrderId(order.orderId) to order.vehicleIds.map(::VehicleId)
        }
        require(fulfilled.size == fulfilledOrders.size) { "Duplicate fulfilled order" }
        require(fulfilled.values.all { ids -> ids.distinct().size == ids.size }) {
            "Duplicate vehicle within fulfilled order"
        }
        val fulfilledIds = fulfilled.values.flatten()
        val parkedIds = waitingIds + fulfilledIds
        require(parkedIds.distinct().size == parkedIds.size) {
            "Vehicle occurs in multiple parking destinations"
        }
        require(parkedIds.all { id -> vehicleStates[id] is VehicleRuleState.ExitCommitted }) {
            "Parking vehicle must have an exit-committed board state"
        }
        return ParkingLotSnapshot(
            slots = waitingVehicles,
            fulfilledVehicleIdsByOrder = fulfilled,
            nextArrivalSequence = nextArrivalSequence,
        )
    }

    fun toDto(progress: PlayerProgress): PlayerProgressDto = PlayerProgressDto(
        coins = progress.coins,
        bestStarsByLevel = progress.bestStarsByLevel.entries
            .sortedBy { it.key.value }
            .map { LevelIntEntryDto(it.key.value, it.value) },
        rewardedCoinsByLevel = progress.rewardedCoinsByLevel.entries
            .sortedBy { it.key.value }
            .map { LevelIntEntryDto(it.key.value, it.value) },
        completedLevelIds = progress.completedLevelIds.map { it.value }.sorted(),
        appliedRewardTransactionIds = progress.appliedRewardTransactionIds.map { it.value }.sorted(),
        l5StarterRewardClaimed = progress.l5StarterRewardClaimed,
        inventory = ToolInventoryDto(
            hints = progress.inventory.hints,
            shields = progress.inventory.shields,
            tows = progress.inventory.tows,
        ),
        revision = progress.revision,
    )

    fun toDomain(dto: PlayerProgressDto): PlayerProgress {
        require(dto.revision >= 0L) { "Progress revision must be non-negative" }
        require(dto.coins >= 0L) { "Coins must be non-negative" }
        require(dto.inventory.hints >= 0 && dto.inventory.shields >= 0 && dto.inventory.tows >= 0) {
            "Inventory values must be non-negative"
        }
        require(dto.bestStarsByLevel.all { it.value in 1..3 }) { "Best stars must be in 1..3" }
        require(dto.rewardedCoinsByLevel.all { it.value >= 0 }) { "Rewarded coins must be non-negative" }
        val bestStars = dto.bestStarsByLevel.associate { LevelId(it.levelId) to it.value }
        val rewardedCoins = dto.rewardedCoinsByLevel.associate { LevelId(it.levelId) to it.value }
        val completedLevels = dto.completedLevelIds.mapTo(linkedSetOf(), ::LevelId)
        val appliedTransactions = dto.appliedRewardTransactionIds
            .mapTo(linkedSetOf(), ::RewardTransactionId)
        require(bestStars.size == dto.bestStarsByLevel.size) { "Duplicate best-star level" }
        require(rewardedCoins.size == dto.rewardedCoinsByLevel.size) { "Duplicate rewarded-coin level" }
        require(completedLevels.size == dto.completedLevelIds.size) { "Duplicate completed level" }
        require(appliedTransactions.size == dto.appliedRewardTransactionIds.size) {
            "Duplicate reward transaction"
        }
        return PlayerProgress(
            coins = dto.coins,
            bestStarsByLevel = bestStars,
            rewardedCoinsByLevel = rewardedCoins,
            completedLevelIds = completedLevels,
            appliedRewardTransactionIds = appliedTransactions,
            l5StarterRewardClaimed = dto.l5StarterRewardClaimed,
            inventory = ToolInventory(
                hints = dto.inventory.hints,
                shields = dto.inventory.shields,
                tows = dto.inventory.tows,
            ),
            revision = dto.revision,
        )
    }

    fun toDto(fact: DomainFact): DomainFactDto = when (fact) {
        is DomainFact.VehicleExitCommitted -> DomainFactDto(
            type = "vehicle_exit_committed",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            commitSequence = fact.commitSequence,
            openedGateIds = fact.openedGateIds.map { it.value }.sorted(),
        )
        is DomainFact.VehicleQueued -> DomainFactDto(
            type = "vehicle_queued",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            colorId = fact.color.name.lowercase(),
            slotIndex = fact.slotIndex,
            arrivalSequence = fact.arrivalSequence,
        )
        is DomainFact.VehicleOrderFulfilled -> DomainFactDto(
            type = "vehicle_order_fulfilled",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            colorId = fact.color.name.lowercase(),
            orderId = fact.orderId.value,
            fromSlotIndex = fact.fromSlotIndex,
        )
        is DomainFact.ColorOrderCompleted -> DomainFactDto(
            type = "color_order_completed",
            attemptId = fact.attemptId.value,
            orderId = fact.orderId.value,
        )
        is DomainFact.ParkingOverflowRecorded -> DomainFactDto(
            type = "parking_overflow_recorded",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            capacity = fact.capacity,
        )
        is DomainFact.CollisionRecorded -> DomainFactDto(
            type = "collision_recorded",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            collisionKind = fact.collisionKind.name.lowercase(),
            chainCollisionCount = fact.chainCollisionCount,
        )
        is DomainFact.TutorialMistakeRecorded -> DomainFactDto(
            type = "tutorial_mistake_recorded",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId.value,
            tutorialMistakeCount = fact.tutorialMistakeCount,
        )
        is DomainFact.ToolUsed -> DomainFactDto(
            type = "tool_used",
            attemptId = fact.attemptId.value,
            vehicleId = fact.vehicleId?.value,
            tool = fact.tool.name.lowercase(),
        )
        is DomainFact.AttemptEnded -> DomainFactDto(
            type = "attempt_ended",
            attemptId = fact.attemptId.value,
            attemptChainId = fact.attemptChainId.value,
            result = fact.result.name.lowercase(),
            stars = fact.stars,
        )
        is DomainFact.AttemptStarted -> DomainFactDto(
            type = "attempt_started",
            attemptId = fact.attemptId.value,
            attemptChainId = fact.attemptChainId.value,
            parentAttemptId = fact.parentAttemptId?.value,
            continued = fact.continued,
        )
        is DomainFact.PresentationAcknowledged -> DomainFactDto(
            type = "presentation_acknowledged",
            effectId = fact.effectId.value,
        )
    }

    fun toDomain(dto: DomainFactDto): DomainFact = when (dto.type) {
        "vehicle_exit_committed" -> DomainFact.VehicleExitCommitted(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            commitSequence = dto.required(dto.commitSequence, "commitSequence"),
            openedGateIds = dto.openedGateIds.mapTo(linkedSetOf(), ::GateId),
        )
        "vehicle_queued" -> DomainFact.VehicleQueued(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            color = enumValue(dto.required(dto.colorId, "colorId"), "vehicle color"),
            slotIndex = dto.required(dto.slotIndex, "slotIndex").also { index ->
                require(index >= 0) { "vehicle_queued requires non-negative slotIndex" }
            },
            arrivalSequence = dto.required(dto.arrivalSequence, "arrivalSequence").also { sequence ->
                require(sequence > 0L) { "vehicle_queued requires positive arrivalSequence" }
            },
        )
        "vehicle_order_fulfilled" -> DomainFact.VehicleOrderFulfilled(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            orderId = OrderId(dto.required(dto.orderId, "orderId")),
            color = enumValue(dto.required(dto.colorId, "colorId"), "vehicle color"),
            fromSlotIndex = dto.fromSlotIndex?.also { index ->
                require(index >= 0) { "vehicle_order_fulfilled requires non-negative fromSlotIndex" }
            },
        )
        "color_order_completed" -> DomainFact.ColorOrderCompleted(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            orderId = OrderId(dto.required(dto.orderId, "orderId")),
        )
        "parking_overflow_recorded" -> DomainFact.ParkingOverflowRecorded(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            capacity = dto.required(dto.capacity, "capacity").also { value ->
                require(value > 0) { "parking_overflow_recorded requires positive capacity" }
            },
        )
        "collision_recorded" -> DomainFact.CollisionRecorded(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            collisionKind = enumValue(dto.required(dto.collisionKind, "collisionKind"), "collision kind"),
            chainCollisionCount = dto.required(dto.chainCollisionCount, "chainCollisionCount"),
        )
        "tutorial_mistake_recorded" -> DomainFact.TutorialMistakeRecorded(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            vehicleId = VehicleId(dto.required(dto.vehicleId, "vehicleId")),
            tutorialMistakeCount = dto.required(dto.tutorialMistakeCount, "tutorialMistakeCount"),
        )
        "tool_used" -> DomainFact.ToolUsed(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            tool = enumValue(dto.required(dto.tool, "tool"), "tool kind"),
            vehicleId = dto.vehicleId?.let(::VehicleId),
        )
        "attempt_ended" -> DomainFact.AttemptEnded(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            attemptChainId = AttemptChainId(dto.required(dto.attemptChainId, "attemptChainId")),
            result = enumValue(dto.required(dto.result, "result"), "attempt result"),
            stars = dto.stars,
        )
        "attempt_started" -> DomainFact.AttemptStarted(
            attemptId = AttemptId(dto.required(dto.attemptId, "attemptId")),
            attemptChainId = AttemptChainId(dto.required(dto.attemptChainId, "attemptChainId")),
            parentAttemptId = dto.parentAttemptId?.let(::AttemptId),
            continued = dto.required(dto.continued, "continued"),
        )
        "presentation_acknowledged" -> DomainFact.PresentationAcknowledged(
            com.example.lcb.parking.domain.model.EffectId(dto.required(dto.effectId, "effectId")),
        )
        else -> throw IllegalArgumentException("Unknown domain fact: ${dto.type}")
    }

    private fun VehicleRuleState.toDto(vehicleId: VehicleId): VehicleRuleStateDto = when (this) {
        VehicleRuleState.Parked -> VehicleRuleStateDto(vehicleId.value, "parked")
        is VehicleRuleState.Locked -> VehicleRuleStateDto(
            vehicleId = vehicleId.value,
            state = "locked",
            keyVehicleId = keyVehicleId.value,
        )
        is VehicleRuleState.ExitCommitted -> VehicleRuleStateDto(
            vehicleId = vehicleId.value,
            state = "exit_committed",
            exitId = exitId.value,
            commitSequence = commitSequence,
        )
        VehicleRuleState.Towed -> VehicleRuleStateDto(vehicleId.value, "towed")
    }

    private fun VehicleRuleStateDto.toDomain(): VehicleRuleState = when (state) {
        "parked" -> VehicleRuleState.Parked
        "locked" -> VehicleRuleState.Locked(
            VehicleId(requireNotNull(keyVehicleId) { "Locked state requires keyVehicleId" }),
        )
        "exit_committed" -> VehicleRuleState.ExitCommitted(
            exitId = ExitId(requireNotNull(exitId) { "Exit state requires exitId" }),
            commitSequence = requireNotNull(commitSequence) { "Exit state requires commitSequence" },
        )
        "towed" -> VehicleRuleState.Towed
        else -> throw IllegalArgumentException("Unknown vehicle state: $state")
    }

    private fun SafetyState.toDto(): SafetyStateDto = when (this) {
        SafetyState.TutorialUnlimited -> SafetyStateDto(mode = "tutorial_unlimited")
        is SafetyState.Limited -> SafetyStateDto(
            mode = "limited",
            initial = initial,
            remaining = remaining,
        )
    }

    private fun SafetyStateDto.toDomain(): SafetyState = when (mode) {
        "tutorial_unlimited" -> SafetyState.TutorialUnlimited
        "limited" -> {
            val initialPoints = requireNotNull(initial) { "Limited safety requires initial" }
            val remainingPoints = requireNotNull(remaining) { "Limited safety requires remaining" }
            require(initialPoints > 0) { "Limited safety initial must be positive" }
            require(remainingPoints in 0..initialPoints) { "Limited safety remaining is out of range" }
            SafetyState.Limited(initial = initialPoints, remaining = remainingPoints)
        }
        else -> throw IllegalArgumentException("Unknown safety state: $mode")
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T {
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown $label: $value")
    }

    private fun <T : Any> DomainFactDto.required(value: T?, field: String): T {
        return requireNotNull(value) { "$type requires $field" }
    }
}
