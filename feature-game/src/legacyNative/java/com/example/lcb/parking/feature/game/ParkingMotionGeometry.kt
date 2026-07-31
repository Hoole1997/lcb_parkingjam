package com.example.lcb.parking.feature.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** 纯 dp 坐标，便于 JVM 测试跨 AndroidView/Compose 的动画锚点。 */
internal data class ParkingMotionPoint(
    val x: Float,
    val y: Float,
)

/**
 * 路径上的纯表现采样结果。
 *
 * [rotationDegrees] 与车辆素材方向保持一致：0 度朝上、90 度朝右、180 度朝下。
 */
internal data class ParkingMotionPathSample(
    val point: ParkingMotionPoint,
    val rotationDegrees: Float,
    val reachedEnd: Boolean,
)

/**
 * 预先离散并累计长度的轻量路径。
 *
 * 构造阶段会把直角折点圆滑化；逐帧只执行一次二分查找和线性插值，不创建 Bitmap、Path 或
 * Android 对象。它只描述已经提交的表现轨迹，不参与碰撞、车位分配或胜负判定。
 */
internal class ParkingMotionPath private constructor(
    points: List<ParkingMotionPoint>,
) {
    val points: List<ParkingMotionPoint> = points.toList()
    private val cumulativeDistances = FloatArray(points.size)

    val totalDistanceDp: Float

    init {
        require(points.isNotEmpty()) { "Parking motion path cannot be empty" }
        var total = 0f
        var index = 1
        while (index < points.size) {
            total += points[index - 1].distanceTo(points[index])
            cumulativeDistances[index] = total
            index++
        }
        totalDistanceDp = total
    }

    /**
     * 按真实累计距离采样，因此不同长短的路径可以使用同一速度推进。
     * 当 [reduceMotion] 为 true 时直接返回终点，供系统关闭动画或生命周期快进使用。
     */
    fun sampleByDistance(
        distanceDp: Float,
        reduceMotion: Boolean = false,
    ): ParkingMotionPathSample {
        if (reduceMotion || totalDistanceDp <= POINT_EPSILON_DP) return terminalSample()
        val stableDistance = when {
            distanceDp.isNaN() -> 0f
            distanceDp == Float.POSITIVE_INFINITY -> totalDistanceDp
            distanceDp == Float.NEGATIVE_INFINITY -> 0f
            else -> distanceDp.coerceIn(0f, totalDistanceDp)
        }
        val point = pointAtDistance(stableDistance)
        val tangentWindow = min(
            DEFAULT_TANGENT_WINDOW_DP,
            (totalDistanceDp * TANGENT_WINDOW_PATH_RATIO).coerceAtLeast(MIN_TANGENT_WINDOW_DP),
        )
        val before = pointAtDistance((stableDistance - tangentWindow).coerceAtLeast(0f))
        val after = pointAtDistance((stableDistance + tangentWindow).coerceAtMost(totalDistanceDp))
        val rotation = rotationDegrees(before, after)
        return ParkingMotionPathSample(
            point = point,
            rotationDegrees = rotation,
            reachedEnd = stableDistance >= totalDistanceDp - POINT_EPSILON_DP,
        )
    }

    fun sampleByProgress(
        progress: Float,
        reduceMotion: Boolean = false,
    ): ParkingMotionPathSample {
        val stableProgress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        return sampleByDistance(totalDistanceDp * stableProgress, reduceMotion)
    }

    private fun terminalSample(): ParkingMotionPathSample {
        val end = points.last()
        val before = if (totalDistanceDp <= POINT_EPSILON_DP) {
            end
        } else {
            pointAtDistance((totalDistanceDp - DEFAULT_TANGENT_WINDOW_DP).coerceAtLeast(0f))
        }
        return ParkingMotionPathSample(
            point = end,
            rotationDegrees = rotationDegrees(before, end),
            reachedEnd = true,
        )
    }

    private fun pointAtDistance(distanceDp: Float): ParkingMotionPoint {
        if (points.size == 1 || distanceDp <= 0f) return points.first()
        if (distanceDp >= totalDistanceDp) return points.last()

        var low = 1
        var high = cumulativeDistances.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulativeDistances[middle] < distanceDp) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        val endIndex = low
        val startIndex = endIndex - 1
        val segmentStartDistance = cumulativeDistances[startIndex]
        val segmentLength = cumulativeDistances[endIndex] - segmentStartDistance
        if (segmentLength <= POINT_EPSILON_DP) return points[endIndex]
        val progress = ((distanceDp - segmentStartDistance) / segmentLength).coerceIn(0f, 1f)
        return points[startIndex].lerp(points[endIndex], progress)
    }

    companion object {
        fun rounded(
            waypoints: List<ParkingMotionPoint>,
            cornerRadiusDp: Float,
        ): ParkingMotionPath {
            require(waypoints.isNotEmpty()) { "Parking motion waypoints cannot be empty" }
            require(cornerRadiusDp >= 0f) { "Parking motion corner radius cannot be negative" }
            val stableWaypoints = waypoints.withoutAdjacentDuplicates()
            if (stableWaypoints.size <= 2 || cornerRadiusDp <= POINT_EPSILON_DP) {
                return ParkingMotionPath(stableWaypoints)
            }

            val roundedPoints = ArrayList<ParkingMotionPoint>(
                stableWaypoints.size * (CURVE_SUBDIVISIONS + 1),
            )
            roundedPoints += stableWaypoints.first()
            var index = 1
            while (index < stableWaypoints.lastIndex) {
                appendRoundedCorner(
                    destination = roundedPoints,
                    previous = stableWaypoints[index - 1],
                    corner = stableWaypoints[index],
                    next = stableWaypoints[index + 1],
                    requestedRadiusDp = cornerRadiusDp,
                )
                index++
            }
            roundedPoints += stableWaypoints.last()
            return ParkingMotionPath(roundedPoints.withoutAdjacentDuplicates())
        }

        private fun appendRoundedCorner(
            destination: MutableList<ParkingMotionPoint>,
            previous: ParkingMotionPoint,
            corner: ParkingMotionPoint,
            next: ParkingMotionPoint,
            requestedRadiusDp: Float,
        ) {
            val incomingLength = previous.distanceTo(corner)
            val outgoingLength = corner.distanceTo(next)
            if (incomingLength <= POINT_EPSILON_DP || outgoingLength <= POINT_EPSILON_DP) {
                destination.addIfDistinct(corner)
                return
            }

            val incomingX = (corner.x - previous.x) / incomingLength
            val incomingY = (corner.y - previous.y) / incomingLength
            val outgoingX = (next.x - corner.x) / outgoingLength
            val outgoingY = (next.y - corner.y) / outgoingLength
            val cross = incomingX * outgoingY - incomingY * outgoingX
            val dot = incomingX * outgoingX + incomingY * outgoingY
            if (abs(cross) <= COLLINEAR_EPSILON && dot > 0f) {
                destination.addIfDistinct(corner)
                return
            }

            val radius = min(
                requestedRadiusDp,
                min(incomingLength * MAX_CORNER_SEGMENT_RATIO, outgoingLength * MAX_CORNER_SEGMENT_RATIO),
            )
            if (radius <= POINT_EPSILON_DP) {
                destination.addIfDistinct(corner)
                return
            }

            val entry = ParkingMotionPoint(
                x = corner.x - incomingX * radius,
                y = corner.y - incomingY * radius,
            )
            val exit = ParkingMotionPoint(
                x = corner.x + outgoingX * radius,
                y = corner.y + outgoingY * radius,
            )
            destination.addIfDistinct(entry)
            var step = 1
            while (step <= CURVE_SUBDIVISIONS) {
                val progress = step.toFloat() / CURVE_SUBDIVISIONS.toFloat()
                destination.addIfDistinct(quadraticPoint(entry, corner, exit, progress))
                step++
            }
        }
    }
}

internal object ParkingMotionGeometry {
    fun boardTop(layout: GamePlayLayoutSpec): Float {
        val orderOffset = if (layout.orderIndicatorHeightDp > 0f) {
            layout.verticalGapDp + layout.orderIndicatorHeightDp
        } else {
            0f
        }
        return layout.hudHeightDp + orderOffset + layout.verticalGapDp
    }

    fun boardVehicleCenter(
        layout: GamePlayLayoutSpec,
        vehicle: VehicleRenderModel,
    ): ParkingMotionPoint {
        val boardLeft = (layout.contentWidthDp - layout.boardWidthDp) / 2f
        return ParkingMotionPoint(
            x = boardLeft + (vehicle.column + vehicle.widthCells / 2f) * layout.cellSizeDp,
            y = boardTop(layout) + (vehicle.row + vehicle.heightCells / 2f) * layout.cellSizeDp,
        )
    }

    fun boardExitCenter(
        layout: GamePlayLayoutSpec,
        vehicle: VehicleRenderModel,
    ): ParkingMotionPoint {
        val boardLeft = (layout.contentWidthDp - layout.boardWidthDp) / 2f
        val boardTop = boardTop(layout)
        val original = boardVehicleCenter(layout, vehicle)
        val halfLength = maxOf(vehicle.widthCells, vehicle.heightCells) * layout.cellSizeDp / 2f
        return when (vehicle.direction) {
            VehicleDirection.UP -> ParkingMotionPoint(original.x, boardTop - halfLength)
            VehicleDirection.RIGHT -> ParkingMotionPoint(
                boardLeft + layout.boardWidthDp + halfLength,
                original.y,
            )
            VehicleDirection.DOWN -> ParkingMotionPoint(
                original.x,
                boardTop + layout.boardHeightDp + halfLength,
            )
            VehicleDirection.LEFT -> ParkingMotionPoint(boardLeft - halfLength, original.y)
        }
    }

    fun waitingSlotCenter(layout: GamePlayLayoutSpec, slotIndex: Int): ParkingMotionPoint {
        require(slotIndex >= 0 && slotIndex < layout.slotColumns * layout.slotRows) {
            "Parking animation slot is outside the visible layout"
        }
        val column = slotIndex % layout.slotColumns
        val row = slotIndex / layout.slotColumns
        val parkingLeft = (layout.contentWidthDp - layout.parkingContentWidthDp) / 2f
        val parkingTop = parkingTop(layout)
        return ParkingMotionPoint(
            x = parkingLeft + column * (layout.slotWidthDp + layout.parkingSlotGapDp) +
                layout.slotWidthDp / 2f,
            y = parkingTop + row * (layout.slotHeightDp + layout.parkingSlotGapDp) +
                layout.slotHeightDp / 2f,
        )
    }

    fun currentOrderCenter(layout: GamePlayLayoutSpec): ParkingMotionPoint = ParkingMotionPoint(
        x = layout.contentWidthDp / 2f,
        y = layout.hudHeightDp + layout.verticalGapDp + layout.orderIndicatorHeightDp / 2f,
    )

    /**
     * 已完全驶出棋盘的车辆沿屏幕侧廊进入底部停车区，再从车位下方倒入目标槽。
     *
     * 侧廊依据车辆驶出方向选择；上下驶出的车辆选择距离其车道最近的一侧。所有锚点只依赖
     * 当前布局和领域已提交的目标槽位，屏幕像素从不回流到规则层。
     */
    fun boardToWaitingSlotPath(
        layout: GamePlayLayoutSpec,
        vehicle: VehicleRenderModel,
        slotIndex: Int,
    ): ParkingMotionPath {
        val start = boardExitCenter(layout, vehicle)
        val target = waitingSlotCenter(layout, slotIndex)
        val radius = min(
            layout.cellSizeDp * DEFAULT_CORNER_RADIUS_CELL_RATIO,
            layout.slotWidthDp * DEFAULT_CORNER_RADIUS_SLOT_RATIO,
        )
        val corridorInset = maxOf(
            MIN_SIDE_CORRIDOR_INSET_DP,
            layout.cellSizeDp * SIDE_CORRIDOR_CELL_RATIO,
            radius + MIN_CORRIDOR_CURVE_INSET_DP,
        ).coerceAtMost(layout.contentWidthDp / 3f)
        val useLeftCorridor = when (vehicle.direction) {
            VehicleDirection.LEFT -> true
            VehicleDirection.RIGHT -> false
            VehicleDirection.UP,
            VehicleDirection.DOWN,
            -> start.x <= layout.contentWidthDp / 2f
        }
        val corridorX = if (useLeftCorridor) {
            corridorInset
        } else {
            layout.contentWidthDp - corridorInset
        }
        val slotApproachY = waitingSlotApproachY(layout, slotIndex)
        val leadDistance = maxOf(
            layout.cellSizeDp * EXIT_LEAD_CELL_RATIO,
            radius * EXIT_LEAD_RADIUS_RATIO,
        )
        val leadOut = ParkingMotionPoint(
            x = start.x + vehicle.direction.dx * leadDistance,
            y = start.y + vehicle.direction.dy * leadDistance,
        )
        val waypoints = buildList(7) {
            add(start)
            // 交接后先保持棋盘中的离场朝向，禁止左右车辆在第一帧掉头返回屏内。
            add(leadOut)
            if (vehicle.direction == VehicleDirection.LEFT ||
                vehicle.direction == VehicleDirection.RIGHT
            ) {
                // 水平离场车辆在屏外完成第一次转弯，再从底部接近槽位。
                add(ParkingMotionPoint(leadOut.x, slotApproachY))
            } else {
                add(ParkingMotionPoint(corridorX, leadOut.y))
            }
            add(ParkingMotionPoint(corridorX, slotApproachY))
            add(ParkingMotionPoint(target.x, slotApproachY))
            add(target)
        }
        return ParkingMotionPath.rounded(waypoints, radius)
    }

    private fun parkingTop(layout: GamePlayLayoutSpec): Float =
        boardTop(layout) + layout.boardHeightDp + layout.verticalGapDp

    /** 让最后一段由下向上进入车槽，终点车辆自然保持素材的 0 度朝向。 */
    private fun waitingSlotApproachY(layout: GamePlayLayoutSpec, slotIndex: Int): Float {
        val row = slotIndex / layout.slotColumns
        val slotCenter = waitingSlotCenter(layout, slotIndex)
        val rowBottom = parkingTop(layout) +
            row * (layout.slotHeightDp + layout.parkingSlotGapDp) +
            layout.slotHeightDp
        return maxOf(
            slotCenter.y + layout.slotHeightDp * SLOT_APPROACH_HEIGHT_RATIO,
            rowBottom - MIN_SLOT_APPROACH_INSET_DP,
        )
    }
}

private fun ParkingMotionPoint.distanceTo(other: ParkingMotionPoint): Float =
    hypot((other.x - x).toDouble(), (other.y - y).toDouble()).toFloat()

private fun ParkingMotionPoint.lerp(other: ParkingMotionPoint, progress: Float): ParkingMotionPoint =
    ParkingMotionPoint(
        x = x + (other.x - x) * progress,
        y = y + (other.y - y) * progress,
    )

private fun List<ParkingMotionPoint>.withoutAdjacentDuplicates(): List<ParkingMotionPoint> {
    if (isEmpty()) return emptyList()
    val result = ArrayList<ParkingMotionPoint>(size)
    var index = 0
    while (index < size) {
        result.addIfDistinct(this[index])
        index++
    }
    return result
}

private fun MutableList<ParkingMotionPoint>.addIfDistinct(point: ParkingMotionPoint) {
    if (isEmpty() || last().distanceTo(point) > POINT_EPSILON_DP) add(point)
}

private fun quadraticPoint(
    start: ParkingMotionPoint,
    control: ParkingMotionPoint,
    end: ParkingMotionPoint,
    progress: Float,
): ParkingMotionPoint {
    val inverse = 1f - progress
    return ParkingMotionPoint(
        x = inverse * inverse * start.x + 2f * inverse * progress * control.x +
            progress * progress * end.x,
        y = inverse * inverse * start.y + 2f * inverse * progress * control.y +
            progress * progress * end.y,
    )
}

private fun rotationDegrees(from: ParkingMotionPoint, to: ParkingMotionPoint): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (abs(dx) <= POINT_EPSILON_DP && abs(dy) <= POINT_EPSILON_DP) return 0f
    var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    while (degrees > 180f) degrees -= 360f
    while (degrees <= -180f) degrees += 360f
    return degrees
}

private val VehicleDirection.dx: Float
    get() = when (this) {
        VehicleDirection.RIGHT -> 1f
        VehicleDirection.LEFT -> -1f
        VehicleDirection.UP,
        VehicleDirection.DOWN,
        -> 0f
    }

private val VehicleDirection.dy: Float
    get() = when (this) {
        VehicleDirection.DOWN -> 1f
        VehicleDirection.UP -> -1f
        VehicleDirection.RIGHT,
        VehicleDirection.LEFT,
        -> 0f
    }

private const val CURVE_SUBDIVISIONS = 10
private const val POINT_EPSILON_DP = 0.001f
private const val COLLINEAR_EPSILON = 0.001f
private const val MAX_CORNER_SEGMENT_RATIO = 0.42f
private const val DEFAULT_TANGENT_WINDOW_DP = 5f
private const val MIN_TANGENT_WINDOW_DP = 1.5f
private const val TANGENT_WINDOW_PATH_RATIO = 0.012f
private const val MIN_SIDE_CORRIDOR_INSET_DP = 10f
private const val MIN_CORRIDOR_CURVE_INSET_DP = 1f
private const val SIDE_CORRIDOR_CELL_RATIO = 0.42f
private const val DEFAULT_CORNER_RADIUS_CELL_RATIO = 0.55f
private const val DEFAULT_CORNER_RADIUS_SLOT_RATIO = 0.48f
private const val EXIT_LEAD_CELL_RATIO = 0.62f
private const val EXIT_LEAD_RADIUS_RATIO = 1.35f
private const val SLOT_APPROACH_HEIGHT_RATIO = 0.35f
private const val MIN_SLOT_APPROACH_INSET_DP = 2f
