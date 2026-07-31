package com.example.lcb.parking.feature.game

/**
 * 车辆位图的长度档位。
 *
 * 玩法目前使用两格短车和三格长车；将更长的特殊车辆归入 [LONG]，可以在不扩充缓存维度的
 * 前提下继续复用长车素材。
 */
internal enum class ParkingVehicleArtLength {
    SHORT,
    LONG,
    ;

    companion object {
        fun fromCellLength(cellLength: Int): ParkingVehicleArtLength {
            require(cellLength > 0) { "Vehicle art length must be positive" }
            return if (cellLength >= LONG_VEHICLE_MIN_CELLS) LONG else SHORT
        }

        private const val LONG_VEHICLE_MIN_CELLS = 3
    }
}

/** 素材尚未解码时仍按车型比例绘制，避免长车在首帧退化成两格小车。 */
internal val ParkingVehicleArtLength.fallbackAspectRatio: Float
    get() = when (this) {
        ParkingVehicleArtLength.SHORT -> 2f
        ParkingVehicleArtLength.LONG -> 3f
    }

/** 位图缓存使用的完整语义键；颜色相同但车身长度不同的车辆可以选择不同素材。 */
internal data class ParkingVehicleArtKey(
    val variant: VehicleArtVariant,
    val length: ParkingVehicleArtLength,
) {
    companion object {
        /** 固定且很小的键集合，供进程级仓库一次性准备，避免绘制阶段按需解码。 */
        val all: List<ParkingVehicleArtKey> = buildList(
            VehicleArtVariant.entries.size * ParkingVehicleArtLength.entries.size,
        ) {
            VehicleArtVariant.entries.forEach { variant ->
                ParkingVehicleArtLength.entries.forEach { length ->
                    add(ParkingVehicleArtKey(variant, length))
                }
            }
        }
    }
}

/**
 * [VehicleRenderModel] 的宽高已经携带领域 length；统一在这里还原，避免各渲染器分别猜测。
 */
internal val VehicleRenderModel.lengthCells: Int
    get() = maxOf(widthCells, heightCells)

/** 车辆模型到美术缓存键的唯一映射入口。 */
internal val VehicleRenderModel.parkingArtKey: ParkingVehicleArtKey
    get() = ParkingVehicleArtKey(
        variant = artVariant,
        length = ParkingVehicleArtLength.fromCellLength(lengthCells),
    )

/** 稳定停车位状态到素材键的唯一映射；空位没有车辆素材。 */
internal val ParkingSlotUiState.parkingArtKey: ParkingVehicleArtKey?
    get() {
        val variant = color ?: return null
        val cellLength = lengthCells ?: return null
        return ParkingVehicleArtKey(
            variant = variant,
            length = ParkingVehicleArtLength.fromCellLength(cellLength),
        )
    }

/** 调度动画在权威车位已经清空后，仍可依靠自身快照保持正确车型。 */
internal val ParkingDispatchMotion.parkingArtKey: ParkingVehicleArtKey
    get() = ParkingVehicleArtKey(
        variant = artVariant,
        length = ParkingVehicleArtLength.fromCellLength(lengthCells),
    )
