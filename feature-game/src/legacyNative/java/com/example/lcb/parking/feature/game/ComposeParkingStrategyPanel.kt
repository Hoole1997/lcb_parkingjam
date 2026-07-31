package com.example.lcb.parking.feature.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.example.lcb.parking.feature.R

/**
 * 用同色小车位表达当前订单数量，不重复显示颜色名、分数或后续订单文本。
 * 视觉层只投影领域状态；订单推进仍完全由 game-domain 决定。
 */
@Composable
internal fun ActiveOrderProgress(
    state: ParkingLotUiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val order = state.currentOrder
    val accessibilityText = order?.let {
        stringResource(
            R.string.feature_game_order_accessibility_format,
            colorName(it.color),
            it.completedCount,
            it.requiredCount,
        )
    } ?: stringResource(R.string.feature_game_all_orders_complete)

    Box(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = accessibilityText
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        if (order != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(order.requiredCount) { index ->
                    OrderVehicleToken(
                        color = Color(order.color.argb),
                        filled = index < order.completedCount,
                        modifier = Modifier.size(
                            width = if (compact) 27.dp else 31.dp,
                            height = if (compact) 17.dp else 20.dp,
                        ),
                    )
                }
            }
        }
    }
}

/** 小车轮廓本身表示数量；不使用三角形或方向箭头。 */
@Composable
private fun OrderVehicleToken(
    color: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = maxOf(1.5f, size.minDimension * 0.09f)
        val bodyTopLeft = Offset(stroke, stroke)
        val bodySize = Size(size.width - stroke * 2f, size.height - stroke * 2f)
        val bodyCorner = CornerRadius(size.height * 0.28f)
        if (filled) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.15f),
                topLeft = bodyTopLeft + Offset(0f, size.height * 0.10f),
                size = bodySize,
                cornerRadius = bodyCorner,
            )
        }
        drawRoundRect(
            color = if (filled) color else color.copy(alpha = 0.10f),
            topLeft = bodyTopLeft,
            size = bodySize,
            cornerRadius = bodyCorner,
        )
        drawRoundRect(
            color = color.copy(alpha = if (filled) 0.95f else 0.60f),
            topLeft = bodyTopLeft,
            size = bodySize,
            cornerRadius = bodyCorner,
            style = Stroke(stroke),
        )
        val detailColor = if (filled) ParkingWindow else color.copy(alpha = 0.46f)
        val wheelRadius = size.height * 0.075f
        val frontWheelX = size.width * 0.27f
        val rearWheelX = size.width * 0.73f
        val topWheelY = size.height * 0.13f
        val bottomWheelY = size.height * 0.87f
        drawCircle(detailColor, wheelRadius, Offset(frontWheelX, topWheelY))
        drawCircle(detailColor, wheelRadius, Offset(frontWheelX, bottomWheelY))
        drawCircle(detailColor, wheelRadius, Offset(rearWheelX, topWheelY))
        drawCircle(detailColor, wheelRadius, Offset(rearWheelX, bottomWheelY))
        // 前后窗让图标被识别为小车，但不承担或暗示行驶方向。
        drawRoundRect(
            color = detailColor,
            topLeft = Offset(size.width * 0.25f, size.height * 0.29f),
            size = Size(size.width * 0.17f, size.height * 0.42f),
            cornerRadius = CornerRadius(size.height * 0.07f),
        )
        drawRoundRect(
            color = detailColor,
            topLeft = Offset(size.width * 0.58f, size.height * 0.29f),
            size = Size(size.width * 0.17f, size.height * 0.42f),
            cornerRadius = CornerRadius(size.height * 0.07f),
        )
    }
}

/**
 * 全量展示临时车位，但不再绘制标题、占用比例、警告文案或包裹整组车位的卡片。
 * 每个车位独立存在，未来容量增加时由布局策略自然换行。
 */
@Composable
internal fun TemporaryParkingLot(
    state: ParkingLotUiState,
    layout: GamePlayLayoutSpec,
    modifier: Modifier = Modifier,
    motionController: ParkingMotionController? = null,
    vehicleImages: Map<ParkingVehicleArtKey, ImageBitmap> = emptyMap(),
) {
    if (state.capacity == 0 || layout.slotColumns == 0) return

    val colorSummary = VehicleArtVariant.entries.mapNotNull { color ->
        val count = state.slots.count { slot -> slot.color == color }
        if (count == 0) null else {
            stringResource(
                R.string.feature_game_parking_color_count_format,
                colorName(color),
                count,
            )
        }
    }.ifEmpty {
        listOf(stringResource(R.string.feature_game_parking_empty_accessibility))
    }.joinToString("，")
    val parkingDescription = stringResource(
        R.string.feature_game_parking_accessibility_format,
        state.occupiedCount,
        state.capacity,
        colorSummary,
    )

    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = parkingDescription
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(
            layout.parkingSlotGapDp.dp,
            Alignment.CenterVertically,
        ),
    ) {
        repeat(layout.slotRows) { rowIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    layout.parkingSlotGapDp.dp,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(layout.slotColumns) { columnIndex ->
                    val slotIndex = rowIndex * layout.slotColumns + columnIndex
                    if (slotIndex < state.capacity) {
                        ParkingSlot(
                            // null 既可能表示空位，也可能是动画账本刻意压制“尚未抵达”的车辆。
                            presentedArtKey = if (motionController == null) {
                                state.slots[slotIndex].parkingArtKey
                            } else {
                                motionController.presentedArtKey(state.slots[slotIndex])
                            },
                            vehicleImages = vehicleImages,
                            modifier = Modifier.size(
                                width = layout.slotWidthDp.dp,
                                height = layout.slotHeightDp.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 单个独立车位；占用车辆不叠加方向箭头或颜色形状标记。 */
@Composable
private fun ParkingSlot(
    presentedArtKey: ParkingVehicleArtKey?,
    vehicleImages: Map<ParkingVehicleArtKey, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawParkingBay()
            val artKey = presentedArtKey ?: return@Canvas
            drawWaitingVehicle(
                artKey = artKey,
                image = vehicleImages[artKey],
            )
        }
    }
}

private fun DrawScope.drawParkingBay() {
    val shadowOffset = size.minDimension * 0.055f
    val inset = size.minDimension * 0.06f
    val bayTopLeft = Offset(inset, inset)
    val baySize = Size(size.width - inset * 2f, size.height - inset * 2f - shadowOffset)
    val corner = CornerRadius(size.minDimension * 0.19f)
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.13f),
        topLeft = bayTopLeft + Offset(0f, shadowOffset),
        size = baySize,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = ParkingBayRim,
        topLeft = bayTopLeft,
        size = baySize,
        cornerRadius = corner,
    )
    val innerInset = size.minDimension * 0.075f
    drawRoundRect(
        color = ParkingSlotSurface,
        topLeft = bayTopLeft + Offset(innerInset, innerInset),
        size = Size(baySize.width - innerInset * 2f, baySize.height - innerInset * 2f),
        cornerRadius = CornerRadius(size.minDimension * 0.13f),
    )
}

private fun DrawScope.drawWaitingVehicle(
    artKey: ParkingVehicleArtKey,
    image: ImageBitmap?,
) {
    drawParkingVehicle(
        center = center,
        shortSide = size.width * 0.58f,
        maxLongSide = size.height * 0.78f,
        rotationDegrees = 0f,
        image = image,
        color = Color(artKey.variant.argb),
        fallbackAspectRatio = artKey.length.fallbackAspectRatio,
    )
}

@Composable
private fun colorName(color: VehicleArtVariant): String = stringResource(
    when (color) {
        VehicleArtVariant.CORAL -> R.string.feature_game_color_coral
        VehicleArtVariant.BLUE -> R.string.feature_game_color_blue
        VehicleArtVariant.YELLOW -> R.string.feature_game_color_yellow
        VehicleArtVariant.PURPLE -> R.string.feature_game_color_purple
        VehicleArtVariant.MINT -> R.string.feature_game_color_mint
        VehicleArtVariant.RED -> R.string.feature_game_color_red
    },
)

private val ParkingBayRim = Color(0xFFFFF5E3)
private val ParkingSlotSurface = Color(0xFFD8D9C5)
private val ParkingWindow = ParkingVehicleWindow
