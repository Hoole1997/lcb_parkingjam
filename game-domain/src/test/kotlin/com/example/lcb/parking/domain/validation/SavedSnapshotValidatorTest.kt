package com.example.lcb.parking.domain.validation

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
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType
import com.example.lcb.parking.domain.rules.GameCommand
import com.example.lcb.parking.domain.rules.GameReducer
import com.example.lcb.parking.domain.rules.RuleDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSnapshotValidatorTest {

    @Test
    fun `initial and reducer-produced snapshots are valid`() {
        val level = level()
        val initial = initial(level)
        val waitingBlue = tap(level, initial, BLUE_ID, "blue-waits")
        val completed = tap(level, waitingBlue, RED_ID, "red-dispatches-blue")

        assertTrue(SavedSnapshotValidator.validate(level, initial).isValid)
        assertTrue(SavedSnapshotValidator.validate(level, waitingBlue).isValid)
        assertTrue(SavedSnapshotValidator.validate(level, completed).isValid)
    }

    @Test
    fun `restore rejects missing board vehicles and wrong parking capacity`() {
        val level = level()
        val initial = initial(level)
        val corrupt = initial.copy(
            board = initial.board.copy(vehicles = initial.board.vehicles - BLUE_ID),
            parkingLot = initial.parkingLot.copy(slots = emptyList()),
        )

        val codes = SavedSnapshotValidator.validate(level, corrupt).issues.map { it.code }.toSet()

        assertTrue("BOARD_VEHICLES_MISSING" in codes)
        assertTrue("PARKING_CAPACITY" in codes)
    }

    @Test
    fun `restore rejects out-of-order fulfillment with the wrong vehicle color`() {
        val level = level()
        val redDelivered = tap(level, initial(level), RED_ID, "red")
        val corrupt = redDelivered.copy(
            parkingLot = redDelivered.parkingLot.copy(
                fulfilledVehicleIdsByOrder = mapOf(
                    RED_ORDER_ID to emptyList(),
                    BLUE_ORDER_ID to listOf(RED_ID),
                ),
            ),
        )

        val codes = SavedSnapshotValidator.validate(level, corrupt).issues.map { it.code }.toSet()

        assertTrue("ORDER_COLOR_MISMATCH" in codes)
        assertTrue("ORDER_OUT_OF_SEQUENCE" in codes)
    }

    @Test
    fun `restore rejects non-monotonic arrival sequence and active completed state`() {
        val level = level()
        val waitingBlue = tap(level, initial(level), BLUE_ID, "blue-waits")
        val invalidSequence = waitingBlue.copy(
            parkingLot = waitingBlue.parkingLot.copy(nextArrivalSequence = 1L),
        )
        assertFalse(SavedSnapshotValidator.validate(level, invalidSequence).isValid)

        val completed = tap(level, waitingBlue, RED_ID, "red-dispatches-blue")
        val activeCompleted = completed.copy(
            attempt = completed.attempt.copy(businessState = AttemptBusinessState.ACTIVE),
        )
        val codes = SavedSnapshotValidator.validate(level, activeCompleted).issues.map { it.code }
        assertTrue("ACTIVE_AFTER_COMPLETION" in codes)
    }

    private fun level(): LevelDefinition {
        val red = vehicle(RED_ID, row = 0, color = VehicleColor.RED)
        val blue = vehicle(BLUE_ID, row = 1, color = VehicleColor.BLUE)
        return LevelDefinition(
            id = LevelId("snapshot-test"),
            levelVersion = 2,
            ruleVersion = 2,
            chapterId = "test",
            displayNumber = 6,
            mode = LevelMode.NORMAL,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(width = 5, height = 4),
            vehicles = listOf(red, blue),
            exits = listOf(
                ExitDefinition(ExitId("west-red"), Cell(0, 0), Direction.WEST),
                ExitDefinition(ExitId("west-blue"), Cell(0, 1), Direction.WEST),
            ),
            parkingRules = ParkingRules(
                capacity = 2,
                overflowPolicy = ParkingOverflowPolicy.REJECT_EXIT,
                orders = listOf(
                    ColorOrderDefinition(RED_ORDER_ID, VehicleColor.RED, 1),
                    ColorOrderDefinition(BLUE_ORDER_ID, VehicleColor.BLUE, 1),
                ),
            ),
            objective = LevelObjective.ClearAll(setOf(RED_ID, BLUE_ID)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(
                CanonicalAction.ExitVehicle(RED_ID),
                CanonicalAction.ExitVehicle(BLUE_ID),
            ),
        )
    }

    private fun vehicle(
        id: VehicleId,
        row: Int,
        color: VehicleColor,
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        type = VehicleType.CAR,
        color = color,
        anchor = Cell(0, row),
        direction = Direction.WEST,
        length = 2,
    )

    private fun initial(level: LevelDefinition): GameSnapshot = GameSnapshot.initial(
        level = level,
        attemptId = AttemptId("attempt"),
        chainId = AttemptChainId("chain"),
    )

    private fun tap(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        vehicleId: VehicleId,
        effectId: String,
    ): GameSnapshot {
        val decision = GameReducer.reduce(
            level,
            snapshot,
            GameCommand.TapVehicle(vehicleId, EffectId(effectId)),
        )
        assertTrue("Expected Applied but was $decision", decision is RuleDecision.Applied)
        return (decision as RuleDecision.Applied).snapshot
    }

    private companion object {
        val RED_ID = VehicleId("R")
        val BLUE_ID = VehicleId("B")
        val RED_ORDER_ID = OrderId("red-order")
        val BLUE_ORDER_ID = OrderId("blue-order")
    }
}
