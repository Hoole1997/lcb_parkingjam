package com.example.lcb.parking.domain.validation

import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.DifficultyTier
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.ExitDefinition
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelValidatorTest {

    @Test
    fun `valid tutorial level passes structural checks`() {
        assertTrue(LevelValidator.validateStructure(validLevel()).isValid)
    }

    @Test
    fun `overlap and misaligned exit are explicit errors`() {
        val base = validLevel()
        val overlapping = base.vehicles.first().copy(id = VehicleId("B"))
        val invalid = base.copy(
            vehicles = base.vehicles + overlapping,
            objective = LevelObjective.ClearAll(setOf(VehicleId("A"), VehicleId("B"))),
            canonicalSolution = listOf(
                CanonicalAction.ExitVehicle(VehicleId("A")),
                CanonicalAction.ExitVehicle(VehicleId("B")),
            ),
            exits = listOf(ExitDefinition(ExitId("wrong"), Cell(4, 2), Direction.EAST)),
        )

        val codes = LevelValidator.validateStructure(invalid).issues.map { it.code }.toSet()

        assertTrue("VEHICLE_OVERLAP" in codes)
        assertTrue("EXIT_UNREACHABLE" in codes)
    }

    @Test
    fun `key lock cycle is rejected`() {
        val a = VehicleDefinition(
            id = VehicleId("A"),
            type = VehicleType.KEY_CAR,
            color = VehicleColor.RED,
            anchor = Cell(0, 0),
            direction = Direction.NORTH,
            length = 2,
            lockedBy = VehicleId("B"),
        )
        val b = VehicleDefinition(
            id = VehicleId("B"),
            type = VehicleType.KEY_CAR,
            color = VehicleColor.RED,
            anchor = Cell(2, 0),
            direction = Direction.NORTH,
            length = 2,
            lockedBy = VehicleId("A"),
        )
        val base = validLevel()
        val invalid = base.copy(
            vehicles = listOf(a, b),
            objective = LevelObjective.ClearAll(setOf(a.id, b.id)),
            canonicalSolution = listOf(
                CanonicalAction.ExitVehicle(a.id),
                CanonicalAction.ExitVehicle(b.id),
            ),
            exits = listOf(
                ExitDefinition(ExitId("a-exit"), Cell(0, 0), Direction.NORTH),
                ExitDefinition(ExitId("b-exit"), Cell(2, 0), Direction.NORTH),
            ),
        )

        val codes = LevelValidator.validateStructure(invalid).issues.map { it.code }

        assertTrue("LOCK_CYCLE" in codes)
    }

    @Test
    fun `invalid parking capacity adjacent orders and color shortage are explicit errors`() {
        val base = validLevel()
        val invalid = base.copy(
            parkingRules = ParkingRules(
                capacity = 0,
                orders = listOf(
                    ColorOrderDefinition(OrderId("red-a"), VehicleColor.RED, 1),
                    ColorOrderDefinition(OrderId("red-b"), VehicleColor.RED, 2),
                ),
            ),
        )

        val codes = LevelValidator.validateStructure(invalid).issues.map { it.code }.toSet()

        assertTrue("PARKING_CAPACITY" in codes)
        assertTrue("ORDER_COLOR_ADJACENT" in codes)
        assertTrue("ORDER_COLOR_SUPPLY" in codes)
    }

    private fun validLevel(): LevelDefinition {
        val vehicleId = VehicleId("A")
        return LevelDefinition(
            id = LevelId("main_001"),
            levelVersion = 1,
            ruleVersion = 2,
            chapterId = "chapter_01",
            displayNumber = 1,
            mode = LevelMode.NORMAL,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(5, 6),
            vehicles = listOf(
                VehicleDefinition(
                    id = vehicleId,
                    type = VehicleType.CAR,
                    color = VehicleColor.RED,
                    anchor = Cell(2, 0),
                    direction = Direction.NORTH,
                    length = 2,
                ),
            ),
            exits = listOf(ExitDefinition(ExitId("north"), Cell(2, 0), Direction.NORTH)),
            parkingRules = ParkingRules(
                capacity = 2,
                orders = listOf(ColorOrderDefinition(OrderId("red-1"), VehicleColor.RED, 1)),
            ),
            objective = LevelObjective.ClearAll(setOf(vehicleId)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(CanonicalAction.ExitVehicle(vehicleId)),
        )
    }
}
