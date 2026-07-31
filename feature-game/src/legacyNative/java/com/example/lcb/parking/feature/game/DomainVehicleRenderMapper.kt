package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition

/**
 * 领域车辆到 Android 轻量绘制模型的唯一映射入口。
 *
 * 棋盘稳定投影与跨层停车动画共用这一映射，避免两套方向、尺寸或颜色规则逐渐漂移。
 */
internal fun VehicleDefinition.toRenderModel(
    visualState: VehicleVisualState,
): VehicleRenderModel {
    val vertical = direction == Direction.NORTH || direction == Direction.SOUTH
    val artVariant = color.toArtVariant()
    return VehicleRenderModel(
        id = id.value,
        row = anchor.y,
        column = anchor.x,
        widthCells = if (vertical) 1 else length,
        heightCells = if (vertical) length else 1,
        direction = direction.toVehicleDirection(),
        visualState = visualState,
        artVariant = artVariant,
        color = artVariant.argb,
    )
}

internal fun VehicleColor.toArtVariant(): VehicleArtVariant = when (this) {
    VehicleColor.CORAL -> VehicleArtVariant.CORAL
    VehicleColor.BLUE -> VehicleArtVariant.BLUE
    VehicleColor.YELLOW -> VehicleArtVariant.YELLOW
    VehicleColor.PURPLE -> VehicleArtVariant.PURPLE
    VehicleColor.MINT -> VehicleArtVariant.MINT
    VehicleColor.RED -> VehicleArtVariant.RED
}

private fun Direction.toVehicleDirection(): VehicleDirection = when (this) {
    Direction.NORTH -> VehicleDirection.UP
    Direction.EAST -> VehicleDirection.RIGHT
    Direction.SOUTH -> VehicleDirection.DOWN
    Direction.WEST -> VehicleDirection.LEFT
}
