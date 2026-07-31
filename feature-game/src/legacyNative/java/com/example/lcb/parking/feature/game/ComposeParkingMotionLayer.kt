package com.example.lcb.parking.feature.game

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min
import kotlin.math.pow

/** 覆盖 HUD、棋盘与车槽的透明绘制层；不消费触摸，也不创建独立 Bitmap。 */
@Composable
internal fun ComposeParkingMotionLayer(
    controller: ParkingMotionController,
    layout: GamePlayLayoutSpec,
    vehicleImages: Map<ParkingVehicleArtKey, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    val motion = controller.activeMotion
    // 路径只随布局、车辆和目标槽变化；进度重组时复用，避免每帧创建点集。
    val waitingPath = remember(
        layout,
        motion?.stage,
        motion?.slotIndex,
        // 相同 vehicleId 会在不同关卡复用；完整模型可防止方向或长度改变后命中旧路径。
        motion?.boardVehicle,
    ) {
        val stableMotion = motion
        if (stableMotion?.stage == ParkingMotionStage.BOARD_TO_WAITING_SLOT) {
            ParkingMotionGeometry.boardToWaitingSlotPath(
                layout = layout,
                vehicle = checkNotNull(stableMotion.boardVehicle),
                slotIndex = checkNotNull(stableMotion.slotIndex),
            )
        } else {
            null
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        motion ?: return@Canvas
        val progress = controller.activeProgress.coerceIn(0f, 1f)
        val start = when (motion.stage) {
            ParkingMotionStage.BOARD_TO_WAITING_SLOT,
            ParkingMotionStage.BOARD_TO_ORDER,
            -> ParkingMotionGeometry.boardExitCenter(
                layout = layout,
                vehicle = checkNotNull(motion.boardVehicle),
            )
            ParkingMotionStage.WAITING_SLOT_TO_ORDER -> ParkingMotionGeometry.waitingSlotCenter(
                layout = layout,
                slotIndex = checkNotNull(motion.slotIndex),
            )
        }
        val end = when (motion.stage) {
            ParkingMotionStage.BOARD_TO_WAITING_SLOT -> ParkingMotionGeometry.waitingSlotCenter(
                layout = layout,
                slotIndex = checkNotNull(motion.slotIndex),
            )
            ParkingMotionStage.BOARD_TO_ORDER,
            ParkingMotionStage.WAITING_SLOT_TO_ORDER,
            -> ParkingMotionGeometry.currentOrderCenter(layout)
        }
        val pathSample = waitingPath?.sampleByProgress(progress)
        val center = pathSample?.point ?: quadraticPoint(
            start = start,
            control = controlPoint(start, end, motion.stage, layout),
            end = end,
            progress = progress,
        )
        val densityScale = density
        val image = vehicleImages[motion.artKey]
        val aspectRatio = image?.let { bitmap ->
            if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width.toFloat() else null
        } ?: motion.artKey.length.fallbackAspectRatio

        val startsOnBoard = motion.boardVehicle != null
        val requestedStartShortSide = if (startsOnBoard) {
            (layout.cellSizeDp - PARKING_VEHICLE_CELL_INSET_DP * 2f).coerceAtLeast(1f)
        } else {
            layout.slotWidthDp * 0.58f
        }
        val startShortSide = if (startsOnBoard) {
            requestedStartShortSide
        } else {
            min(requestedStartShortSide, layout.slotHeightDp * 0.78f / aspectRatio)
        }
        val entersWaitingSlot = motion.stage == ParkingMotionStage.BOARD_TO_WAITING_SLOT
        val requestedEndShortSide = if (entersWaitingSlot) {
            layout.slotWidthDp * 0.58f
        } else {
            orderTokenHeight(layout)
        }
        val endShortSide = if (entersWaitingSlot) {
            min(requestedEndShortSide, layout.slotHeightDp * 0.78f / aspectRatio)
        } else {
            requestedEndShortSide
        }
        val startRotation = motion.boardVehicle?.direction?.rotationDegrees ?: 0f
        val endRotation = if (entersWaitingSlot) 0f else 90f
        val rotation = pathSample?.rotationDegrees
            ?: lerpAngle(startRotation, endRotation, progress)
        val alpha = if (entersWaitingSlot) {
            1f
        } else {
            ((1f - progress) / ORDER_FADE_WINDOW).coerceIn(0f, 1f).pow(0.55f)
        }

        drawParkingVehicle(
            center = Offset(center.x * densityScale, center.y * densityScale),
            shortSide = lerp(startShortSide, endShortSide, progress) * densityScale,
            rotationDegrees = rotation,
            image = image,
            color = Color(motion.artKey.variant.argb),
            fallbackAspectRatio = motion.artKey.length.fallbackAspectRatio,
            alpha = alpha,
        )
    }
}

private fun controlPoint(
    start: ParkingMotionPoint,
    end: ParkingMotionPoint,
    stage: ParkingMotionStage,
    layout: GamePlayLayoutSpec,
): ParkingMotionPoint = when (stage) {
    ParkingMotionStage.BOARD_TO_WAITING_SLOT -> ParkingMotionPoint(
        x = start.x,
        y = end.y - layout.slotHeightDp * 1.15f,
    )
    ParkingMotionStage.BOARD_TO_ORDER -> ParkingMotionPoint(
        x = end.x,
        y = (start.y + end.y) / 2f,
    )
    ParkingMotionStage.WAITING_SLOT_TO_ORDER -> ParkingMotionPoint(
        x = start.x,
        y = (start.y + end.y) / 2f,
    )
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

private fun orderTokenHeight(layout: GamePlayLayoutSpec): Float = when (layout.mode) {
    GamePlayLayoutMode.COMPACT -> 17f
    GamePlayLayoutMode.STANDARD -> 20f
    GamePlayLayoutMode.TABLET -> 22f
}

private fun lerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress

private fun lerpAngle(start: Float, end: Float, progress: Float): Float {
    var delta = (end - start) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return start + delta * progress
}

private val VehicleDirection.rotationDegrees: Float
    get() = when (this) {
        VehicleDirection.UP -> 0f
        VehicleDirection.RIGHT -> 90f
        VehicleDirection.DOWN -> 180f
        VehicleDirection.LEFT -> -90f
    }

private const val ORDER_FADE_WINDOW = 0.30f
