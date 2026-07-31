package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelSelectRoutePlannerTest {

    @Test
    fun `thirty nodes remain unique and inside horizontal route bounds`() {
        val placements = plan(nodes())

        assertEquals(30, placements.size)
        assertEquals(30, placements.map(LevelNodePlacement::levelNumber).distinct().size)
        assertTrue(placements.all { it.centerX in 40f..360f })
        assertTrue(placements.zipWithNext().all { (first, second) ->
            first.levelNumber < second.levelNumber
        })
    }

    @Test
    fun `hard preview is a branch and does not consume main route step`() {
        val placements = plan(nodes())
        val level25 = placements.single { it.levelNumber == 25 }
        val hard26 = placements.single { it.levelNumber == 26 }
        val level27 = placements.single { it.levelNumber == 27 }

        assertFalse(level25.isBranch)
        assertTrue(hard26.isBranch)
        assertFalse(level27.isBranch)
        assertEquals(92f, level27.centerY - level25.centerY, 0.001f)
        assertTrue(hard26.centerY in level25.centerY..level27.centerY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ui state rejects duplicate level numbers`() {
        val duplicate = node(1)

        LevelSelectUiState(
            starProgress = StarProgressUiState(earned = 0, maximum = 90),
            continueLevelNumber = 1,
            nodes = listOf(duplicate, duplicate),
        )
    }

    private fun plan(nodes: List<LevelNodeUiState>): List<LevelNodePlacement> {
        return LevelSelectRoutePlanner.plan(
            nodes = nodes,
            contentWidth = 400f,
            horizontalInset = 40f,
            maxTrackWidth = 320f,
            topOffset = 60f,
            verticalStep = 92f,
        )
    }

    private fun nodes(): List<LevelNodeUiState> = (1..30).map { level ->
        node(level)
    }

    private fun node(level: Int): LevelNodeUiState {
        return LevelNodeUiState(
            levelNumber = level,
            stars = if (level < 12) 3 else 0,
            status = when {
                level < 12 -> LevelNodeStatus.COMPLETED
                level == 12 -> LevelNodeStatus.CURRENT
                level == 13 -> LevelNodeStatus.AVAILABLE
                else -> LevelNodeStatus.LOCKED
            },
            isBoss = level in setOf(10, 20, 30),
            isHardPreview = level == 26,
        )
    }
}
