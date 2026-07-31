package com.example.lcb.parking.feature.game

import kotlin.math.PI
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaroutMotionMathTest {

    @Test
    fun `polyline samples its center by cumulative distance`() {
        val path = path()
        val sample = CaroutMotionSample()

        assertEquals(7f, path.totalDistanceDp, TOLERANCE)
        assertEquals(2, path.segmentCount)

        path.sampleInto(1.5f, sample)
        assertSample(sample, x = 1.5f, y = 0f, angle = HALF_PI)
        assertFalse(sample.reachedEnd)

        path.sampleInto(5f, sample)
        assertSample(sample, x = 3f, y = 2f, angle = PI.toFloat())
        assertFalse(sample.reachedEnd)

        path.sampleInto(99f, sample)
        assertSample(sample, x = 3f, y = 4f, angle = PI.toFloat())
        assertTrue(sample.reachedEnd)
    }

    @Test
    fun `corner itself keeps incoming segment angle like carout`() {
        val path = path()
        val sample = CaroutMotionSample()

        path.sampleInto(3f, sample)

        assertSample(sample, x = 3f, y = 0f, angle = HALF_PI)
    }

    @Test
    fun `invalid distances clamp to stable path endpoints`() {
        val path = path()
        val sample = CaroutMotionSample()

        path.sampleInto(Float.NaN, sample)
        assertSample(sample, x = 0f, y = 0f, angle = HALF_PI)
        assertFalse(sample.reachedEnd)

        path.sampleInto(Float.POSITIVE_INFINITY, sample)
        assertSample(sample, x = 3f, y = 4f, angle = PI.toFloat())
        assertTrue(sample.reachedEnd)
    }

    @Test
    fun `adjacent duplicate points are removed once during construction`() {
        val path = CaroutPolylinePath.from(
            listOf(
                CaroutMotionPoint(0f, 0f),
                CaroutMotionPoint(0f, 0f),
                CaroutMotionPoint(0f, 5f),
            ),
        )

        assertEquals(1, path.segmentCount)
        assertEquals(5f, path.totalDistanceDp, TOLERANCE)
    }

    @Test
    fun `drive and leave advance at reference speeds with a fifty millisecond cap`() {
        val longPath = CaroutPolylinePath.from(
            listOf(CaroutMotionPoint(0f, 0f), CaroutMotionPoint(1_000f, 0f)),
        )

        assertEquals(
            5.6f,
            longPath.advanceDistance(0f, 0.01f, CaroutMotionKind.DRIVE),
            TOLERANCE,
        )
        assertEquals(
            4.8f,
            longPath.advanceDistance(0f, 0.01f, CaroutMotionKind.LEAVE),
            TOLERANCE,
        )
        assertEquals(
            28f,
            longPath.advanceDistance(0f, 0.20f, CaroutMotionKind.DRIVE),
            TOLERANCE,
        )
        assertEquals(
            24f,
            longPath.advanceDistance(0f, Float.POSITIVE_INFINITY, CaroutMotionKind.LEAVE),
            TOLERANCE,
        )
    }

    @Test
    fun `distance advancement never crosses the path endpoint`() {
        val path = path()

        assertEquals(
            path.totalDistanceDp,
            path.advanceDistance(6f, 0.05f, CaroutMotionKind.DRIVE),
            TOLERANCE,
        )
        assertEquals(
            0f,
            path.advanceDistance(Float.NaN, -1f, CaroutMotionKind.LEAVE),
            TOLERANCE,
        )
    }

    @Test
    fun `turning follows the shortest wrapped angle with carout response`() {
        val degrees = PI.toFloat() / 180f
        val wrappedDifference = CaroutMotionMath.shortestAngleDelta(
            currentAngleRadians = 179f * degrees,
            targetAngleRadians = -179f * degrees,
        )
        assertEquals(2f * degrees, wrappedDifference, 0.0001f)

        // 1/60s 对应响应系数 0.2；从朝上转向朝左应选择 -90 度短路径。
        val firstFrame = CaroutMotionMath.turnToward(
            currentAngleRadians = 0f,
            targetAngleRadians = -HALF_PI,
            deltaSeconds = 1f / 60f,
        )
        assertEquals(-HALF_PI * 0.2f, firstFrame, 0.0001f)

        // 即便传入更大的 dt，也先截为 50ms，因此单帧响应系数最多是 0.6。
        val cappedFrame = CaroutMotionMath.turnToward(0f, HALF_PI, 0.5f)
        assertEquals(HALF_PI * 0.6f, cappedFrame, 0.0001f)
    }

    @Test
    fun `bump accelerates to contact then performs damped rebound`() {
        val freeDistance = 40f

        assertEquals(0f, CaroutMotionMath.bumpOffsetDp(0f, freeDistance), TOLERANCE)
        assertEquals(10f, CaroutMotionMath.bumpOffsetDp(0.08f, freeDistance), TOLERANCE)
        assertEquals(40f, CaroutMotionMath.bumpOffsetDp(0.16f, freeDistance), TOLERANCE)

        val halfRebound = CaroutMotionMath.bumpOffsetDp(0.16f + 0.34f * 0.5f, freeDistance)
        val expected = freeDistance * exp(-2.5).toFloat() *
            kotlin.math.cos(PI * 1.1).toFloat()
        assertEquals(expected, halfRebound, 0.0001f)
        assertTrue("The spring should overshoot behind the origin", halfRebound < 0f)
        assertEquals(0f, CaroutMotionMath.bumpOffsetDp(0.50f, freeDistance), TOLERANCE)
    }

    @Test
    fun `blocker shake starts at impact and decays within three hundred milliseconds`() {
        val cellSize = 50f
        assertEquals(0f, CaroutMotionMath.blockerShakeOffsetDp(0.15f, cellSize), TOLERANCE)
        assertEquals(0f, CaroutMotionMath.blockerShakeOffsetDp(0.16f, cellSize), TOLERANCE)

        // p=0.125 时 sin(4PIp)=1，便于验证明确的正峰值公式。
        val elapsed = 0.16f + 0.30f * 0.125f
        val expected = cellSize * 0.09f * exp(-0.5).toFloat()
        assertEquals(
            expected,
            CaroutMotionMath.blockerShakeOffsetDp(elapsed, cellSize),
            0.0001f,
        )
        assertEquals(0f, CaroutMotionMath.blockerShakeOffsetDp(0.46f, cellSize), TOLERANCE)
    }

    private fun path(): CaroutPolylinePath = CaroutPolylinePath.from(
        listOf(
            CaroutMotionPoint(0f, 0f),
            CaroutMotionPoint(3f, 0f),
            CaroutMotionPoint(3f, 4f),
        ),
    )

    private fun assertSample(
        actual: CaroutMotionSample,
        x: Float,
        y: Float,
        angle: Float,
    ) {
        assertEquals(x, actual.xDp, TOLERANCE)
        assertEquals(y, actual.yDp, TOLERANCE)
        assertEquals(angle, actual.segmentAngleRadians, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
        val HALF_PI = (PI / 2.0).toFloat()
    }
}
