package com.example.lcb.parking.feature.game

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
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType
import com.example.lcb.parking.domain.model.WaitingVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainGameUiProjectorTest {

    private val projector = DomainGameUiProjector()

    @Test
    fun `L4 result hides coins while L5 result reveals domain reward`() {
        val level4 = level(displayNumber = 4)
        val level5 = level(displayNumber = 5)

        val state4 = projector.map(projection(level4, earnedCoins = 20))
        val state5 = projector.map(projection(level5, earnedCoins = 120, coinBalance = 220L))

        assertEquals(GameScreenPhase.RESULT, state4.phase)
        assertFalse(state4.result!!.showCoins)
        assertTrue(state5.result!!.showCoins)
        assertEquals(120, state5.result!!.earnedCoins)
        assertEquals(220L, state5.result!!.coinBalance)
        assertEquals(3, state5.result!!.stars)
    }

    @Test
    fun `domain grid direction maps to rectangular canvas vehicle`() {
        val level = level(displayNumber = 1)
        val snapshot = GameSnapshot.initial(level, AttemptId("attempt"), AttemptChainId("chain"))

        val state = projector.map(DomainGameProjection(level = level, snapshot = snapshot))

        val vehicle = state.board.vehicles.single()
        assertEquals(1, vehicle.widthCells)
        assertEquals(2, vehicle.heightCells)
        assertEquals(VehicleDirection.UP, vehicle.direction)
        assertEquals("car_a", vehicle.id)
    }

    @Test
    fun `vehicle render color comes from domain definition instead of list position`() {
        val base = level(displayNumber = 8)
        val redVehicle = base.vehicles.single().copy(color = VehicleColor.RED)
        val redOrder = ColorOrderDefinition(OrderId("red"), VehicleColor.RED, 1)
        val redLevel = base.copy(
            vehicles = listOf(redVehicle),
            parkingRules = ParkingRules(capacity = 3, orders = listOf(redOrder)),
        )

        val state = projector.map(
            DomainGameProjection(
                level = redLevel,
                snapshot = GameSnapshot.initial(redLevel, AttemptId("attempt"), AttemptChainId("chain")),
            ),
        )

        assertEquals(VehicleArtVariant.RED, state.board.vehicles.single().artVariant)
        assertEquals(VehicleArtVariant.RED.argb, state.board.vehicles.single().color)
        assertEquals(VehicleArtVariant.RED, state.parkingLot.currentOrder?.color)
    }

    @Test
    fun `parking lot projection exposes only current order and every stable slot`() {
        val redA = vehicleDefinition("red_a", VehicleColor.RED, x = 0)
        val redB = vehicleDefinition("red_b", VehicleColor.RED, x = 1)
        val blue = vehicleDefinition("blue_wait", VehicleColor.BLUE, x = 2)
        val mint = vehicleDefinition("mint_wait", VehicleColor.MINT, x = 3, length = 3)
        val redOrder = ColorOrderDefinition(OrderId("red"), VehicleColor.RED, 2)
        val blueOrder = ColorOrderDefinition(OrderId("blue"), VehicleColor.BLUE, 1)
        val mintOrder = ColorOrderDefinition(OrderId("mint"), VehicleColor.MINT, 1)
        val base = level(displayNumber = 8)
        val level = base.copy(
            vehicles = listOf(redA, redB, blue, mint),
            parkingRules = ParkingRules(
                capacity = 3,
                orders = listOf(redOrder, blueOrder, mintOrder),
            ),
            objective = LevelObjective.ClearAll(setOf(redA.id, redB.id, blue.id, mint.id)),
        )
        val initial = GameSnapshot.initial(level, AttemptId("attempt"), AttemptChainId("chain"))
        val snapshot = initial.copy(
            parkingLot = ParkingLotSnapshot(
                slots = listOf(
                    WaitingVehicle(blue.id, arrivalSequence = 1L),
                    null,
                    WaitingVehicle(mint.id, arrivalSequence = 2L),
                ),
                fulfilledVehicleIdsByOrder = mapOf(
                    redOrder.id to listOf(redA.id),
                    blueOrder.id to emptyList(),
                    mintOrder.id to emptyList(),
                ),
                nextArrivalSequence = 3L,
            ),
        )

        val parking = projector.map(DomainGameProjection(level, snapshot)).parkingLot

        assertEquals(3, parking.capacity)
        assertEquals(2, parking.occupiedCount)
        assertEquals("red", parking.currentOrder?.id)
        assertEquals(1, parking.currentOrder?.completedCount)
        assertEquals(listOf(VehicleArtVariant.BLUE, null, VehicleArtVariant.MINT), parking.slots.map { it.color })
        assertEquals(listOf(2, null, 3), parking.slots.map { it.lengthCells })
        assertEquals(ParkingVehicleArtLength.LONG, parking.slots[2].parkingArtKey?.length)
        assertEquals(listOf(1L, null, 2L), parking.slots.map { it.arrivalSequence })
    }

    @Test
    fun `failed attempt never projects as playable`() {
        val level = level(displayNumber = 8)
        val initial = GameSnapshot.initial(level, AttemptId("failed_attempt"), AttemptChainId("chain"))
        val failed = initial.copy(
            attempt = initial.attempt.copy(
                businessState = AttemptBusinessState.FAIL,
                presentationState = AttemptPresentationState.FAILURE_PENDING,
            ),
            transientVehicleLocks = mapOf(
                VehicleId("car_a") to EffectId("collision_feedback"),
            ),
        )

        val animatingFailure = projector.map(DomainGameProjection(level = level, snapshot = failed))
        val readyFailure = projector.map(
            DomainGameProjection(level = level, snapshot = failed.copy(transientVehicleLocks = emptyMap())),
        )

        assertEquals(GameScreenPhase.FAILING, animatingFailure.phase)
        assertFalse(animatingFailure.acceptsBoardInput)
        assertEquals(GameScreenPhase.FAILURE, readyFailure.phase)
        assertFalse(readyFailure.acceptsBoardInput)
        assertEquals("failed_attempt", readyFailure.failure?.presentationToken)
    }

    private fun projection(
        level: LevelDefinition,
        earnedCoins: Int,
        coinBalance: Long = 0L,
    ): DomainGameProjection {
        val initial = GameSnapshot.initial(level, AttemptId("attempt"), AttemptChainId("chain"))
        val complete = initial.copy(
            attempt = initial.attempt.copy(
                businessState = AttemptBusinessState.COMPLETE,
                presentationState = AttemptPresentationState.COMPLETION_PENDING,
            ),
        )
        return DomainGameProjection(
            level = level,
            snapshot = complete,
            resultStars = 3,
            earnedCoins = earnedCoins,
            coinBalance = coinBalance,
        )
    }

    private fun level(displayNumber: Int): LevelDefinition {
        val vehicleId = VehicleId("car_a")
        return LevelDefinition(
            id = LevelId("main_${displayNumber.toString().padStart(3, '0')}"),
            levelVersion = 1,
            ruleVersion = 1,
            chapterId = "chapter_1",
            displayNumber = displayNumber,
            mode = LevelMode.TUTORIAL,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(width = 5, height = 6),
            vehicles = listOf(
                VehicleDefinition(
                    id = vehicleId,
                    type = VehicleType.CAR,
                    color = VehicleColor.BLUE,
                    anchor = Cell(2, 3),
                    direction = Direction.NORTH,
                    length = 2,
                ),
            ),
            exits = listOf(
                ExitDefinition(
                    id = ExitId("exit_top"),
                    boundaryCell = Cell(2, 0),
                    direction = Direction.NORTH,
                ),
            ),
            parkingRules = ParkingRules(
                capacity = 3,
                orders = listOf(
                    ColorOrderDefinition(OrderId("blue"), VehicleColor.BLUE, 1),
                ),
            ),
            objective = LevelObjective.ClearAll(setOf(vehicleId)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(CanonicalAction.ExitVehicle(vehicleId)),
        )
    }

    private fun vehicleDefinition(
        id: String,
        color: VehicleColor,
        x: Int,
        length: Int = 2,
    ): VehicleDefinition = VehicleDefinition(
        id = VehicleId(id),
        type = VehicleType.CAR,
        color = color,
        anchor = Cell(x, 3),
        direction = Direction.NORTH,
        length = length,
    )
}
