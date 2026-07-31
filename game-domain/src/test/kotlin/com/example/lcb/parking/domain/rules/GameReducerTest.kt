package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
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
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingOverflowPolicy
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReducerTest {
    @Test
    fun `legal exit is committed before animation and completes immediately`() {
        val level = singleVehicleLevel()
        val initial = initial(level)

        val decision = applied(
            GameReducer.reduce(level, initial, GameCommand.TapVehicle(A, EFFECT_1)),
        )

        assertTrue(decision.snapshot.board.vehicles[A] is VehicleRuleState.ExitCommitted)
        assertEquals(AttemptBusinessState.COMPLETE, decision.snapshot.attempt.businessState)
        assertEquals(3, (decision.presentationIntents.single() as PresentationIntent.ExitCommitted).completedStars)
        assertTrue(decision.facts.any { it is DomainFact.AttemptEnded })
    }

    @Test
    fun `intersecting visual paths never become rule blockers`() {
        val a = vehicle(A, Cell(0, 2), Direction.EAST)
        val b = vehicle(B, Cell(3, 0), Direction.SOUTH)
        val level = level(
            vehicles = listOf(a, b),
            exits = listOf(
                ExitDefinition(ExitId("east"), Cell(4, 2), Direction.EAST),
                ExitDefinition(ExitId("south"), Cell(3, 5), Direction.SOUTH),
            ),
            required = setOf(A, B),
        )
        val first = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)))
        val second = applied(GameReducer.reduce(level, first.snapshot, GameCommand.TapVehicle(B, EFFECT_2)))

        assertTrue(first.snapshot.board.vehicles[A] is VehicleRuleState.ExitCommitted)
        assertTrue(second.snapshot.board.vehicles[B] is VehicleRuleState.ExitCommitted)
        assertEquals(AttemptBusinessState.COMPLETE, second.snapshot.attempt.businessState)
    }

    @Test
    fun `five fast taps on blocked vehicle count one collision until presentation ack`() {
        val level = blockedLevel()
        val first = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)))

        repeat(4) {
            val duplicate = GameReducer.reduce(level, first.snapshot, GameCommand.TapVehicle(A, EffectId("dup-$it")))
            assertEquals(RuleRejection.VehicleBusy(A), (duplicate as RuleDecision.Rejected).reason)
        }
        assertEquals(1, first.snapshot.chain.collisionCount)
        assertEquals(2, (first.snapshot.safety as SafetyState.Limited).remaining)

        val ack = applied(
            GameReducer.reduce(
                level,
                first.snapshot,
                GameCommand.ConfirmCollisionPresentation(A, EFFECT_1),
            ),
        )
        assertFalse(ack.requiresPersistence)
        assertTrue(ack.snapshot.transientLockedVehicleIds.isEmpty())
        assertEquals(first.snapshot.chain.collisionCount, ack.snapshot.chain.collisionCount)
    }

    @Test
    fun `stable persistence removes transient lock but keeps collision deduction`() {
        val level = blockedLevel()
        val collided = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1))).snapshot

        val stable = collided.stableForPersistence()

        assertTrue(stable.transientLockedVehicleIds.isEmpty())
        assertEquals(1, stable.chain.collisionCount)
        assertEquals(2, (stable.safety as SafetyState.Limited).remaining)
        assertEquals(VehicleRuleState.Parked, stable.board.vehicles[A])
    }

    @Test
    fun `fatal collision atomically persists safety zero failure and terminal fact`() {
        val level = blockedLevel(initialSafety = InitialSafety.Limited(1))

        val failed = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)))

        assertEquals(0, (failed.snapshot.safety as SafetyState.Limited).remaining)
        assertEquals(AttemptBusinessState.FAIL, failed.snapshot.attempt.businessState)
        assertTrue((failed.presentationIntents.single() as PresentationIntent.Collision).fatal)
        assertEquals(1, failed.facts.filterIsInstance<DomainFact.AttemptEnded>().size)
        val afterTerminalTap = GameReducer.reduce(level, failed.snapshot, GameCommand.TapVehicle(B, EFFECT_2))
        assertEquals(RuleRejection.AttemptNotActive, (afterTerminalTap as RuleDecision.Rejected).reason)
    }

    @Test
    fun `L1 to L5 blocked taps are tutorial mistakes without safety or star collision`() {
        val level = blockedLevel(
            displayNumber = 5,
            initialSafety = InitialSafety.TutorialUnlimited,
            mode = LevelMode.TUTORIAL,
        )

        val result = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)))

        assertEquals(1, result.snapshot.chain.tutorialMistakeCount)
        assertEquals(0, result.snapshot.chain.collisionCount)
        assertEquals(SafetyState.TutorialUnlimited, result.snapshot.safety)
        assertTrue(result.facts.single() is DomainFact.TutorialMistakeRecorded)
    }

    @Test
    fun `shield restores exactly one up to initial and only once per chain`() {
        val level = blockedLevel()
        val collision = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)))
        val stableRuntime = applied(
            GameReducer.reduce(
                level,
                collision.snapshot,
                GameCommand.ConfirmCollisionPresentation(A, EFFECT_1),
            ),
        )

        val shield = applied(GameReducer.reduce(level, stableRuntime.snapshot, GameCommand.UseShield(EFFECT_2)))

        assertEquals(3, (shield.snapshot.safety as SafetyState.Limited).remaining)
        assertTrue(shield.snapshot.chain.shieldUsed)
        assertEquals(
            RuleRejection.ShieldAlreadyUsed,
            (GameReducer.reduce(level, shield.snapshot, GameCommand.UseShield(EFFECT_3)) as RuleDecision.Rejected).reason,
        )
    }

    @Test
    fun `tow removes eligible required car and caps completion at two stars`() {
        val level = singleVehicleLevel()

        val result = applied(GameReducer.reduce(level, initial(level), GameCommand.TowVehicle(A, EFFECT_1)))

        assertEquals(VehicleRuleState.Towed, result.snapshot.board.vehicles[A])
        assertEquals(AttemptBusinessState.COMPLETE, result.snapshot.attempt.businessState)
        assertEquals(2, (result.presentationIntents.single() as PresentationIntent.VehicleTowed).completedStars)
        assertTrue(result.snapshot.chain.towUsed)
    }

    @Test
    fun `rescue target cannot be towed`() {
        val rescue = vehicle(A, Cell(1, 2), Direction.EAST, type = VehicleType.RESCUE)
        val level = level(
            vehicles = listOf(rescue),
            exits = listOf(ExitDefinition(ExitId("east"), Cell(4, 2), Direction.EAST)),
            objective = LevelObjective.RescueTarget(A),
        )

        val result = GameReducer.reduce(level, initial(level), GameCommand.TowVehicle(A, EFFECT_1))

        assertEquals(RuleRejection.TowProhibited(A), (result as RuleDecision.Rejected).reason)
    }

    @Test
    fun `reward continue creates child attempt in same chain with exactly one safety`() {
        val level = blockedLevel(initialSafety = InitialSafety.Limited(1))
        val failed = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1))).snapshot
        val originalAttempt = failed.attempt.attemptId
        val originalChain = failed.attempt.attemptChainId

        val continued = applied(
            GameReducer.reduce(
                level,
                failed,
                GameCommand.ContinueAfterReward(AttemptId("attempt-child"), EFFECT_2),
            ),
        ).snapshot

        assertEquals(AttemptBusinessState.ACTIVE, continued.attempt.businessState)
        assertEquals(originalAttempt, continued.attempt.parentAttemptId)
        assertEquals(originalChain, continued.attempt.attemptChainId)
        assertEquals(1, (continued.safety as SafetyState.Limited).remaining)
        assertEquals(1, continued.chain.collisionCount)
        assertTrue(continued.chain.continueUsed)
    }

    @Test
    fun `restart injects new IDs and resets board chain and tools`() {
        val level = blockedLevel()
        val collision = applied(GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1))).snapshot

        val restarted = applied(
            GameReducer.reduce(
                level,
                collision,
                GameCommand.Restart(AttemptId("attempt-new"), AttemptChainId("chain-new"), EFFECT_2),
            ),
        ).snapshot

        assertEquals(AttemptId("attempt-new"), restarted.attempt.attemptId)
        assertEquals(AttemptChainId("chain-new"), restarted.attempt.attemptChainId)
        assertEquals(0, restarted.chain.collisionCount)
        assertFalse(restarted.chain.shieldUsed)
        assertFalse(restarted.chain.towUsed)
        assertEquals(VehicleRuleState.Parked, restarted.board.vehicles[A])
        assertNotEquals(collision.revision, restarted.revision)
    }

    @Test
    fun `pause rejects board input without changing state`() {
        val level = singleVehicleLevel()
        val paused = applied(GameReducer.reduce(level, initial(level), GameCommand.SetPaused(true))).snapshot

        val result = GameReducer.reduce(level, paused, GameCommand.TapVehicle(A, EFFECT_1))

        assertEquals(RuleRejection.Paused, (result as RuleDecision.Rejected).reason)
        assertEquals(VehicleRuleState.Parked, paused.board.vehicles[A])
    }

    @Test
    fun `paused active attempt can quit from pause page`() {
        val level = singleVehicleLevel()
        val paused = applied(GameReducer.reduce(level, initial(level), GameCommand.SetPaused(true))).snapshot

        val quit = applied(GameReducer.reduce(level, paused, GameCommand.Quit(EFFECT_1)))

        assertEquals(AttemptBusinessState.QUIT, quit.snapshot.attempt.businessState)
        assertTrue(quit.facts.single() is DomainFact.AttemptEnded)
    }

    @Test
    fun `terminal presentation confirmation changes only presentation and is idempotent`() {
        val level = singleVehicleLevel()
        val completed = applied(
            GameReducer.reduce(level, initial(level), GameCommand.TapVehicle(A, EFFECT_1)),
        ).snapshot

        val confirmed = applied(
            GameReducer.reduce(level, completed, GameCommand.ConfirmTerminalPresentation(EFFECT_2)),
        )

        assertEquals(AttemptBusinessState.COMPLETE, confirmed.snapshot.attempt.businessState)
        assertEquals(AttemptPresentationState.PRESENTED, confirmed.snapshot.attempt.presentationState)
        assertTrue(confirmed.requiresPersistence)
        val duplicate = applied(
            GameReducer.reduce(level, confirmed.snapshot, GameCommand.ConfirmTerminalPresentation(EFFECT_2)),
        )
        assertEquals(confirmed.snapshot, duplicate.snapshot)
        assertFalse(duplicate.requiresPersistence)
    }

    @Test
    fun `star formula uses chain collision count and tow cap`() {
        assertEquals(3, GameReducer.starsFor(0, towUsed = false))
        assertEquals(2, GameReducer.starsFor(1, towUsed = false))
        assertEquals(1, GameReducer.starsFor(2, towUsed = false))
        assertEquals(2, GameReducer.starsFor(0, towUsed = true))
        assertEquals(1, GameReducer.starsFor(3, towUsed = true))
    }

    private fun singleVehicleLevel(): LevelDefinition = level(
        vehicles = listOf(vehicle(A, Cell(1, 2), Direction.EAST)),
        exits = listOf(ExitDefinition(ExitId("east"), Cell(4, 2), Direction.EAST)),
        required = setOf(A),
    )

    private fun blockedLevel(
        initialSafety: InitialSafety = InitialSafety.Limited(3),
        displayNumber: Int = 6,
        mode: LevelMode = LevelMode.NORMAL,
    ): LevelDefinition {
        val blocked = vehicle(A, Cell(0, 2), Direction.EAST)
        val blocker = vehicle(B, Cell(3, 2), Direction.NORTH, required = false)
        return level(
            vehicles = listOf(blocked, blocker),
            exits = listOf(
                ExitDefinition(ExitId("east"), Cell(4, 2), Direction.EAST),
                ExitDefinition(ExitId("north"), Cell(3, 0), Direction.NORTH),
            ),
            required = setOf(A),
            initialSafety = initialSafety,
            displayNumber = displayNumber,
            mode = mode,
        )
    }

    private fun level(
        vehicles: List<VehicleDefinition>,
        exits: List<ExitDefinition>,
        required: Set<VehicleId> = vehicles.filter(VehicleDefinition::required).mapTo(mutableSetOf(), VehicleDefinition::id),
        objective: LevelObjective = LevelObjective.ClearAll(required),
        initialSafety: InitialSafety = InitialSafety.Limited(3),
        displayNumber: Int = 6,
        mode: LevelMode = LevelMode.NORMAL,
        ruleVersion: Int = 1,
        parkingCapacity: Int = 3,
        overflowPolicy: ParkingOverflowPolicy = ParkingOverflowPolicy.REJECT_EXIT,
        orders: List<ColorOrderDefinition> = listOf(
            ColorOrderDefinition(OrderId("red-order"), VehicleColor.RED, required.size.coerceAtLeast(1)),
        ),
    ): LevelDefinition = LevelDefinition(
        id = LevelId("test-level"),
        levelVersion = 1,
        ruleVersion = ruleVersion,
        chapterId = "test",
        displayNumber = displayNumber,
        mode = mode,
        difficultyTier = DifficultyTier.D1,
        board = BoardDefinition(5, 6),
        vehicles = vehicles,
        exits = exits,
        parkingRules = ParkingRules(parkingCapacity, orders, overflowPolicy),
        objective = objective,
        initialSafety = initialSafety,
        canonicalSolution = required.map(CanonicalAction::ExitVehicle),
    )

    private fun vehicle(
        id: VehicleId,
        anchor: Cell,
        direction: Direction,
        type: VehicleType = VehicleType.CAR,
        color: VehicleColor = VehicleColor.RED,
        required: Boolean = true,
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        type = type,
        color = color,
        anchor = anchor,
        direction = direction,
        length = 2,
        required = required,
    )

    private fun initial(level: LevelDefinition): GameSnapshot =
        GameSnapshot.initial(level, AttemptId("attempt-root"), AttemptChainId("chain-root"))

    private fun applied(decision: RuleDecision): RuleDecision.Applied {
        assertTrue("Expected Applied but was $decision", decision is RuleDecision.Applied)
        return decision as RuleDecision.Applied
    }

    private companion object {
        val A = VehicleId("A")
        val B = VehicleId("B")
        val EFFECT_1 = EffectId("effect-1")
        val EFFECT_2 = EffectId("effect-2")
        val EFFECT_3 = EffectId("effect-3")
    }
}
