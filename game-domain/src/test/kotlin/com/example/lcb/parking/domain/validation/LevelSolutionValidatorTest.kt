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
import com.example.lcb.parking.domain.model.WallDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelSolutionValidatorTest {
    @Test
    fun `L1 style dependency chain passes canonical replay and strong search`() {
        val report = LevelSolutionValidator().validate(tutorialChain())

        assertTrue(report.issues.toString(), report.isValid)
    }

    @Test
    fun `canonical step that collides instead of exiting is rejected`() {
        val level = tutorialChain().copy(
            canonicalSolution = listOf(exit(A), exit(B), exit(C)),
        )

        val report = LevelSolutionValidator().validateCanonicalSolution(level)

        assertEquals(
            LevelSolutionValidator.ISSUE_CANONICAL_STEP,
            report.issues.single().code,
        )
    }

    @Test
    fun `canonical sequence that stops before completion is rejected`() {
        val level = tutorialChain().copy(
            canonicalSolution = listOf(exit(C), exit(B)),
        )

        val report = LevelSolutionValidator().validateCanonicalSolution(level)

        assertEquals(
            LevelSolutionValidator.ISSUE_CANONICAL_NOT_COMPLETE,
            report.issues.single().code,
        )
    }

    @Test
    fun `reachable state with no safe exit is reported as dead end`() {
        val deadlocked = tutorialChain().copy(
            walls = listOf(WallDefinition("permanent-block", setOf(Cell(2, 0)))),
        )

        val report = LevelSolutionValidator().validateStrongSolvability(deadlocked)

        assertEquals(LevelSolutionValidator.ISSUE_DEAD_END, report.issues.single().code)
        assertTrue(report.issues.single().message.contains("initial state"))
    }

    @Test
    fun `state bound returns explicit issue instead of unbounded traversal`() {
        val report = LevelSolutionValidator(maxStates = 1)
            .validateStrongSolvability(tutorialChain())

        assertEquals(LevelSolutionValidator.ISSUE_SEARCH_LIMIT, report.issues.single().code)
        assertTrue(report.issues.single().message.contains("maxStates=1"))
    }

    private fun tutorialChain(): LevelDefinition {
        // C can leave first; that frees B's north ray, then B frees A's east ray.
        val a = vehicle(A, Cell(0, 3), Direction.EAST)
        val b = vehicle(B, Cell(3, 2), Direction.NORTH)
        val c = vehicle(C, Cell(3, 0), Direction.WEST)
        return LevelDefinition(
            id = LevelId("main_001_chain"),
            levelVersion = 1,
            ruleVersion = 2,
            chapterId = "chapter_01",
            displayNumber = 1,
            mode = LevelMode.TUTORIAL,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(6, 6),
            vehicles = listOf(a, b, c),
            exits = listOf(
                ExitDefinition(ExitId("a-east"), Cell(5, 3), Direction.EAST),
                ExitDefinition(ExitId("b-north"), Cell(3, 0), Direction.NORTH),
                ExitDefinition(ExitId("c-west"), Cell(0, 0), Direction.WEST),
            ),
            parkingRules = ParkingRules(
                capacity = 2,
                orders = listOf(ColorOrderDefinition(OrderId("red-3"), VehicleColor.RED, 3)),
            ),
            objective = LevelObjective.ClearAll(setOf(A, B, C)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(exit(C), exit(B), exit(A)),
        )
    }

    private fun vehicle(
        id: VehicleId,
        anchor: Cell,
        direction: Direction,
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        type = VehicleType.CAR,
        color = VehicleColor.RED,
        anchor = anchor,
        direction = direction,
        length = 2,
    )

    private fun exit(id: VehicleId): CanonicalAction = CanonicalAction.ExitVehicle(id)

    private companion object {
        val A = VehicleId("A")
        val B = VehicleId("B")
        val C = VehicleId("C")
    }
}
