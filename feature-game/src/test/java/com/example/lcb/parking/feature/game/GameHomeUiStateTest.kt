package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Test

class GameHomeUiStateTest {

    @Test
    fun `paused game uses persisted continue target`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.PAUSED,
                completed = 2,
                continueLevel = 3,
            ),
        )

        assertEquals(GameHomePrimaryAction.OPEN_CURRENT_LEVEL, state.primaryAction)
        assertEquals(3, state.targetLevelNumber)
        assertEquals(2, state.completedLevelCount)
    }

    @Test
    fun `result uses highest persisted incomplete target`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.RESULT,
                completed = 3,
                continueLevel = 4,
            ),
        )

        assertEquals(GameHomePrimaryAction.OPEN_NEXT_LEVEL, state.primaryAction)
        assertEquals(4, state.targetLevelNumber)
        assertEquals(3, state.completedLevelCount)
    }

    @Test
    fun `all completed main levels disable continue`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.RESULT,
                completed = 30,
                continueLevel = 30,
                allCompleted = true,
            ),
        )

        assertEquals(GameHomePrimaryAction.NONE, state.primaryAction)
        assertEquals(30, state.completedLevelCount)
    }

    @Test
    fun `load error exposes retry action`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.ERROR,
                completed = 1,
                continueLevel = 2,
            ),
        )

        assertEquals(GameHomePrimaryAction.RETRY_LOAD, state.primaryAction)
    }

    @Test
    fun `quit attempt restarts persisted continue target from game home`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.QUIT,
                completed = 2,
                continueLevel = 3,
            ),
        )

        assertEquals(GameHomePrimaryAction.RESTART_CURRENT_LEVEL, state.primaryAction)
        assertEquals(3, state.targetLevelNumber)
        assertEquals(2, state.completedLevelCount)
        assertEquals(27, state.totalStars)
        assertEquals(240L, state.coins)
    }

    @Test
    fun `failed attempt restarts current persisted target from game home`() {
        val state = GameHomeUiState.fromGameState(
            gameState(
                phase = GameScreenPhase.FAILURE,
                completed = 7,
                continueLevel = 8,
            ),
        )

        assertEquals(GameHomePrimaryAction.RESTART_CURRENT_LEVEL, state.primaryAction)
        assertEquals(8, state.targetLevelNumber)
    }

    private fun gameState(
        phase: GameScreenPhase,
        completed: Int,
        continueLevel: Int,
        allCompleted: Boolean = false,
    ): MainGameUiState {
        return MainGameUiState(
            phase = phase,
            levelNumber = continueLevel,
            progress = GameProgressUiState(
                coins = 240L,
                totalStars = 27,
                completedLevelCount = completed,
                totalLevelCount = 30,
                continueLevelNumber = continueLevel,
                allMainLevelsCompleted = allCompleted,
            ),
        )
    }
}
