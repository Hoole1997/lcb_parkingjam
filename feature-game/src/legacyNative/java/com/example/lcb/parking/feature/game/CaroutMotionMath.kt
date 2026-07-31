package com.example.lcb.parking.feature.game

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * carout 参考实现使用的逻辑坐标点，单位统一为 dp。
 *
 * 该类型只用于构建路径；逐帧采样写入 [CaroutMotionSample]，不会持续创建坐标对象。
 */
internal data class CaroutMotionPoint(
    val xDp: Float,
    val yDp: Float,
)

/** 一条车辆路径的用途；速度与 carout Canvas 参考实现保持一致。 */
internal enum class CaroutMotionKind(
    val speedDpPerSecond: Float,
) {
    DRIVE(CaroutMotionMath.DRIVE_SPEED_DP_PER_SECOND),
    LEAVE(CaroutMotionMath.LEAVE_SPEED_DP_PER_SECOND),
}

/**
 * 可复用的逐帧采样容器。
 *
 * 调用方应为每个活动车辆长期持有一个实例，再通过 [CaroutPolylinePath.sampleInto] 覆写数值，
 * 避免在 60fps 动画热路径中分配临时对象。
 */
internal class CaroutMotionSample {
    var xDp: Float = 0f
        internal set
    var yDp: Float = 0f
        internal set
    /** 0 表示车头向上，PI / 2 表示向右，与当前车辆素材方向一致。 */
    var segmentAngleRadians: Float = 0f
        internal set
    var reachedEnd: Boolean = false
        internal set
}

/**
 * 基于累计距离的不可变折线路径。
 *
 * 构建时会移除相邻重复点，并把坐标、累计距离和每段角度复制到 primitive 数组。逐帧采样只做
 * 二分查找和浮点插值；中心严格沿折线移动，转向平滑由 [CaroutMotionMath.turnToward] 独立完成。
 */
internal class CaroutPolylinePath private constructor(
    private val xCoordinatesDp: FloatArray,
    private val yCoordinatesDp: FloatArray,
    private val cumulativeDistancesDp: FloatArray,
    private val segmentAnglesRadians: FloatArray,
) {
    val totalDistanceDp: Float = cumulativeDistancesDp.last()
    val segmentCount: Int = segmentAnglesRadians.size

    /**
     * 把 [distanceDp] 对应的中心点和当前折线段方向写入 [destination]。
     * 距离超界会稳定吸附到首尾，NaN 按起点处理。
     */
    fun sampleInto(
        distanceDp: Float,
        destination: CaroutMotionSample,
    ) {
        val stableDistance = stableDistance(distanceDp)
        val endPointIndex = endPointIndexFor(stableDistance)
        val segmentIndex = (endPointIndex - 1).coerceIn(0, segmentAnglesRadians.lastIndex)
        val segmentStartDistance = cumulativeDistancesDp[segmentIndex]
        val segmentEndDistance = cumulativeDistancesDp[endPointIndex]
        val segmentLength = segmentEndDistance - segmentStartDistance
        val progress = if (segmentLength <= POINT_EPSILON_DP) {
            1f
        } else {
            ((stableDistance - segmentStartDistance) / segmentLength).coerceIn(0f, 1f)
        }

        destination.xDp = lerp(
            xCoordinatesDp[segmentIndex],
            xCoordinatesDp[endPointIndex],
            progress,
        )
        destination.yDp = lerp(
            yCoordinatesDp[segmentIndex],
            yCoordinatesDp[endPointIndex],
            progress,
        )
        destination.segmentAngleRadians = segmentAnglesRadians[segmentIndex]
        destination.reachedEnd = stableDistance >= totalDistanceDp - POINT_EPSILON_DP
    }

    /** 按 carout 的速度和最大 50ms 帧步长推进，并确保不会越过路径终点。 */
    fun advanceDistance(
        currentDistanceDp: Float,
        deltaSeconds: Float,
        kind: CaroutMotionKind,
    ): Float {
        val stableCurrent = stableDistance(currentDistanceDp)
        val frameDelta = CaroutMotionMath.cappedDeltaSeconds(deltaSeconds)
        return min(
            totalDistanceDp,
            stableCurrent + kind.speedDpPerSecond * frameDelta,
        )
    }

    private fun stableDistance(distanceDp: Float): Float = when {
        distanceDp.isNaN() || distanceDp <= 0f -> 0f
        distanceDp == Float.POSITIVE_INFINITY -> totalDistanceDp
        else -> distanceDp.coerceAtMost(totalDistanceDp)
    }

    /** 查找第一个累计距离大于等于目标距离的端点，折点本身仍沿用入射段方向。 */
    private fun endPointIndexFor(distanceDp: Float): Int {
        if (distanceDp <= 0f) return 1
        if (distanceDp >= totalDistanceDp) return cumulativeDistancesDp.lastIndex

        var low = 1
        var high = cumulativeDistancesDp.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulativeDistancesDp[middle] < distanceDp) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    companion object {
        fun from(points: List<CaroutMotionPoint>): CaroutPolylinePath {
            val stablePoints = ArrayList<CaroutMotionPoint>(points.size)
            points.forEach { point ->
                require(point.xDp.isFinite() && point.yDp.isFinite()) {
                    "Carout motion points must be finite"
                }
                val previous = stablePoints.lastOrNull()
                if (previous == null || previous.distanceTo(point) > POINT_EPSILON_DP) {
                    stablePoints += point
                }
            }
            require(stablePoints.size >= 2) {
                "Carout polyline requires at least two distinct points"
            }

            val pointCount = stablePoints.size
            val xCoordinates = FloatArray(pointCount)
            val yCoordinates = FloatArray(pointCount)
            val cumulativeDistances = FloatArray(pointCount)
            val segmentAngles = FloatArray(pointCount - 1)
            var totalDistance = 0f
            var index = 0
            while (index < pointCount) {
                val point = stablePoints[index]
                xCoordinates[index] = point.xDp
                yCoordinates[index] = point.yDp
                if (index > 0) {
                    val previous = stablePoints[index - 1]
                    val dx = point.xDp - previous.xDp
                    val dy = point.yDp - previous.yDp
                    totalDistance += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    cumulativeDistances[index] = totalDistance
                    // 源素材 0 弧度朝上，因此屏幕坐标的切线角需要再加 PI / 2。
                    segmentAngles[index - 1] =
                        (atan2(dy.toDouble(), dx.toDouble()) + PI / 2.0).toFloat()
                }
                index++
            }
            return CaroutPolylinePath(
                xCoordinatesDp = xCoordinates,
                yCoordinatesDp = yCoordinates,
                cumulativeDistancesDp = cumulativeDistances,
                segmentAnglesRadians = segmentAngles,
            )
        }
    }
}

/** carout 动画中与布局和领域规则无关的时间函数。 */
internal object CaroutMotionMath {
    const val DRIVE_SPEED_DP_PER_SECOND = 560f
    const val LEAVE_SPEED_DP_PER_SECOND = 480f
    const val MAX_FRAME_DELTA_SECONDS = 0.05f
    const val TURN_RESPONSE_PER_SECOND = 12f
    const val BUMP_OUT_SECONDS = 0.16f
    const val BUMP_BACK_SECONDS = 0.34f
    const val BLOCKER_SHAKE_SECONDS = 0.30f

    /** 防止页面卡顿后的单帧大跳；无效或负时间按 0 处理。 */
    fun cappedDeltaSeconds(deltaSeconds: Float): Float = when {
        deltaSeconds.isNaN() || deltaSeconds <= 0f -> 0f
        deltaSeconds == Float.POSITIVE_INFINITY -> MAX_FRAME_DELTA_SECONDS
        else -> deltaSeconds.coerceAtMost(MAX_FRAME_DELTA_SECONDS)
    }

    /**
     * 沿最短角差追随目标折线段方向。
     *
     * 公式与 carout 一致：`angle += shortestDiff * min(1, 12 * dt)`。dt 同样限制为 50ms。
     */
    fun turnToward(
        currentAngleRadians: Float,
        targetAngleRadians: Float,
        deltaSeconds: Float,
    ): Float {
        require(currentAngleRadians.isFinite() && targetAngleRadians.isFinite()) {
            "Carout motion angles must be finite"
        }
        val difference = shortestAngleDelta(currentAngleRadians, targetAngleRadians)
        val response = min(1f, cappedDeltaSeconds(deltaSeconds) * TURN_RESPONSE_PER_SECOND)
        return currentAngleRadians + difference * response
    }

    /** 返回从 current 到 target 的 [-PI, PI] 最短有向角差。 */
    fun shortestAngleDelta(
        currentAngleRadians: Float,
        targetAngleRadians: Float,
    ): Float {
        require(currentAngleRadians.isFinite() && targetAngleRadians.isFinite()) {
            "Carout motion angles must be finite"
        }
        var difference = targetAngleRadians - currentAngleRadians
        val fullTurn = (PI * 2.0).toFloat()
        val halfTurn = PI.toFloat()
        while (difference > halfTurn) difference -= fullTurn
        while (difference < -halfTurn) difference += fullTurn
        return difference
    }

    /**
     * 主动车辆的碰撞位移：0.16s 内按 p² 加速冲到接触点，随后 0.34s 阻尼震荡回原位。
     */
    fun bumpOffsetDp(
        elapsedSeconds: Float,
        freeDistanceDp: Float,
    ): Float {
        require(freeDistanceDp >= 0f && freeDistanceDp.isFinite()) {
            "Collision free distance must be a finite non-negative value"
        }
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return 0f
        if (elapsedSeconds < BUMP_OUT_SECONDS) {
            val progress = elapsedSeconds / BUMP_OUT_SECONDS
            return freeDistanceDp * progress * progress
        }
        val reboundProgress = (elapsedSeconds - BUMP_OUT_SECONDS) / BUMP_BACK_SECONDS
        if (reboundProgress >= 1f) return 0f
        return freeDistanceDp *
            exp((-5f * reboundProgress).toDouble()).toFloat() *
            cos((reboundProgress * PI * 2.2).toDouble()).toFloat()
    }

    /** 被撞车辆在接触后的 0.30s 衰减晃动，峰值基准为格子尺寸的 9%。 */
    fun blockerShakeOffsetDp(
        elapsedSeconds: Float,
        cellSizeDp: Float,
    ): Float {
        require(cellSizeDp >= 0f && cellSizeDp.isFinite()) {
            "Collision cell size must be a finite non-negative value"
        }
        if (!elapsedSeconds.isFinite()) return 0f
        val progress = (elapsedSeconds - BUMP_OUT_SECONDS) / BLOCKER_SHAKE_SECONDS
        if (progress < 0f || progress >= 1f) return 0f
        return cellSizeDp * 0.09f *
            exp((-4f * progress).toDouble()).toFloat() *
            sin((progress * PI * 4.0).toDouble()).toFloat()
    }
}

private fun CaroutMotionPoint.distanceTo(other: CaroutMotionPoint): Float =
    hypot((other.xDp - xDp).toDouble(), (other.yDp - yDp).toDouble()).toFloat()

private fun lerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress

private const val POINT_EPSILON_DP = 0.001f
