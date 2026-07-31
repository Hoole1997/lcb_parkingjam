package com.example.lcb.parking.feature.game

/** 成功离场车辆在棋盘动画结束后的视觉目的地；不参与停车规则判定。 */
sealed interface ParkingMotionDestination {
    data class WaitingSlot(val slotIndex: Int) : ParkingMotionDestination {
        init {
            require(slotIndex >= 0) { "Parking motion slot index cannot be negative" }
        }
    }

    data object CurrentOrder : ParkingMotionDestination
    data object Bypass : ParkingMotionDestination
}

/** 从临停车位自动交付到新订单的一辆车，列表顺序就是领域层确认的调度顺序。 */
data class ParkingDispatchMotion(
    val vehicleId: String,
    val fromSlotIndex: Int,
    val artVariant: VehicleArtVariant,
    val lengthCells: Int,
) {
    init {
        require(vehicleId.isNotBlank()) { "Dispatch vehicle id cannot be blank" }
        require(fromSlotIndex >= 0) { "Dispatch slot index cannot be negative" }
        require(lengthCells > 0) { "Dispatch vehicle length must be positive" }
    }
}

/**
 * 一次成功离场对应的完整跨层动画说明。
 *
 * 这里仅保存已经提交的目的地与车辆外观。动画结束回调只确认表现完成，绝不反向修改订单、
 * 容量或车辆归属。
 */
data class ParkingMotionSpec(
    val arrivingVehicle: VehicleRenderModel,
    val destination: ParkingMotionDestination,
    val dispatches: List<ParkingDispatchMotion> = emptyList(),
) {
    val presentationDurationMillis: Long
        get() = ParkingMotionTiming.presentationDurationMillis(this)
}

/** 统一约束 Canvas 棋盘调度器与 Compose 停车动画的时长，避免终态弹层抢在动画前出现。 */
internal object ParkingMotionTiming {
    /** 完整侧廊路径保持在半秒内，既能看清真实车辆，又不拖慢连续操作。 */
    const val WAITING_SLOT_ARRIVAL_MILLIS = 460L
    const val ORDER_ARRIVAL_MILLIS = 320L
    const val DISPATCH_TO_ORDER_MILLIS = 240L

    fun presentationDurationMillis(spec: ParkingMotionSpec): Long {
        val arrivalDuration = when (spec.destination) {
            is ParkingMotionDestination.WaitingSlot -> WAITING_SLOT_ARRIVAL_MILLIS
            ParkingMotionDestination.CurrentOrder -> ORDER_ARRIVAL_MILLIS
            ParkingMotionDestination.Bypass -> 0L
        }
        return arrivalDuration + spec.dispatches.size * DISPATCH_TO_ORDER_MILLIS
    }
}
