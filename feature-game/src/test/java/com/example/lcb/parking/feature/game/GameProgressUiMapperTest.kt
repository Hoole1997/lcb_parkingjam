package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.DifficultyTier
import com.example.lcb.parking.domain.model.Direction
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressUiMapperTest {

    @Test
    fun `hard preview does not block continue and both branches unlock after level 25`() {
        val levels = levels()
        val completed = levels.take(25).mapTo(linkedSetOf(), LevelDefinition::id)
        val state = GameProgressUiMapper.map(aggregate(levels, completed))

        assertEquals(27, state.continueLevelNumber)
        assertEquals(LevelNodeStatus.AVAILABLE, state.node(26).status)
        assertTrue(state.node(26).isHardPreview)
        assertEquals(LevelNodeStatus.CURRENT, state.node(27).status)
        assertEquals(LevelNodeStatus.LOCKED, state.node(28).status)
        assertFalse(state.allMainLevelsCompleted)
    }

    @Test
    fun `optional hard node is excluded from main completion`() {
        val levels = levels()
        val completed = levels
            .filterNot { it.displayNumber == 26 }
            .mapTo(linkedSetOf(), LevelDefinition::id)
        val state = GameProgressUiMapper.map(aggregate(levels, completed))

        assertTrue(state.allMainLevelsCompleted)
        assertEquals(29, state.completedLevelCount)
        assertEquals(30, state.continueLevelNumber)
        assertEquals(LevelNodeStatus.AVAILABLE, state.node(26).status)
    }

    @Test
    fun `boss styling follows semantic mode or content tag`() {
        val levels = levels()
        val state = GameProgressUiMapper.map(aggregate(levels, emptySet()))

        assertTrue(state.node(10).isBoss)
        assertTrue(state.node(20).isBoss)
        assertTrue(state.node(30).isBoss)
        assertFalse(state.node(29).isBoss)
    }

    private fun GameProgressUiState.node(levelNumber: Int): LevelNodeUiState =
        levelNodes.single { it.levelNumber == levelNumber }

    private fun aggregate(
        levels: List<LevelDefinition>,
        completed: Set<LevelId>,
    ): DomainGameSessionAggregate {
        val current = levels[24]
        val snapshot = GameSnapshot.initial(
            current,
            AttemptId("attempt"),
            AttemptChainId("chain"),
        )
        return DomainGameSessionAggregate(
            projection = DomainGameProjection(current, snapshot),
            progress = PlayerProgress(
                coins = 400L,
                completedLevelIds = completed,
                bestStarsByLevel = completed.associateWith { 3 },
            ),
            levels = levels,
            snapshotsByLevel = mapOf(current.id to snapshot),
            currentLevelIndex = 24,
        )
    }

    private fun levels(): List<LevelDefinition> = (1..30).map(::level)

    private fun level(number: Int): LevelDefinition {
        val vehicle = VehicleDefinition(
            id = VehicleId("car_$number"),
            type = VehicleType.CAR,
            color = VehicleColor.BLUE,
            anchor = Cell(2, 2),
            direction = Direction.NORTH,
            length = 2,
        )
        return LevelDefinition(
            id = LevelId("main_${number.toString().padStart(3, '0')}"),
            levelVersion = 1,
            ruleVersion = 1,
            chapterId = "chapter_01",
            displayNumber = number,
            mode = when (number) {
                10, 20 -> LevelMode.BOSS
                30 -> LevelMode.RESCUE
                26 -> LevelMode.HARD_PREVIEW
                else -> LevelMode.NORMAL
            },
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(5, 6),
            vehicles = listOf(vehicle),
            exits = listOf(
                ExitDefinition(
                    id = ExitId("north"),
                    boundaryCell = Cell(2, 0),
                    direction = Direction.NORTH,
                ),
            ),
            parkingRules = ParkingRules(
                capacity = 3,
                orders = listOf(
                    ColorOrderDefinition(OrderId("blue_$number"), VehicleColor.BLUE, 1),
                ),
            ),
            objective = LevelObjective.ClearAll(setOf(vehicle.id)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(CanonicalAction.ExitVehicle(vehicle.id)),
            progression = LevelProgression(
                prerequisiteLevelIds = when (number) {
                    1 -> emptySet()
                    27 -> setOf(LevelId("main_025"))
                    else -> setOf(
                        LevelId("main_${(number - 1).toString().padStart(3, '0')}"),
                    )
                },
                skippable = number == 26,
            ),
            contentTags = if (number in setOf(10, 20, 30)) setOf("boss") else emptySet(),
        )
    }
}
