package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlayLayoutPolicyTest {

    @Test
    fun `335 by 752 gives dense board nearly the full available width`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 335f,
            availableHeightDp = 752f,
            boardRows = 10,
            boardColumns = 8,
            parkingCapacity = 5,
            fontScale = 1f,
        )

        assertEquals(GamePlayLayoutMode.COMPACT, layout.mode)
        assertEquals(1, layout.slotRows)
        assertEquals(5, layout.slotColumns)
        assertEquals(layout.cellSizeDp, layout.boardWidthDp / 8f, 0.001f)
        assertEquals(layout.cellSizeDp, layout.boardHeightDp / 10f, 0.001f)
        assertTrue(layout.boardWidthDp >= 327f)
        assertEquals(44f, layout.slotWidthDp, 0.001f)
        assertEquals(244f, layout.parkingContentWidthDp, 0.001f)
        assertEquals(45.5f, (335f - layout.parkingContentWidthDp) / 2f, 0.001f)
        assertTrue(layout.parkingContentWidthDp < layout.contentWidthDp)
        assertTrue(layout.occupiedHeightDp <= 752f)
    }

    @Test
    fun `320 by 568 preserves a useful 8 by 10 complex board`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 320f,
            availableHeightDp = 568f,
            boardRows = 10,
            boardColumns = 8,
            parkingCapacity = 5,
            fontScale = 1f,
        )

        assertEquals(312f, layout.contentWidthDp, 0.001f)
        assertEquals(39f, layout.cellSizeDp, 0.001f)
        assertEquals(312f, layout.boardWidthDp, 0.001f)
        assertEquals(390f, layout.boardHeightDp, 0.001f)
        assertEquals(1, layout.slotRows)
        assertEquals(244f, layout.parkingContentWidthDp, 0.001f)
        assertEquals(38f, (320f - layout.parkingContentWidthDp) / 2f, 0.001f)
        assertTrue(layout.occupiedHeightDp <= 568f)
    }

    @Test
    fun `future capacity above five wraps without hiding a slot`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 320f,
            availableHeightDp = 700f,
            boardRows = 6,
            boardColumns = 5,
            parkingCapacity = 8,
            fontScale = 1f,
        )

        assertEquals(5, layout.slotColumns)
        assertEquals(2, layout.slotRows)
        assertTrue(layout.slotWidthDp > 0f)
        assertTrue(layout.slotHeightDp > layout.slotWidthDp)
        assertEquals(244f, layout.parkingContentWidthDp, 0.001f)
        assertTrue(layout.parkingContentWidthDp <= layout.contentWidthDp)
        assertTrue(layout.occupiedHeightDp <= 700f)
    }

    @Test
    fun `portrait tablet caps board width and cell size`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 800f,
            availableHeightDp = 1280f,
            boardRows = 10,
            boardColumns = 8,
            parkingCapacity = 5,
            fontScale = 1f,
        )

        assertEquals(GamePlayLayoutMode.TABLET, layout.mode)
        assertEquals(600f, layout.contentWidthDp, 0.001f)
        assertEquals(75f, layout.cellSizeDp, 0.001f)
        assertEquals(600f, layout.boardViewportWidthDp, 0.001f)
        assertEquals(750f, layout.boardViewportHeightDp, 0.001f)
        assertEquals(58f, layout.slotWidthDp, 0.001f)
        assertEquals(330f, layout.parkingContentWidthDp, 0.001f)
        assertEquals(235f, (800f - layout.parkingContentWidthDp) / 2f, 0.001f)
    }

    @Test
    fun `large font keeps both icon sections without textual panel growth`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 412f,
            availableHeightDp = 800f,
            boardRows = 7,
            boardColumns = 6,
            parkingCapacity = 5,
            fontScale = 1.5f,
        )

        assertEquals(GamePlayLayoutMode.COMPACT, layout.mode)
        assertTrue(layout.orderIndicatorHeightDp > 0f)
        assertTrue(layout.parkingSlotsHeightDp > 0f)
        assertEquals(1, layout.slotRows)
    }

    @Test
    fun `zero parking capacity has no parking footprint`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 360f,
            availableHeightDp = 720f,
            boardRows = 6,
            boardColumns = 6,
            parkingCapacity = 0,
            fontScale = 1f,
        )

        assertEquals(0, layout.slotColumns)
        assertEquals(0, layout.slotRows)
        assertEquals(0f, layout.parkingContentWidthDp, 0.001f)
        assertEquals(0f, layout.parkingSlotsHeightDp, 0.001f)
    }
}
