package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.WaitingVehicle

/** 成功离开棋盘的车辆首先到达订单或临时停车位。 */
sealed interface ParkingDestination {
    data class Order(val orderId: OrderId) : ParkingDestination
    data class Slot(val slotIndex: Int) : ParkingDestination
    /** All orders are done; a later objective-required vehicle may leave without occupying a slot. */
    data object Bypass : ParkingDestination
}

/** 候车车辆因订单推进而自动离开停车位。 */
data class ParkingDispatch(
    val vehicleId: VehicleId,
    val fromSlotIndex: Int,
    val orderId: OrderId,
)

/** 一次路由事务中实际完成的订单车辆，包含直达车辆和自动调度车辆。 */
data class ParkingFulfillment(
    val vehicleId: VehicleId,
    val orderId: OrderId,
    val color: VehicleColor,
    val fromSlotIndex: Int?,
)

sealed interface ParkingRouteResult {
    data class Routed(
        val snapshot: ParkingLotSnapshot,
        val destination: ParkingDestination,
        val dispatches: List<ParkingDispatch>,
        val fulfillments: List<ParkingFulfillment>,
        val completedOrderIds: List<OrderId>,
    ) : ParkingRouteResult

    data object Full : ParkingRouteResult
}

/**
 * 顺序颜色订单的纯规则组件。
 *
 * 当前颜色车辆直达订单；错色车辆进入编号最小的空位。订单完成后，按 arrivalSequence
 * 从停车位中自动调度下一订单需要的颜色。所有循环都有订单数和车位数的天然上界。
 */
object ParkingOrderRouter {
    fun route(
        rules: ParkingRules,
        current: ParkingLotSnapshot,
        arrivingVehicle: VehicleDefinition,
        vehicleById: Map<VehicleId, VehicleDefinition>,
    ): ParkingRouteResult {
        val activeOrder = activeOrder(rules, current)
        if (activeOrder == null) {
            return ParkingRouteResult.Routed(
                snapshot = current,
                destination = ParkingDestination.Bypass,
                dispatches = emptyList(),
                fulfillments = emptyList(),
                completedOrderIds = emptyList(),
            )
        }
        if (arrivingVehicle.color == activeOrder.color) {
            val mutable = MutableParkingLot(current)
            val fulfillments = mutableListOf<ParkingFulfillment>()
            val completedOrders = mutableListOf<OrderId>()
            mutable.fulfill(
                order = activeOrder,
                vehicle = arrivingVehicle,
                fromSlotIndex = null,
                destination = fulfillments,
            )
            if (mutable.isComplete(activeOrder)) completedOrders += activeOrder.id
            val dispatches = drainWaitingVehicles(
                rules = rules,
                parking = mutable,
                vehicleById = vehicleById,
                fulfillments = fulfillments,
                completedOrderIds = completedOrders,
            )
            return ParkingRouteResult.Routed(
                snapshot = mutable.snapshot(),
                destination = ParkingDestination.Order(activeOrder.id),
                dispatches = dispatches,
                fulfillments = fulfillments,
                completedOrderIds = completedOrders,
            )
        }

        val freeSlotIndex = current.slots.indexOfFirst { it == null }
        if (freeSlotIndex < 0) return ParkingRouteResult.Full

        val mutable = MutableParkingLot(current)
        mutable.slots[freeSlotIndex] = WaitingVehicle(
            vehicleId = arrivingVehicle.id,
            arrivalSequence = mutable.nextArrivalSequence,
        )
        mutable.nextArrivalSequence += 1L
        return ParkingRouteResult.Routed(
            snapshot = mutable.snapshot(),
            destination = ParkingDestination.Slot(freeSlotIndex),
            dispatches = emptyList(),
            fulfillments = emptyList(),
            completedOrderIds = emptyList(),
        )
    }

    fun allOrdersComplete(rules: ParkingRules, snapshot: ParkingLotSnapshot): Boolean =
        rules.orders.all { order -> fulfilledCount(snapshot, order.id) >= order.requiredCount }

    private fun drainWaitingVehicles(
        rules: ParkingRules,
        parking: MutableParkingLot,
        vehicleById: Map<VehicleId, VehicleDefinition>,
        fulfillments: MutableList<ParkingFulfillment>,
        completedOrderIds: MutableList<OrderId>,
    ): List<ParkingDispatch> {
        val dispatches = mutableListOf<ParkingDispatch>()
        while (true) {
            val order = activeOrder(rules, parking.snapshot()) ?: break
            val candidate = parking.slots
                .withIndex()
                .asSequence()
                .mapNotNull { indexed -> indexed.value?.let { indexed.index to it } }
                .filter { (_, waiting) -> vehicleById[waiting.vehicleId]?.color == order.color }
                .minByOrNull { (_, waiting) -> waiting.arrivalSequence }
                ?: break
            val slotIndex = candidate.first
            val waiting = candidate.second
            val definition = vehicleById[waiting.vehicleId] ?: break
            parking.slots[slotIndex] = null
            parking.fulfill(order, definition, slotIndex, fulfillments)
            dispatches += ParkingDispatch(definition.id, slotIndex, order.id)
            if (parking.isComplete(order)) completedOrderIds += order.id
        }
        return dispatches
    }

    private fun activeOrder(
        rules: ParkingRules,
        snapshot: ParkingLotSnapshot,
    ): ColorOrderDefinition? = rules.orders.firstOrNull { order ->
        fulfilledCount(snapshot, order.id) < order.requiredCount
    }

    private fun fulfilledCount(snapshot: ParkingLotSnapshot, orderId: OrderId): Int =
        snapshot.fulfilledVehicleIdsByOrder[orderId].orEmpty().size

    private class MutableParkingLot(snapshot: ParkingLotSnapshot) {
        val slots = snapshot.slots.toMutableList()
        val fulfilled = snapshot.fulfilledVehicleIdsByOrder
            .mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toMutableList() }
        var nextArrivalSequence = snapshot.nextArrivalSequence

        fun fulfill(
            order: ColorOrderDefinition,
            vehicle: VehicleDefinition,
            fromSlotIndex: Int?,
            destination: MutableList<ParkingFulfillment>,
        ) {
            fulfilled.getOrPut(order.id, ::mutableListOf) += vehicle.id
            destination += ParkingFulfillment(vehicle.id, order.id, vehicle.color, fromSlotIndex)
        }

        fun isComplete(order: ColorOrderDefinition): Boolean =
            fulfilled[order.id].orEmpty().size >= order.requiredCount

        fun snapshot(): ParkingLotSnapshot = ParkingLotSnapshot(
            slots = slots.toList(),
            fulfilledVehicleIdsByOrder = fulfilled.mapValues { (_, ids) -> ids.toList() },
            nextArrivalSequence = nextArrivalSequence,
        )
    }
}
