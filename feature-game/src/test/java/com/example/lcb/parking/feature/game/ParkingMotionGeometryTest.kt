package com.example.lcb.parking.feature.game

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingMotionGeometryTest {

    @Test
    fun `all four directions leave the board and finish in the requested slot`() {
        val layout = compactLayout()
        val vehicles = listOf(
            vehicle("up", VehicleDirection.UP, row = 4, column = 2),
            vehicle("right", VehicleDirection.RIGHT, row = 4, column = 2),
            vehicle("down", VehicleDirection.DOWN, row = 4, column = 5),
            vehicle("left", VehicleDirection.LEFT, row = 7, column = 5),
        )

        vehicles.forEachIndexed { index, vehicle ->
            val slotIndex = index.coerceAtMost(4)
            val path = ParkingMotionGeometry.boardToWaitingSlotPath(layout, vehicle, slotIndex)
            val expectedStart = ParkingMotionGeometry.boardExitCenter(layout, vehicle)
            val expectedEnd = ParkingMotionGeometry.waitingSlotCenter(layout, slotIndex)

            assertPointEquals(expectedStart, path.sampleByDistance(0f).point)
            assertPointEquals(expectedEnd, path.sampleByDistance(path.totalDistanceDp).point)
            assertTrue("${vehicle.direction} should have a non-empty route", path.totalDistanceDp > 0f)
            assertTrue(path.sampleByDistance(path.totalDistanceDp).reachedEnd)
            assertFalse(path.sampleByDistance(path.totalDistanceDp * 0.5f).reachedEnd)
        }
    }

    @Test
    fun `route uses the matching screen-side corridor before entering bottom parking`() {
        val layout = compactLayout()
        val leftPath = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = layout,
            vehicle = vehicle("left", VehicleDirection.LEFT, row = 5, column = 4),
            slotIndex = 0,
        )
        val rightPath = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = layout,
            vehicle = vehicle("right", VehicleDirection.RIGHT, row = 5, column = 2),
            slotIndex = 4,
        )
        val leftVisiblePoints = leftPath.points.drop(1)
        val rightVisiblePoints = rightPath.points.drop(1)

        assertTrue(leftVisiblePoints.any { it.x < layout.contentWidthDp * 0.25f })
        assertTrue(rightVisiblePoints.any { it.x > layout.contentWidthDp * 0.75f })
        assertTrue(leftPath.points.dropLast(1).any { it.y > leftPath.points.last().y })
        assertTrue(rightPath.points.dropLast(1).any { it.y > rightPath.points.last().y })
        assertEquals(0f, leftPath.sampleByProgress(1f).rotationDegrees, 1.5f)
        assertEquals(0f, rightPath.sampleByProgress(1f).rotationDegrees, 1.5f)
    }

    @Test
    fun `route keeps the original exit heading before turning toward parking`() {
        val layout = compactLayout()
        val expectedRotations = mapOf(
            VehicleDirection.UP to 0f,
            VehicleDirection.RIGHT to 90f,
            VehicleDirection.DOWN to 180f,
            VehicleDirection.LEFT to -90f,
        )

        expectedRotations.forEach { (direction, expectedRotation) ->
            val path = ParkingMotionGeometry.boardToWaitingSlotPath(
                layout = layout,
                vehicle = vehicle("heading_$direction", direction, row = 4, column = 3),
                slotIndex = 2,
            )

            assertEquals(
                direction.name,
                expectedRotation,
                path.sampleByDistance(1f).rotationDegrees,
                1.5f,
            )
        }
    }

    @Test
    fun `distance sampling advances at constant path distance on straight segments`() {
        val layout = compactLayout()
        val path = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = layout,
            vehicle = vehicle("up", VehicleDirection.UP, row = 5, column = 3),
            slotIndex = 0,
        )
        val first = path.sampleByDistance(8f).point
        val second = path.sampleByDistance(18f).point

        assertEquals(10f, distance(first, second), 0.02f)
        assertPointEquals(
            path.sampleByDistance(path.totalDistanceDp * 0.25f).point,
            path.sampleByProgress(0.25f).point,
        )
    }

    @Test
    fun `rounded route changes heading gradually without a right-angle snap`() {
        val path = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = compactLayout(),
            vehicle = vehicle("right", VehicleDirection.RIGHT, row = 4, column = 2),
            slotIndex = 1,
        )
        var previousRotation = path.sampleByProgress(0f).rotationDegrees
        var largestTurn = 0f
        var sampleIndex = 1
        while (sampleIndex <= 160) {
            val rotation = path.sampleByProgress(sampleIndex / 160f).rotationDegrees
            largestTurn = maxOf(largestTurn, angularDistance(previousRotation, rotation))
            previousRotation = rotation
            sampleIndex++
        }

        assertTrue("largest sampled turn was $largestTurn degrees", largestTurn < 35f)
    }

    @Test
    fun `compact layout re-enters through a visible side corridor`() {
        val layout = compactLayout()
        assertEquals(GamePlayLayoutMode.COMPACT, layout.mode)
        val path = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = layout,
            vehicle = vehicle("up", VehicleDirection.UP, row = 6, column = 1),
            slotIndex = 4,
        )

        // 离场 lead-out 允许位于屏外；之后必须存在可见侧廊并最终落入可见车槽。
        assertTrue(path.points.any { point -> point.x in 0f..layout.contentWidthDp })
        val end = path.points.last()
        assertTrue(end.x in 0f..layout.contentWidthDp)
        assertTrue(end.y <= layout.occupiedHeightDp + 0.001f)
    }

    @Test
    fun `reduced motion always resolves directly to the stable slot endpoint`() {
        val layout = compactLayout()
        val path = ParkingMotionGeometry.boardToWaitingSlotPath(
            layout = layout,
            vehicle = vehicle("down", VehicleDirection.DOWN, row = 2, column = 4),
            slotIndex = 3,
        )
        val expected = ParkingMotionGeometry.waitingSlotCenter(layout, 3)

        val fromStart = path.sampleByDistance(0f, reduceMotion = true)
        val fromProgress = path.sampleByProgress(0.35f, reduceMotion = true)

        assertPointEquals(expected, fromStart.point)
        assertPointEquals(expected, fromProgress.point)
        assertTrue(fromStart.reachedEnd)
        assertTrue(fromProgress.reachedEnd)
        assertEquals(0f, fromStart.rotationDegrees, 1.5f)
    }

    private fun compactLayout(): GamePlayLayoutSpec = GamePlayLayoutPolicy.calculate(
        availableWidthDp = 320f,
        availableHeightDp = 568f,
        boardRows = 10,
        boardColumns = 8,
        parkingCapacity = 5,
        fontScale = 1f,
    )

    private fun vehicle(
        id: String,
        direction: VehicleDirection,
        row: Int,
        column: Int,
    ): VehicleRenderModel {
        val vertical = direction == VehicleDirection.UP || direction == VehicleDirection.DOWN
        return VehicleRenderModel(
            id = id,
            row = row,
            column = column,
            widthCells = if (vertical) 1 else 2,
            heightCells = if (vertical) 2 else 1,
            direction = direction,
            visualState = VehicleVisualState.MOVING,
            artVariant = VehicleArtVariant.CORAL,
            color = VehicleArtVariant.CORAL.argb,
        )
    }

    private fun assertPointEquals(expected: ParkingMotionPoint, actual: ParkingMotionPoint) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }

    private fun distance(first: ParkingMotionPoint, second: ParkingMotionPoint): Float =
        hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble()).toFloat()

    private fun angularDistance(first: Float, second: Float): Float {
        var difference = abs(second - first) % 360f
        if (difference > 180f) difference = 360f - difference
        return difference
    }
}
