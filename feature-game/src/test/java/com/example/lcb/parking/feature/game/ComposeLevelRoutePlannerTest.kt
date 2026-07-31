package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compose 网格阅读顺序回归，困难关同样占用一个标准网格位置。 */
class ComposeLevelRoutePlannerTest {

    @Test
    fun `all thirty levels keep stable reading order in the grid`() {
        val nodes = levelGridNodes((30 downTo 1).map(::node))

        assertEquals(30, nodes.size)
        assertEquals((1..30).toList(), nodes.map(LevelNodeUiState::levelNumber))
        assertTrue(nodes.single { it.levelNumber == 26 }.isHardPreview)
    }

    private fun node(levelNumber: Int): LevelNodeUiState {
        return LevelNodeUiState(
            levelNumber = levelNumber,
            stars = 0,
            status = if (levelNumber == 1) LevelNodeStatus.CURRENT else LevelNodeStatus.LOCKED,
            isBoss = levelNumber in setOf(10, 20, 30),
            isHardPreview = levelNumber == 26,
        )
    }
}
