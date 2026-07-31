package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
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
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.VehicleType
import com.example.lcb.parking.domain.progression.StarRatingCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingOrderV2Test {
    @Test
    fun `one thousand blocked taps remain active and never mutate persistent state`() {
        val blocked = vehicle(A, row = 0, direction = Direction.EAST, column = 0)
        val blocker = vehicle(B, row = 0, direction = Direction.NORTH, column = 3)
        val level = level(
            vehicles = listOf(blocked, blocker),
            required = setOf(A),
            orders = listOf(order("red-1", VehicleColor.RED, 1)),
        )
        val initial = initial(level)
        var snapshot = initial

        repeat(1_000) { index ->
            val decision = applied(
                GameReducer.reduce(
                    level,
                    snapshot,
                    GameCommand.TapVehicle(A, EffectId("collision-$index")),
                ),
            )
            assertFalse(decision.requiresPersistence)
            assertTrue(decision.facts.isEmpty())
            assertEquals(AttemptBusinessState.ACTIVE, decision.snapshot.attempt.businessState)
            snapshot = decision.snapshot
        }

        assertEquals(initial, snapshot)
        assertEquals(0, snapshot.chain.collisionCount)
        assertEquals(SafetyState.Limited(initial = 3, remaining = 3), snapshot.safety)
    }

    @Test
    fun `wrong colors use lowest free slots then dispatch by arrival order`() {
        val green = vehicle(A, row = 0, color = VehicleColor.MINT)
        val olderBlue = vehicle(B, row = 1, color = VehicleColor.BLUE)
        val red = vehicle(C, row = 2, color = VehicleColor.RED)
        val newerBlue = vehicle(D, row = 3, color = VehicleColor.BLUE)
        val yellow = vehicle(E, row = 4, color = VehicleColor.YELLOW)
        val level = level(
            vehicles = listOf(green, olderBlue, red, newerBlue, yellow),
            required = setOf(A, B, C, D, E),
            capacity = 2,
            orders = listOf(
                order("red-1", VehicleColor.RED, 1),
                order("green-1", VehicleColor.MINT, 1),
                order("yellow-1", VehicleColor.YELLOW, 1),
                order("blue-2", VehicleColor.BLUE, 2),
            ),
        )

        val greenWaiting = applied(tap(level, initial(level), A, "green"))
        val oldBlueWaiting = applied(tap(level, greenWaiting.snapshot, B, "old-blue"))
        val redDelivered = applied(tap(level, oldBlueWaiting.snapshot, C, "red"))
        assertEquals(null, redDelivered.snapshot.parkingLot.slots[0])
        assertEquals(B, redDelivered.snapshot.parkingLot.slots[1]?.vehicleId)

        val newBlueWaiting = applied(tap(level, redDelivered.snapshot, D, "new-blue"))
        assertEquals(D, newBlueWaiting.snapshot.parkingLot.slots[0]?.vehicleId)
        assertEquals(B, newBlueWaiting.snapshot.parkingLot.slots[1]?.vehicleId)
        assertTrue(
            newBlueWaiting.snapshot.parkingLot.slots[1]!!.arrivalSequence <
                newBlueWaiting.snapshot.parkingLot.slots[0]!!.arrivalSequence,
        )

        val completed = applied(tap(level, newBlueWaiting.snapshot, E, "yellow"))
        val effect = completed.presentationIntents.single() as PresentationIntent.ExitCommitted

        assertEquals(ParkingDestination.Order(OrderId("yellow-1")), effect.parkingDestination)
        assertEquals(listOf(B, D), effect.parkingDispatches.map(ParkingDispatch::vehicleId))
        assertTrue(completed.snapshot.parkingLot.slots.all { it == null })
        assertEquals(listOf(B, D), completed.snapshot.parkingLot.fulfilledVehicleIdsByOrder[OrderId("blue-2")])
        assertEquals(AttemptBusinessState.COMPLETE, completed.snapshot.attempt.businessState)
    }

    @Test
    fun `full parking lot rejects wrong color atomically by default`() {
        val blueOne = vehicle(A, row = 0, color = VehicleColor.BLUE)
        val blueTwo = vehicle(B, row = 1, color = VehicleColor.BLUE)
        val red = vehicle(C, row = 2, color = VehicleColor.RED)
        val level = level(
            vehicles = listOf(blueOne, blueTwo, red),
            required = setOf(C),
            capacity = 1,
            orders = listOf(order("red-1", VehicleColor.RED, 1)),
        )
        val parked = applied(tap(level, initial(level), A, "first-blue")).snapshot

        val rejected = tap(level, parked, B, "overflow") as RuleDecision.Rejected

        assertEquals(RuleRejection.ParkingLotFull(B), rejected.reason)
        assertEquals(VehicleRuleState.Parked, parked.board.vehicles[B])
        assertEquals(AttemptBusinessState.ACTIVE, parked.attempt.businessState)
        assertTrue((rejected.presentationIntents.single() as PresentationIntent.ParkingLotFull).fatal.not())
    }

    @Test
    fun `fail overflow policy remains explicit and does not move overflowing vehicle`() {
        val level = level(
            vehicles = listOf(
                vehicle(A, row = 0, color = VehicleColor.BLUE),
                vehicle(B, row = 1, color = VehicleColor.BLUE),
                vehicle(C, row = 2, color = VehicleColor.RED),
            ),
            required = setOf(C),
            capacity = 1,
            overflowPolicy = ParkingOverflowPolicy.FAIL_ATTEMPT,
            orders = listOf(order("red-1", VehicleColor.RED, 1)),
        )
        val parked = applied(tap(level, initial(level), A, "first-blue")).snapshot

        val failed = applied(tap(level, parked, B, "overflow"))

        assertEquals(AttemptBusinessState.FAIL, failed.snapshot.attempt.businessState)
        assertEquals(VehicleRuleState.Parked, failed.snapshot.board.vehicles[B])
        assertTrue((failed.presentationIntents.single() as PresentationIntent.ParkingLotFull).fatal)
        assertTrue(failed.facts.any { it is DomainFact.ParkingOverflowRecorded })
    }

    @Test
    fun `orders and original objective are both required and later objective car bypasses`() {
        val red = vehicle(A, row = 0, color = VehicleColor.RED)
        val blueObjectiveCar = vehicle(B, row = 1, color = VehicleColor.BLUE)
        val level = level(
            vehicles = listOf(red, blueObjectiveCar),
            required = setOf(A, B),
            orders = listOf(order("red-1", VehicleColor.RED, 1)),
        )

        val orderDone = applied(tap(level, initial(level), A, "red"))
        assertEquals(AttemptBusinessState.ACTIVE, orderDone.snapshot.attempt.businessState)

        val objectiveDone = applied(tap(level, orderDone.snapshot, B, "blue-bypass"))
        val effect = objectiveDone.presentationIntents.single() as PresentationIntent.ExitCommitted
        assertEquals(ParkingDestination.Bypass, effect.parkingDestination)
        assertEquals(AttemptBusinessState.COMPLETE, objectiveDone.snapshot.attempt.businessState)
    }

    @Test
    fun `V2 disables tow shield and continue and blocked taps do not reduce stars`() {
        val level = level(
            vehicles = listOf(vehicle(A, row = 0)),
            required = setOf(A),
            orders = listOf(order("red-1", VehicleColor.RED, 1)),
        )
        val snapshot = initial(level)

        assertEquals(
            RuleRejection.TowUnavailable,
            (GameReducer.reduce(level, snapshot, GameCommand.TowVehicle(A, EffectId("tow"))) as RuleDecision.Rejected).reason,
        )
        assertEquals(
            RuleRejection.ShieldUnavailable,
            (GameReducer.reduce(level, snapshot, GameCommand.UseShield(EffectId("shield"))) as RuleDecision.Rejected).reason,
        )
        assertEquals(
            RuleRejection.ContinueUnavailable,
            (
                GameReducer.reduce(
                    level,
                    snapshot,
                    GameCommand.ContinueAfterReward(AttemptId("child"), EffectId("continue")),
                ) as RuleDecision.Rejected
                ).reason,
        )

        val completedWithHistoricalCollisions = snapshot.copy(
            attempt = snapshot.attempt.copy(businessState = AttemptBusinessState.COMPLETE),
            chain = snapshot.chain.copy(collisionCount = 1_000),
        )
        assertEquals(3, StarRatingCalculator.calculate(completedWithHistoricalCollisions))
        assertEquals(2, StarRatingCalculator.calculate(completedWithHistoricalCollisions.copy(
            chain = completedWithHistoricalCollisions.chain.copy(towUsed = true),
        )))
    }

    private fun level(
        vehicles: List<VehicleDefinition>,
        required: Set<VehicleId>,
        orders: List<ColorOrderDefinition>,
        capacity: Int = 3,
        overflowPolicy: ParkingOverflowPolicy = ParkingOverflowPolicy.REJECT_EXIT,
    ): LevelDefinition = LevelDefinition(
        id = LevelId("v2-test"),
        levelVersion = 2,
        ruleVersion = 2,
        chapterId = "test",
        displayNumber = 6,
        mode = LevelMode.NORMAL,
        difficultyTier = DifficultyTier.D1,
        board = BoardDefinition(5, 6),
        vehicles = vehicles,
        exits = vehicles.map { definition ->
            val boundaryCell = when (definition.direction) {
                Direction.NORTH -> Cell(definition.anchor.x, 0)
                Direction.SOUTH -> Cell(definition.anchor.x, 5)
                Direction.WEST -> Cell(0, definition.anchor.y)
                Direction.EAST -> Cell(4, definition.anchor.y)
            }
            ExitDefinition(ExitId("exit-${definition.id.value}"), boundaryCell, definition.direction)
        },
        parkingRules = ParkingRules(capacity, orders, overflowPolicy),
        objective = LevelObjective.ClearAll(required),
        initialSafety = InitialSafety.Limited(3),
        canonicalSolution = required.map(CanonicalAction::ExitVehicle),
    )

    private fun vehicle(
        id: VehicleId,
        row: Int,
        color: VehicleColor = VehicleColor.RED,
        direction: Direction = Direction.WEST,
        column: Int = 0,
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        type = VehicleType.CAR,
        color = color,
        anchor = Cell(column, row),
        direction = direction,
        length = 2,
    )

    private fun order(id: String, color: VehicleColor, count: Int): ColorOrderDefinition =
        ColorOrderDefinition(OrderId(id), color, count)

    private fun initial(level: LevelDefinition): GameSnapshot =
        GameSnapshot.initial(level, AttemptId("attempt"), AttemptChainId("chain"))

    private fun tap(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        vehicleId: VehicleId,
        effectId: String,
    ): RuleDecision = GameReducer.reduce(
        level,
        snapshot,
        GameCommand.TapVehicle(vehicleId, EffectId(effectId)),
    )

    private fun applied(decision: RuleDecision): RuleDecision.Applied {
        assertTrue("Expected Applied but was $decision", decision is RuleDecision.Applied)
        return decision as RuleDecision.Applied
    }

    private companion object {
        val A = VehicleId("A")
        val B = VehicleId("B")
        val C = VehicleId("C")
        val D = VehicleId("D")
        val E = VehicleId("E")
    }
}
