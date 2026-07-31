package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.DifficultyTier
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.ExitDefinition
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.LevelProgression
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType
import com.example.lcb.parking.domain.rules.PresentationIntent
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.rules.ParkingDestination
import com.example.lcb.parking.domain.rules.ParkingDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainGameSessionAdapterTest {

    private val ids = FixedIdFactory()
    private val reducer = DomainGameReducerAdapter(ids)
    private val projector = DomainGameUiProjector()

    @Test
    fun `pause exit persists quit before home and restart creates playable attempt`() {
        val level = level(listOf(vehicle("car_a", y = 2)))
        val paused = reducer.reduce(
            aggregate(level),
            MainGameCommand.Pause,
        ).aggregate

        val quitDecision = reducer.reduce(paused, MainGameCommand.QuitToHome)

        assertTrue(quitDecision.requiresPersistence)
        assertEquals(
            AttemptBusinessState.QUIT,
            quitDecision.aggregate.projection.snapshot.attempt.businessState,
        )
        assertEquals(GameScreenPhase.QUIT, projector.map(quitDecision.aggregate.projection).phase)

        val restartDecision = reducer.reduce(
            quitDecision.aggregate,
            MainGameCommand.RestartCurrentLevel,
        )

        assertTrue(restartDecision.requiresPersistence)
        assertEquals(
            AttemptBusinessState.ACTIVE,
            restartDecision.aggregate.projection.snapshot.attempt.businessState,
        )
        assertEquals(GameScreenPhase.PLAYING, projector.map(restartDecision.aggregate.projection).phase)
    }

    @Test
    fun `final exit stays moving until animation completes and result is actually presented`() {
        val level = level(listOf(vehicle("car_a", y = 2)))
        val initial = aggregate(level)

        val exitDecision = reducer.reduce(initial, MainGameCommand.TapVehicle("car_a"))
        val afterExit = exitDecision.aggregate

        assertTrue(exitDecision.requiresPersistence)
        assertEquals(setOf(VehicleId("car_a")), afterExit.projection.pendingExitVehicleIds)
        assertEquals(GameScreenPhase.COMPLETING, projector.map(afterExit.projection).phase)
        val intent = exitDecision.presentationIntents.single() as PresentationIntent.ExitCommitted

        val completionDecision = reducer.reduce(
            afterExit,
            MainGameCommand.PresentationCompleted(intent.effectId.value, intent.vehicleId.value),
        )

        assertFalse(completionDecision.requiresPersistence)
        assertTrue(completionDecision.aggregate.projection.pendingExitVehicleIds.isEmpty())
        assertEquals(
            AttemptPresentationState.COMPLETION_PENDING,
            completionDecision.aggregate.projection.snapshot.attempt.presentationState,
        )
        assertEquals(GameScreenPhase.RESULT, projector.map(completionDecision.aggregate.projection).phase)

        val presentedDecision = reducer.reduce(
            completionDecision.aggregate,
            MainGameCommand.TerminalPresented,
        )

        assertTrue(presentedDecision.requiresPersistence)
        assertEquals(
            AttemptPresentationState.PRESENTED,
            presentedDecision.aggregate.projection.snapshot.attempt.presentationState,
        )
    }

    @Test
    fun `earlier same-vehicle effect cannot acknowledge a pending exit`() {
        val level = level(listOf(vehicle("car_a", y = 2)))
        val exitDecision = reducer.reduce(aggregate(level), MainGameCommand.TapVehicle("car_a"))
        val exitIntent = exitDecision.presentationIntents.single() as PresentationIntent.ExitCommitted

        val wrongCompletion = reducer.reduce(
            exitDecision.aggregate,
            MainGameCommand.PresentationCompleted("older_highlight", exitIntent.vehicleId.value),
        )

        assertEquals(
            setOf(exitIntent.vehicleId),
            wrongCompletion.aggregate.projection.pendingExitVehicleIds,
        )
        assertEquals(
            exitIntent.effectId,
            wrongCompletion.aggregate.pendingExitEffectIdsByVehicle[exitIntent.vehicleId],
        )
        assertEquals(GameScreenPhase.COMPLETING, projector.map(wrongCompletion.aggregate.projection).phase)
    }

    @Test
    fun `blocked feedback is presentation only and never creates a vehicle lock`() {
        val level = level(
            listOf(
                vehicle("car_a", y = 3),
                vehicle("car_b", y = 1),
            ),
        )
        val initial = aggregate(level)

        val collisionDecision = reducer.reduce(initial, MainGameCommand.TapVehicle("car_a"))
        val afterCollision = collisionDecision.aggregate
        val intent = collisionDecision.presentationIntents.single() as PresentationIntent.Collision
        assertFalse(collisionDecision.requiresPersistence)
        assertTrue(afterCollision.projection.snapshot.transientLockedVehicleIds.isEmpty())
        assertEquals(GameScreenPhase.PLAYING, projector.map(afterCollision.projection).phase)
        assertTrue(projector.map(afterCollision.projection).acceptsBoardInput)

        val completionDecision = reducer.reduce(
            afterCollision,
            MainGameCommand.PresentationCompleted(intent.effectId.value, intent.vehicleId.value),
        )

        assertFalse(completionDecision.requiresPersistence)
        assertTrue(completionDecision.aggregate.projection.snapshot.transientLockedVehicleIds.isEmpty())
    }

    @Test
    fun `three distinct blocked taps keep the board playable with unlimited collisions`() {
        val level = threeBlockedVehicleLevel()
        var current = aggregate(level)
        val collisionIntents = ArrayList<PresentationIntent.Collision>(3)
        val initialRevision = current.projection.snapshot.revision

        listOf("car_a", "car_b", "car_c").forEach { vehicleId ->
            val collision = reducer.reduce(current, MainGameCommand.TapVehicle(vehicleId))
            current = collision.aggregate
            collisionIntents += collision.presentationIntents.single() as PresentationIntent.Collision
            assertFalse(collision.requiresPersistence)
            assertEquals(initialRevision, current.projection.snapshot.revision)
            assertEquals(AttemptBusinessState.ACTIVE, current.projection.snapshot.attempt.businessState)
            assertEquals(GameScreenPhase.PLAYING, projector.map(current.projection).phase)
            assertTrue(projector.map(current.projection).acceptsBoardInput)
        }

        collisionIntents.forEach { intent ->
            val completion = reducer.reduce(
                current,
                MainGameCommand.PresentationCompleted(
                    effectId = intent.effectId.value,
                    vehicleId = intent.vehicleId.value,
                ),
            )
            current = completion.aggregate
            assertTrue(completion.presentationIntents.isEmpty())
            assertEquals(GameScreenPhase.PLAYING, projector.map(current.projection).phase)
        }

        assertTrue(current.projection.snapshot.transientVehicleLocks.isEmpty())
    }

    @Test
    fun `presentation mapper preserves waiting destination and ordered dispatches`() {
        val carA = vehicle("car_a", y = 2)
        val carB = vehicle("car_b", y = 4)
        val level = level(listOf(carA, carB))
        val intent = PresentationIntent.ExitCommitted(
            effectId = EffectId("parking_motion"),
            vehicleId = carA.id,
            sweepPath = listOf(Cell(2, 1), Cell(2, 0)),
            commitSequence = 1L,
            openedGateIds = emptySet(),
            unlockedVehicleIds = emptySet(),
            parkingDestination = ParkingDestination.Slot(slotIndex = 2),
            parkingDispatches = listOf(
                ParkingDispatch(
                    vehicleId = carB.id,
                    fromSlotIndex = 1,
                    orderId = OrderId("order_blue"),
                ),
            ),
        )

        val effect = DomainPresentationEffectMapper().map(aggregate(level), intent)
            as GamePresentationEffect.MoveVehicle
        val motion = checkNotNull(effect.parkingMotion)

        assertEquals(ParkingMotionDestination.WaitingSlot(2), motion.destination)
        assertEquals(carA.id.value, motion.arrivingVehicle.id)
        assertEquals(VehicleDirection.UP, motion.arrivingVehicle.direction)
        assertEquals(listOf(carB.id.value), motion.dispatches.map { it.vehicleId })
        assertEquals(listOf(1), motion.dispatches.map { it.fromSlotIndex })
        assertEquals(VehicleArtVariant.BLUE, motion.dispatches.single().artVariant)
        assertEquals(carB.length, motion.dispatches.single().lengthCells)
        assertEquals(
            ParkingMotionTiming.WAITING_SLOT_ARRIVAL_MILLIS +
                ParkingMotionTiming.DISPATCH_TO_ORDER_MILLIS,
            motion.presentationDurationMillis,
        )
    }

    @Test
    fun `next level creates missing snapshot and durable attempt start`() {
        val level1 = level(listOf(vehicle("car_a", y = 2)), displayNumber = 1)
        val level2 = level(listOf(vehicle("car_b", y = 2)), displayNumber = 2)
        val firstExit = reducer.reduce(aggregate(level1), MainGameCommand.TapVehicle("car_a"))
        val intent = firstExit.presentationIntents.single() as PresentationIntent.ExitCommitted
        val acknowledgedFirst = reducer.reduce(
            firstExit.aggregate,
            MainGameCommand.PresentationCompleted(intent.effectId.value, intent.vehicleId.value),
        ).aggregate
        val completedFirst = acknowledgedFirst.copy(
            levels = listOf(level1, level2),
            snapshotsByLevel = mapOf(level1.id to acknowledgedFirst.projection.snapshot),
            currentLevelIndex = 0,
        )

        val nextDecision = reducer.reduce(completedFirst, MainGameCommand.NextLevel)

        assertTrue(nextDecision.requiresPersistence)
        assertEquals(1, nextDecision.aggregate.currentLevelIndex)
        assertEquals(level2.id, nextDecision.aggregate.projection.level.id)
        assertEquals(GameScreenPhase.PLAYING, projector.map(nextDecision.aggregate.projection).phase)
        assertFalse(nextDecision.aggregate.projection.hasNextLevel)
        assertTrue(nextDecision.aggregate.pendingFacts.single() is DomainFact.AttemptStarted)
    }

    @Test
    fun `next level skips optional hard preview after level 25`() {
        val level25 = level(listOf(vehicle("car_25", y = 2)), displayNumber = 25)
        val hard26 = level(
            listOf(vehicle("car_26", y = 2)),
            displayNumber = 26,
            mode = LevelMode.HARD_PREVIEW,
        )
        val level27 = level(listOf(vehicle("car_27", y = 2)), displayNumber = 27)
        val completedSnapshot = GameSnapshot.initial(
            level25,
            AttemptId("completed_attempt"),
            AttemptChainId("completed_chain"),
        ).copy(
            attempt = GameSnapshot.initial(
                level25,
                AttemptId("completed_attempt"),
                AttemptChainId("completed_chain"),
            ).attempt.copy(
                businessState = AttemptBusinessState.COMPLETE,
                presentationState = AttemptPresentationState.PRESENTED,
            ),
            revision = 4L,
        )
        val current = DomainGameSessionAggregate(
            projection = DomainGameProjection(level25, completedSnapshot),
            progress = PlayerProgress(completedLevelIds = setOf(level25.id)),
            levels = listOf(level25, hard26, level27),
            snapshotsByLevel = mapOf(level25.id to completedSnapshot),
            currentLevelIndex = 0,
        )

        val decision = reducer.reduce(current, MainGameCommand.NextLevel)

        assertEquals(level27.id, decision.aggregate.projection.level.id)
        assertEquals(2, decision.aggregate.currentLevelIndex)
        assertTrue(decision.requiresPersistence)
    }

    @Test
    fun `level selection rejects locked node and opens hard branch after prerequisite`() {
        val level25 = level(listOf(vehicle("car_25", y = 2)), displayNumber = 25)
        val hard26 = level(
            listOf(vehicle("car_26", y = 2)),
            displayNumber = 26,
            mode = LevelMode.HARD_PREVIEW,
        )
        val base = aggregate(level25).copy(
            levels = listOf(level25, hard26),
            snapshotsByLevel = emptyMap(),
        )

        val rejected = reducer.reduce(base, MainGameCommand.OpenLevel(26))
        assertEquals(level25.id, rejected.aggregate.projection.level.id)
        assertFalse(rejected.requiresPersistence)

        val unlocked = base.copy(
            progress = PlayerProgress(completedLevelIds = setOf(level25.id)),
        )
        val opened = reducer.reduce(unlocked, MainGameCommand.OpenLevel(26))
        assertEquals(hard26.id, opened.aggregate.projection.level.id)
        assertTrue(opened.requiresPersistence)
        assertTrue(opened.aggregate.pendingFacts.single() is DomainFact.AttemptStarted)
    }

    @Test
    fun `presentation completion dedupe window stays bounded`() {
        var current = aggregate(level(listOf(vehicle("car_a", y = 2))))

        repeat(200) { index ->
            current = reducer.reduce(
                current,
                MainGameCommand.PresentationCompleted("detached_effect_$index", "car_a"),
            ).aggregate
        }

        assertEquals(64, current.visuallyCompletedEffectIds.size)
        assertFalse(EffectId("detached_effect_0") in current.visuallyCompletedEffectIds)
        assertTrue(EffectId("detached_effect_199") in current.visuallyCompletedEffectIds)
    }

    private fun aggregate(level: LevelDefinition): DomainGameSessionAggregate {
        return DomainGameSessionAggregate(
            projection = DomainGameProjection(
                level = level,
                snapshot = GameSnapshot.initial(level, AttemptId("attempt"), AttemptChainId("chain")),
                hasNextLevel = false,
            ),
            progress = PlayerProgress(),
        )
    }

    private fun level(
        vehicles: List<VehicleDefinition>,
        displayNumber: Int = 1,
        mode: LevelMode = LevelMode.TUTORIAL,
        initialSafety: InitialSafety = InitialSafety.TutorialUnlimited,
    ): LevelDefinition {
        val requiredIds = vehicles.mapTo(mutableSetOf(), VehicleDefinition::id)
        return LevelDefinition(
            id = LevelId("main_${displayNumber.toString().padStart(3, '0')}"),
            levelVersion = 1,
            ruleVersion = 2,
            chapterId = "chapter_1",
            displayNumber = displayNumber,
            mode = mode,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(width = 5, height = 6),
            vehicles = vehicles,
            exits = listOf(
                ExitDefinition(
                    id = ExitId("exit_top"),
                    boundaryCell = Cell(2, 0),
                    direction = Direction.NORTH,
                ),
            ),
            parkingRules = ParkingRules(
                capacity = 4,
                orders = listOf(
                    ColorOrderDefinition(
                        id = OrderId("order_blue"),
                        color = VehicleColor.BLUE,
                        requiredCount = vehicles.size,
                    ),
                ),
            ),
            objective = LevelObjective.ClearAll(requiredIds),
            initialSafety = initialSafety,
            canonicalSolution = vehicles.reversed().map { CanonicalAction.ExitVehicle(it.id) },
            progression = LevelProgression(
                skippable = mode == LevelMode.HARD_PREVIEW,
            ),
        )
    }

    private fun vehicle(id: String, y: Int): VehicleDefinition {
        return VehicleDefinition(
            id = VehicleId(id),
            type = VehicleType.CAR,
            color = VehicleColor.BLUE,
            anchor = Cell(2, y),
            direction = Direction.NORTH,
            length = 2,
        )
    }

    /** 三辆车均无同方向出口，用于模拟第 8 关连续点击阻挡车辆的失败链路。 */
    private fun threeBlockedVehicleLevel(): LevelDefinition {
        val vehicles = listOf(0, 2, 4).mapIndexed { index, row ->
            VehicleDefinition(
                id = VehicleId("car_${('a'.code + index).toChar()}"),
                type = VehicleType.CAR,
                color = VehicleColor.BLUE,
                anchor = Cell(0, row),
                direction = Direction.EAST,
                length = 2,
            )
        }
        return level(
            vehicles = vehicles,
            displayNumber = 8,
            mode = LevelMode.NORMAL,
            initialSafety = InitialSafety.Limited(3),
        )
    }

    private class FixedIdFactory : DomainSessionIdFactory {
        private var effectSequence = 0

        override fun newEffectId(): EffectId {
            effectSequence++
            return EffectId("effect_$effectSequence")
        }

        override fun newAttemptId(): AttemptId = AttemptId("new_attempt")
        override fun newAttemptChainId(): AttemptChainId = AttemptChainId("new_chain")
    }
}
