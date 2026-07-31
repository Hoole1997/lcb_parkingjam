package com.example.lcb.parking.feature.game

/** 纯 Kotlin 的回弹准入器；把同车合并和并发上界从 Animator 生命周期中解耦出来。 */
internal class ReboundEffectGate(private val maxConcurrentVehicles: Int) {
    init {
        require(maxConcurrentVehicles > 0) { "maxConcurrentVehicles must be positive" }
    }

    private val activeVehicleIds = LinkedHashSet<String>(maxConcurrentVehicles)

    val activeCount: Int
        get() = activeVehicleIds.size

    fun acquire(vehicleId: String): ReboundAdmission {
        require(vehicleId.isNotBlank()) { "vehicleId cannot be blank" }
        if (vehicleId in activeVehicleIds) return ReboundAdmission.COALESCED
        if (activeVehicleIds.size >= maxConcurrentVehicles) return ReboundAdmission.SATURATED
        activeVehicleIds += vehicleId
        return ReboundAdmission.START
    }

    fun release(vehicleId: String) {
        activeVehicleIds -= vehicleId
    }

    fun clear() {
        activeVehicleIds.clear()
    }
}

internal enum class ReboundAdmission {
    START,
    COALESCED,
    SATURATED,
}
