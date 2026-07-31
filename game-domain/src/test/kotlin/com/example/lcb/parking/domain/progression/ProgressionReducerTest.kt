package com.example.lcb.parking.domain.progression

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptSnapshot
import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.BoardSnapshot
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
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.RewardTransactionId
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionReducerTest {

    @Test
    fun `duplicate completion transaction never awards twice`() {
        val level = level(number = 1)
        val snapshot = completedSnapshot(level)
        val transaction = RewardTransactionId("completion-a")

        val first = ProgressionReducer.settleCompletion(level, snapshot, PlayerProgress(), transaction)
        val duplicate = ProgressionReducer.settleCompletion(level, snapshot, first.progress, transaction)

        assertEquals(35, first.awardedCoins)
        assertEquals(35L, first.progress.coins)
        assertTrue(duplicate.duplicate)
        assertEquals(0, duplicate.awardedCoins)
        assertEquals(first.progress, duplicate.progress)
    }

    @Test
    fun `replay grants only improved star difference`() {
        val level = level(number = 7)
        val twoStars = completedSnapshot(level, collisions = 1)
        val threeStars = completedSnapshot(level, collisions = 0)

        val first = ProgressionReducer.settleCompletion(
            level,
            twoStars,
            PlayerProgress(),
            RewardTransactionId("completion-2-star"),
        )
        val improved = ProgressionReducer.settleCompletion(
            level,
            threeStars,
            first.progress,
            RewardTransactionId("completion-3-star"),
        )

        assertEquals(25, first.awardedCoins)
        assertEquals(10, improved.awardedCoins)
        assertEquals(35L, improved.progress.coins)
        assertEquals(3, improved.progress.bestStarsByLevel[level.id])
    }

    @Test
    fun `level five starter bonus is granted once across different completions`() {
        val level = level(number = 5)
        val snapshot = completedSnapshot(level)

        val first = ProgressionReducer.settleCompletion(
            level,
            snapshot,
            PlayerProgress(),
            RewardTransactionId("l5-first"),
        )
        val replay = ProgressionReducer.settleCompletion(
            level,
            snapshot,
            first.progress,
            RewardTransactionId("l5-replay"),
        )

        assertEquals(100, first.starterBonusCoins)
        assertEquals(135L, first.progress.coins)
        assertEquals(0, replay.starterBonusCoins)
        assertEquals(135L, replay.progress.coins)
        assertFalse(replay.duplicate)
    }

    private fun level(number: Int): LevelDefinition = LevelDefinition(
        id = LevelId("main_${number.toString().padStart(3, '0')}"),
        levelVersion = 1,
        ruleVersion = 1,
        chapterId = "chapter_01",
        displayNumber = number,
        mode = LevelMode.NORMAL,
        difficultyTier = DifficultyTier.D1,
        board = BoardDefinition(5, 6),
        vehicles = emptyList(),
        exits = listOf(ExitDefinition(ExitId("north"), Cell(0, 0), Direction.NORTH)),
        parkingRules = ParkingRules(capacity = 1, orders = emptyList()),
        objective = LevelObjective.ClearAll(emptySet()),
        initialSafety = InitialSafety.TutorialUnlimited,
        canonicalSolution = emptyList(),
    )

    private fun completedSnapshot(level: LevelDefinition, collisions: Int = 0): GameSnapshot {
        val attemptId = AttemptId("attempt")
        val chainId = AttemptChainId("chain")
        return GameSnapshot(
            levelId = level.id,
            levelVersion = level.levelVersion,
            ruleVersion = level.ruleVersion,
            board = BoardSnapshot(emptyMap(), emptySet()),
            parkingLot = ParkingLotSnapshot.initial(level.parkingRules),
            attempt = AttemptSnapshot(
                attemptId = attemptId,
                attemptChainId = chainId,
                businessState = AttemptBusinessState.COMPLETE,
            ),
            chain = com.example.lcb.parking.domain.model.AttemptChainSnapshot(
                id = chainId,
                collisionCount = collisions,
            ),
            safety = SafetyState.TutorialUnlimited,
        )
    }
}
