package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Test

class ReboundEffectGateTest {

    @Test
    fun `one hundred rapid taps on same blocked vehicle retain one animation`() {
        val gate = ReboundEffectGate(maxConcurrentVehicles = 6)
        var started = 0
        var coalesced = 0

        repeat(100) {
            when (gate.acquire("car_a")) {
                ReboundAdmission.START -> started++
                ReboundAdmission.COALESCED -> coalesced++
                ReboundAdmission.SATURATED -> error("Same vehicle must coalesce before saturation")
            }
        }

        assertEquals(1, started)
        assertEquals(99, coalesced)
        assertEquals(1, gate.activeCount)
    }

    @Test
    fun `different blocked vehicles are bounded without changing active set`() {
        val gate = ReboundEffectGate(maxConcurrentVehicles = 3)

        assertEquals(ReboundAdmission.START, gate.acquire("car_a"))
        assertEquals(ReboundAdmission.START, gate.acquire("car_b"))
        assertEquals(ReboundAdmission.START, gate.acquire("car_c"))
        assertEquals(ReboundAdmission.SATURATED, gate.acquire("car_d"))
        assertEquals(3, gate.activeCount)

        gate.release("car_b")
        assertEquals(ReboundAdmission.START, gate.acquire("car_d"))
        assertEquals(3, gate.activeCount)
    }
}
