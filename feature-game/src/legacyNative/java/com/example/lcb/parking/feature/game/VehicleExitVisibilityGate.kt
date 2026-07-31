package com.example.lcb.parking.feature.game

/**
 * 衔接 Canvas 动画终态与异步权威快照的纯展示层可见性门。
 *
 * 离场动画结束后，业务确认仍需经过命令队列。此类在快照追上前保留一个轻量 tombstone，
 * 防止 MOVING 车辆因动画偏移被清零而在原车位回绘一帧。它不改变规则状态，也不持有 View。
 */
internal class VehicleExitVisibilityGate {

    private val suppressedVehicleIds = LinkedHashSet<String>()

    fun suppressUntilSnapshotRemoval(vehicleId: String) {
        if (vehicleId.isNotBlank()) suppressedVehicleIds += vehicleId
    }

    fun isSuppressed(vehicleId: String): Boolean = vehicleId in suppressedVehicleIds

    /**
     * 只有权威模型不再把车辆标为 MOVING 时才释放 tombstone。
     * 因此同一份旧快照被重复渲染也不会让车辆闪回；新关卡复用相同 id 时则会正常显示。
     */
    fun reconcile(board: BoardRenderModel) {
        if (suppressedVehicleIds.isEmpty()) return
        val iterator = suppressedVehicleIds.iterator()
        while (iterator.hasNext()) {
            val vehicleId = iterator.next()
            if (!board.containsMovingVehicle(vehicleId)) iterator.remove()
        }
    }

    fun reset() {
        suppressedVehicleIds.clear()
    }

    private fun BoardRenderModel.containsMovingVehicle(vehicleId: String): Boolean {
        var index = 0
        while (index < vehicles.size) {
            val vehicle = vehicles[index]
            if (vehicle.id == vehicleId && vehicle.visualState == VehicleVisualState.MOVING) {
                return true
            }
            index++
        }
        return false
    }
}
