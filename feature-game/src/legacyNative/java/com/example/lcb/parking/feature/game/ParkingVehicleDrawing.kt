package com.example.lcb.parking.feature.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.min

/**
 * 车槽与跨层动画共用棋盘的真实车辆 Bitmap。尺寸只由一个短边推导，始终保留素材宽高比；
 * 资源尚未准备好时才短暂使用固定 1:2 的 Canvas 降级车。
 */
internal fun DrawScope.drawParkingVehicle(
    center: Offset,
    shortSide: Float,
    maxLongSide: Float = Float.POSITIVE_INFINITY,
    rotationDegrees: Float,
    image: ImageBitmap?,
    color: Color,
    fallbackAspectRatio: Float = FALLBACK_ASPECT_RATIO,
    alpha: Float = 1f,
) {
    if (shortSide <= 0f || alpha <= 0f) return
    val aspectRatio = image?.let { bitmap ->
        if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width.toFloat() else null
    } ?: fallbackAspectRatio
    val vehicleWidth = min(shortSide, maxLongSide / aspectRatio)
    val vehicleHeight = vehicleWidth * aspectRatio
    rotate(degrees = rotationDegrees, pivot = center) {
        val topLeft = Offset(
            x = center.x - vehicleWidth / 2f,
            y = center.y - vehicleHeight / 2f,
        )
        if (image != null) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(
                    vehicleWidth.roundToInt().coerceAtLeast(1),
                    vehicleHeight.roundToInt().coerceAtLeast(1),
                ),
                alpha = alpha,
                filterQuality = FilterQuality.High,
            )
            return@rotate
        }

        val size = Size(vehicleWidth, vehicleHeight)
        val corner = CornerRadius(vehicleWidth * 0.28f)
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.18f * alpha),
            topLeft = topLeft + Offset(0f, vehicleHeight * 0.055f),
            size = size,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
        )
        val windowColor = ParkingVehicleWindow.copy(alpha = alpha)
        drawRoundRect(
            color = windowColor,
            topLeft = Offset(
                topLeft.x + vehicleWidth * 0.16f,
                topLeft.y + vehicleHeight * 0.15f,
            ),
            size = Size(vehicleWidth * 0.68f, vehicleHeight * 0.22f),
            cornerRadius = CornerRadius(vehicleWidth * 0.12f),
        )
        drawRoundRect(
            color = windowColor.copy(alpha = alpha * 0.78f),
            topLeft = Offset(
                topLeft.x + vehicleWidth * 0.16f,
                topLeft.y + vehicleHeight * 0.63f,
            ),
            size = Size(vehicleWidth * 0.68f, vehicleHeight * 0.16f),
            cornerRadius = CornerRadius(vehicleWidth * 0.10f),
        )
    }
}

internal val ParkingVehicleWindow = Color(0xFF284044)
/** 棋盘与跨层动画共享的单边车辆留白，保证交接帧尺寸连续。 */
internal const val PARKING_VEHICLE_CELL_INSET_DP = 2.5f
private const val FALLBACK_ASPECT_RATIO = 2f
