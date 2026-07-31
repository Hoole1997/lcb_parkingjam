package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.AttemptSnapshot
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.ParkingOverflowPolicy
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.VehicleType

/** Deterministic, synchronous game rules. No Android, I/O, clocks, randomness, or animation state. */
object GameReducer {
    fun reduce(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand,
    ): RuleDecision {
        if (!snapshot.matches(level)) return RuleDecision.Rejected(RuleRejection.LevelMismatch)
        return when (command) {
            is GameCommand.TapVehicle -> tapVehicle(level, snapshot, command)
            is GameCommand.SetPaused -> setPaused(snapshot, command)
            is GameCommand.ConfirmCollisionPresentation -> confirmCollision(snapshot, command)
            is GameCommand.ConfirmTerminalPresentation -> confirmTerminal(snapshot, command)
            is GameCommand.UseShield -> useShield(level, snapshot, command)
            is GameCommand.TowVehicle -> towVehicle(level, snapshot, command)
            is GameCommand.ContinueAfterReward -> continueAfterReward(snapshot, command)
            is GameCommand.Restart -> restart(level, snapshot, command)
            is GameCommand.Quit -> quit(snapshot, command)
        }
    }

    fun starsFor(chainCollisionCount: Int, towUsed: Boolean): Int {
        val collisionStars = when (chainCollisionCount) {
            0 -> 3
            1 -> 2
            else -> 1
        }
        return if (towUsed) minOf(2, collisionStars) else collisionStars
    }

    /** V2 blocked taps are feedback only and never reduce completion stars. */
    fun starsFor(ruleVersion: Int, chainCollisionCount: Int, towUsed: Boolean): Int =
        if (ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            if (towUsed) 2 else 3
        } else {
            starsFor(chainCollisionCount, towUsed)
        }

    private fun tapVehicle(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.TapVehicle,
    ): RuleDecision {
        activeRejection(snapshot)?.let { return RuleDecision.Rejected(it) }
        if (command.vehicleId in snapshot.transientLockedVehicleIds) {
            return RuleDecision.Rejected(RuleRejection.VehicleBusy(command.vehicleId))
        }
        val definition = level.vehicleById[command.vehicleId]
            ?: return RuleDecision.Rejected(RuleRejection.VehicleNotFound(command.vehicleId))
        return when (val vehicleState = snapshot.board.vehicles[command.vehicleId]) {
            null -> RuleDecision.Rejected(RuleRejection.VehicleNotFound(command.vehicleId))
            is VehicleRuleState.Locked -> RuleDecision.Rejected(
                reason = RuleRejection.VehicleLocked(command.vehicleId),
                presentationIntents = listOf(
                    PresentationIntent.LockedVehicleFeedback(
                        command.effectId,
                        command.vehicleId,
                        vehicleState.keyVehicleId,
                    ),
                ),
            )
            VehicleRuleState.Parked -> {
                when (val path = BoardPathResolver.resolve(level, snapshot.board, definition)) {
                    is BoardPathResult.Blocked -> commitCollision(level, snapshot, command, path.kind)
                    is BoardPathResult.Open -> commitExit(level, snapshot, command, definition, path)
                }
            }
            is VehicleRuleState.ExitCommitted,
            VehicleRuleState.Towed,
            -> RuleDecision.Rejected(RuleRejection.VehicleAlreadyRemoved(command.vehicleId))
        }
    }

    private fun commitExit(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.TapVehicle,
        vehicle: VehicleDefinition,
        path: BoardPathResult.Open,
    ): RuleDecision {
        val parkingRoute = ParkingOrderRouter.route(
            rules = level.parkingRules,
            current = snapshot.parkingLot,
            arrivingVehicle = vehicle,
            vehicleById = level.vehicleById,
        )
        if (parkingRoute is ParkingRouteResult.Full) {
            return commitParkingOverflow(level, snapshot, command)
        }
        parkingRoute as ParkingRouteResult.Routed

        val sequence = snapshot.board.nextCommitSequence
        // Include the starting footprint so presentation can detect every animation intersection.
        val sweptPath = (vehicle.occupiedCells() + path.cells).distinct()
        val sweptCells = sweptPath.toSet()
        val openedGateIds = level.pressurePlates
            .asSequence()
            .filter { it.triggeringVehicleId == vehicle.id && it.cells.any(sweptCells::contains) }
            .flatMap { it.opensGateIds.asSequence() }
            .toSet()
        val unlockedIds = level.vehicles
            .asSequence()
            .filter { it.lockedBy == vehicle.id }
            .map(VehicleDefinition::id)
            .toSet()

        val vehicleStates = snapshot.board.vehicles.toMutableMap().apply {
            this[vehicle.id] = VehicleRuleState.ExitCommitted(path.exit.id, sequence)
            unlockedIds.forEach { id ->
                if (this[id] is VehicleRuleState.Locked) this[id] = VehicleRuleState.Parked
            }
        }
        val board = snapshot.board.copy(
            vehicles = vehicleStates,
            openGateIds = snapshot.board.openGateIds + openedGateIds,
            nextCommitSequence = sequence + 1,
        )
        val completed = CompletionEvaluator.isSatisfied(level, board, parkingRoute.snapshot)
        val stars = if (completed) {
            starsFor(snapshot.ruleVersion, snapshot.chain.collisionCount, snapshot.chain.towUsed)
        } else {
            null
        }
        val attempt = if (completed) {
            snapshot.attempt.copy(
                businessState = AttemptBusinessState.COMPLETE,
                presentationState = AttemptPresentationState.COMPLETION_PENDING,
            )
        } else {
            snapshot.attempt
        }
        val next = snapshot.copy(
            board = board,
            parkingLot = parkingRoute.snapshot,
            attempt = attempt,
            revision = snapshot.revision + 1,
        )
        val facts = buildList {
            add(DomainFact.VehicleExitCommitted(snapshot.attempt.attemptId, vehicle.id, sequence, openedGateIds))
            when (val destination = parkingRoute.destination) {
                is ParkingDestination.Slot -> {
                    val waiting = parkingRoute.snapshot.slots[destination.slotIndex]
                    if (waiting != null) {
                        add(
                            DomainFact.VehicleQueued(
                                attemptId = snapshot.attempt.attemptId,
                                vehicleId = vehicle.id,
                                color = vehicle.color,
                                slotIndex = destination.slotIndex,
                                arrivalSequence = waiting.arrivalSequence,
                            ),
                        )
                    }
                }
                is ParkingDestination.Order -> Unit
                ParkingDestination.Bypass -> Unit
            }
            parkingRoute.fulfillments.forEach { fulfillment ->
                add(
                    DomainFact.VehicleOrderFulfilled(
                        attemptId = snapshot.attempt.attemptId,
                        vehicleId = fulfillment.vehicleId,
                        orderId = fulfillment.orderId,
                        color = fulfillment.color,
                        fromSlotIndex = fulfillment.fromSlotIndex,
                    ),
                )
            }
            parkingRoute.completedOrderIds.forEach { orderId ->
                add(DomainFact.ColorOrderCompleted(snapshot.attempt.attemptId, orderId))
            }
            if (completed) {
                add(
                    DomainFact.AttemptEnded(
                        snapshot.attempt.attemptId,
                        snapshot.attempt.attemptChainId,
                        AttemptResult.COMPLETE,
                        stars,
                    ),
                )
            }
        }
        return RuleDecision.Applied(
            snapshot = next,
            facts = facts,
            presentationIntents = listOf(
                PresentationIntent.ExitCommitted(
                    effectId = command.effectId,
                    vehicleId = vehicle.id,
                    sweepPath = sweptPath,
                    commitSequence = sequence,
                    openedGateIds = openedGateIds,
                    unlockedVehicleIds = unlockedIds,
                    parkingDestination = parkingRoute.destination,
                    parkingDispatches = parkingRoute.dispatches,
                    completedStars = stars,
                ),
            ),
        )
    }

    private fun commitParkingOverflow(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.TapVehicle,
    ): RuleDecision {
        val intent = PresentationIntent.ParkingLotFull(
            effectId = command.effectId,
            vehicleId = command.vehicleId,
            capacity = level.parkingRules.capacity,
            fatal = level.parkingRules.overflowPolicy == ParkingOverflowPolicy.FAIL_ATTEMPT,
        )
        if (level.parkingRules.overflowPolicy == ParkingOverflowPolicy.REJECT_EXIT) {
            return RuleDecision.Rejected(
                reason = RuleRejection.ParkingLotFull(command.vehicleId),
                presentationIntents = listOf(intent),
            )
        }

        val failedAttempt = snapshot.attempt.copy(
            businessState = AttemptBusinessState.FAIL,
            presentationState = AttemptPresentationState.FAILURE_PENDING,
        )
        return RuleDecision.Applied(
            snapshot = snapshot.copy(
                attempt = failedAttempt,
                revision = snapshot.revision + 1,
            ),
            facts = listOf(
                DomainFact.ParkingOverflowRecorded(
                    attemptId = snapshot.attempt.attemptId,
                    vehicleId = command.vehicleId,
                    capacity = level.parkingRules.capacity,
                ),
                DomainFact.AttemptEnded(
                    attemptId = snapshot.attempt.attemptId,
                    attemptChainId = snapshot.attempt.attemptChainId,
                    result = AttemptResult.FAIL,
                ),
            ),
            presentationIntents = listOf(intent),
        )
    }

    private fun commitCollision(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.TapVehicle,
        kind: CollisionKind,
    ): RuleDecision {
        if (level.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            return RuleDecision.Applied(
                snapshot = snapshot,
                presentationIntents = listOf(
                    PresentationIntent.Collision(
                        effectId = command.effectId,
                        vehicleId = command.vehicleId,
                        kind = kind,
                        tutorialDemo = level.isTutorialCollisionMode(),
                        fatal = false,
                    ),
                ),
                requiresPersistence = false,
            )
        }

        val tutorial = level.isTutorialCollisionMode()
        val nextLocks = snapshot.transientVehicleLocks + (command.vehicleId to command.effectId)
        if (tutorial) {
            val nextChain = snapshot.chain.copy(
                tutorialMistakeCount = snapshot.chain.tutorialMistakeCount + 1,
            )
            return RuleDecision.Applied(
                snapshot = snapshot.copy(
                    chain = nextChain,
                    transientVehicleLocks = nextLocks,
                    revision = snapshot.revision + 1,
                ),
                facts = listOf(
                    DomainFact.TutorialMistakeRecorded(
                        snapshot.attempt.attemptId,
                        command.vehicleId,
                        nextChain.tutorialMistakeCount,
                    ),
                ),
                presentationIntents = listOf(
                    PresentationIntent.Collision(command.effectId, command.vehicleId, kind, true, false),
                ),
            )
        }

        val safety = snapshot.safety as? SafetyState.Limited
            ?: return RuleDecision.Rejected(RuleRejection.ShieldUnavailable)
        val remaining = (safety.remaining - 1).coerceAtLeast(0)
        val nextChain = snapshot.chain.copy(collisionCount = snapshot.chain.collisionCount + 1)
        val fatal = remaining == 0
        val nextAttempt = if (fatal) {
            snapshot.attempt.copy(
                businessState = AttemptBusinessState.FAIL,
                presentationState = AttemptPresentationState.FAILURE_PENDING,
            )
        } else {
            snapshot.attempt
        }
        val next = snapshot.copy(
            attempt = nextAttempt,
            chain = nextChain,
            safety = safety.copy(remaining = remaining),
            transientVehicleLocks = nextLocks,
            revision = snapshot.revision + 1,
        )
        val facts = buildList {
            add(
                DomainFact.CollisionRecorded(
                    snapshot.attempt.attemptId,
                    command.vehicleId,
                    kind,
                    nextChain.collisionCount,
                ),
            )
            if (fatal) {
                add(
                    DomainFact.AttemptEnded(
                        snapshot.attempt.attemptId,
                        snapshot.attempt.attemptChainId,
                        AttemptResult.FAIL,
                    ),
                )
            }
        }
        return RuleDecision.Applied(
            snapshot = next,
            facts = facts,
            presentationIntents = listOf(
                PresentationIntent.Collision(command.effectId, command.vehicleId, kind, false, fatal),
            ),
        )
    }

    private fun setPaused(snapshot: GameSnapshot, command: GameCommand.SetPaused): RuleDecision {
        if (snapshot.attempt.businessState != AttemptBusinessState.ACTIVE) {
            return RuleDecision.Rejected(RuleRejection.AttemptNotActive)
        }
        if (snapshot.paused == command.paused) {
            return RuleDecision.Rejected(RuleRejection.AlreadyInRequestedPauseState)
        }
        return RuleDecision.Applied(
            snapshot.copy(paused = command.paused, revision = snapshot.revision + 1),
        )
    }

    private fun confirmCollision(
        snapshot: GameSnapshot,
        command: GameCommand.ConfirmCollisionPresentation,
    ): RuleDecision {
        if (snapshot.transientVehicleLocks[command.vehicleId] != command.effectId) {
            return RuleDecision.Rejected(RuleRejection.StalePresentationAcknowledgement)
        }
        val next = snapshot.copy(transientVehicleLocks = snapshot.transientVehicleLocks - command.vehicleId)
        // 快速点击多辆阻挡车时会同时存在多个表现锁；只有最后一个回弹确认后才能展示失败页。
        val intents = if (
            snapshot.attempt.businessState == AttemptBusinessState.FAIL &&
            next.transientVehicleLocks.isEmpty()
        ) {
            listOf(PresentationIntent.FailureReady(command.effectId))
        } else {
            emptyList()
        }
        return RuleDecision.Applied(next, presentationIntents = intents, requiresPersistence = false)
    }

    private fun confirmTerminal(
        snapshot: GameSnapshot,
        command: GameCommand.ConfirmTerminalPresentation,
    ): RuleDecision {
        if (snapshot.attempt.businessState !in setOf(AttemptBusinessState.COMPLETE, AttemptBusinessState.FAIL)) {
            return RuleDecision.Rejected(RuleRejection.TerminalPresentationUnavailable)
        }
        if (snapshot.attempt.presentationState == AttemptPresentationState.PRESENTED) {
            // Duplicate callbacks are harmless and must not trigger another write.
            return RuleDecision.Applied(snapshot, requiresPersistence = false)
        }
        if (snapshot.attempt.presentationState !in setOf(
                AttemptPresentationState.COMPLETION_PENDING,
                AttemptPresentationState.FAILURE_PENDING,
            )
        ) {
            return RuleDecision.Rejected(RuleRejection.TerminalPresentationUnavailable)
        }
        return RuleDecision.Applied(
            snapshot = snapshot.copy(
                attempt = snapshot.attempt.copy(presentationState = AttemptPresentationState.PRESENTED),
                revision = snapshot.revision + 1,
            ),
            facts = listOf(DomainFact.PresentationAcknowledged(command.effectId)),
            requiresPersistence = true,
        )
    }

    private fun useShield(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.UseShield,
    ): RuleDecision {
        activeRejection(snapshot)?.let { return RuleDecision.Rejected(it) }
        if (level.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            return RuleDecision.Rejected(RuleRejection.ShieldUnavailable)
        }
        if (snapshot.transientVehicleLocks.isNotEmpty()) {
            return RuleDecision.Rejected(RuleRejection.SessionNotStable)
        }
        if (snapshot.chain.shieldUsed) return RuleDecision.Rejected(RuleRejection.ShieldAlreadyUsed)
        if (level.mode == LevelMode.HARD || level.mode == LevelMode.HARD_PREVIEW) {
            return RuleDecision.Rejected(RuleRejection.ShieldUnavailable)
        }
        val safety = snapshot.safety as? SafetyState.Limited
            ?: return RuleDecision.Rejected(RuleRejection.ShieldUnavailable)
        if (safety.remaining >= safety.initial) return RuleDecision.Rejected(RuleRejection.ShieldAtCapacity)
        val nextSafety = safety.copy(remaining = safety.remaining + 1)
        val next = snapshot.copy(
            safety = nextSafety,
            chain = snapshot.chain.copy(shieldUsed = true),
            revision = snapshot.revision + 1,
        )
        return RuleDecision.Applied(
            snapshot = next,
            facts = listOf(DomainFact.ToolUsed(snapshot.attempt.attemptId, ToolKind.SHIELD)),
            presentationIntents = listOf(PresentationIntent.ShieldApplied(command.effectId, nextSafety.remaining)),
        )
    }

    private fun towVehicle(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.TowVehicle,
    ): RuleDecision {
        activeRejection(snapshot)?.let { return RuleDecision.Rejected(it) }
        if (level.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            return RuleDecision.Rejected(RuleRejection.TowUnavailable)
        }
        if (snapshot.transientVehicleLocks.isNotEmpty()) {
            return RuleDecision.Rejected(RuleRejection.SessionNotStable)
        }
        if (snapshot.chain.towUsed) return RuleDecision.Rejected(RuleRejection.TowAlreadyUsed)
        val vehicle = level.vehicleById[command.vehicleId]
            ?: return RuleDecision.Rejected(RuleRejection.VehicleNotFound(command.vehicleId))
        val state = snapshot.board.vehicles[vehicle.id]
            ?: return RuleDecision.Rejected(RuleRejection.VehicleNotFound(command.vehicleId))
        if (state is VehicleRuleState.Locked) {
            return RuleDecision.Rejected(RuleRejection.TowProhibited(vehicle.id))
        }
        if (state != VehicleRuleState.Parked) {
            return RuleDecision.Rejected(RuleRejection.VehicleAlreadyRemoved(vehicle.id))
        }
        val isTrigger = level.pressurePlates.any { it.triggeringVehicleId == vehicle.id }
        val prohibitedByType = vehicle.type in setOf(VehicleType.RESCUE, VehicleType.KEY_CAR, VehicleType.SPECIAL)
        if (vehicle.towProhibited || prohibitedByType || isTrigger || rescueTarget(level) == vehicle.id) {
            return RuleDecision.Rejected(RuleRejection.TowProhibited(vehicle.id))
        }
        val board = snapshot.board.copy(
            vehicles = snapshot.board.vehicles + (vehicle.id to VehicleRuleState.Towed),
        )
        val nextChain = snapshot.chain.copy(towUsed = true)
        val completed = CompletionEvaluator.isSatisfied(level, board, snapshot.parkingLot)
        val stars = if (completed) {
            starsFor(snapshot.ruleVersion, nextChain.collisionCount, towUsed = true)
        } else {
            null
        }
        val attempt = if (completed) {
            snapshot.attempt.copy(
                businessState = AttemptBusinessState.COMPLETE,
                presentationState = AttemptPresentationState.COMPLETION_PENDING,
            )
        } else snapshot.attempt
        val next = snapshot.copy(
            board = board,
            attempt = attempt,
            chain = nextChain,
            revision = snapshot.revision + 1,
        )
        val facts = buildList {
            add(DomainFact.ToolUsed(snapshot.attempt.attemptId, ToolKind.TOW, vehicle.id))
            if (completed) {
                add(
                    DomainFact.AttemptEnded(
                        snapshot.attempt.attemptId,
                        snapshot.attempt.attemptChainId,
                        AttemptResult.COMPLETE,
                        stars,
                    ),
                )
            }
        }
        return RuleDecision.Applied(
            snapshot = next,
            facts = facts,
            presentationIntents = listOf(PresentationIntent.VehicleTowed(command.effectId, vehicle.id, stars)),
        )
    }

    private fun continueAfterReward(
        snapshot: GameSnapshot,
        command: GameCommand.ContinueAfterReward,
    ): RuleDecision {
        if (snapshot.ruleVersion >= PARKING_ORDER_RULE_VERSION) {
            return RuleDecision.Rejected(RuleRejection.ContinueUnavailable)
        }
        if (snapshot.attempt.businessState != AttemptBusinessState.FAIL || snapshot.chain.continueUsed) {
            return RuleDecision.Rejected(RuleRejection.ContinueUnavailable)
        }
        val failedSafety = snapshot.safety as? SafetyState.Limited
            ?: return RuleDecision.Rejected(RuleRejection.ContinueUnavailable)
        if (failedSafety.remaining != 0) return RuleDecision.Rejected(RuleRejection.ContinueUnavailable)
        val parentId = snapshot.attempt.attemptId
        val nextAttempt = AttemptSnapshot(
            attemptId = command.newAttemptId,
            attemptChainId = snapshot.attempt.attemptChainId,
            parentAttemptId = parentId,
        )
        val next = snapshot.copy(
            attempt = nextAttempt,
            chain = snapshot.chain.copy(continueUsed = true),
            safety = failedSafety.copy(remaining = 1),
            paused = false,
            transientVehicleLocks = emptyMap(),
            revision = snapshot.revision + 1,
        )
        return RuleDecision.Applied(
            snapshot = next,
            facts = listOf(
                DomainFact.ToolUsed(command.newAttemptId, ToolKind.CONTINUE_SHIELD),
                DomainFact.AttemptStarted(
                    command.newAttemptId,
                    snapshot.attempt.attemptChainId,
                    parentId,
                    continued = true,
                ),
            ),
            presentationIntents = listOf(PresentationIntent.AttemptContinued(command.effectId)),
        )
    }

    private fun restart(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand.Restart,
    ): RuleDecision {
        val initial = GameSnapshot.initial(level, command.newAttemptId, command.newAttemptChainId).copy(
            revision = snapshot.revision + 1,
        )
        val facts = buildList {
            if (snapshot.attempt.businessState == AttemptBusinessState.ACTIVE) {
                add(
                    DomainFact.AttemptEnded(
                        snapshot.attempt.attemptId,
                        snapshot.attempt.attemptChainId,
                        AttemptResult.RESTART,
                    ),
                )
            }
            add(
                DomainFact.AttemptStarted(
                    command.newAttemptId,
                    command.newAttemptChainId,
                    parentAttemptId = null,
                    continued = false,
                ),
            )
        }
        return RuleDecision.Applied(
            snapshot = initial,
            facts = facts,
            presentationIntents = listOf(PresentationIntent.AttemptRestarted(command.effectId)),
        )
    }

    private fun quit(snapshot: GameSnapshot, command: GameCommand.Quit): RuleDecision {
        // Quit originates from the pause page, so paused ACTIVE attempts remain quittable.
        if (snapshot.attempt.businessState != AttemptBusinessState.ACTIVE) {
            return RuleDecision.Rejected(RuleRejection.AttemptNotActive)
        }
        val next = snapshot.copy(
            attempt = snapshot.attempt.copy(
                businessState = AttemptBusinessState.QUIT,
                presentationState = AttemptPresentationState.PRESENTED,
            ),
            revision = snapshot.revision + 1,
        )
        return RuleDecision.Applied(
            snapshot = next,
            facts = listOf(
                DomainFact.AttemptEnded(
                    snapshot.attempt.attemptId,
                    snapshot.attempt.attemptChainId,
                    AttemptResult.QUIT,
                ),
            ),
            presentationIntents = listOf(PresentationIntent.AttemptQuit(command.effectId)),
        )
    }

    private fun activeRejection(snapshot: GameSnapshot): RuleRejection? = when {
        snapshot.attempt.businessState != AttemptBusinessState.ACTIVE -> RuleRejection.AttemptNotActive
        snapshot.paused -> RuleRejection.Paused
        else -> null
    }

    private fun rescueTarget(level: LevelDefinition): VehicleId? = when (val objective = level.objective) {
        is LevelObjective.RescueTarget -> objective.targetVehicleId
        is LevelObjective.BossClear -> objective.rescueTargetVehicleId
        is LevelObjective.ClearAll -> null
    }

    private fun GameSnapshot.matches(level: LevelDefinition): Boolean =
        levelId == level.id && levelVersion == level.levelVersion && ruleVersion == level.ruleVersion

    private const val PARKING_ORDER_RULE_VERSION = 2

}
