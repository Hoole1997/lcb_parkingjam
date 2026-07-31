package com.example.lcb.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OneShotActionGateTest {
    @Test
    fun `installed action executes only once after repeated enable`() {
        var calls = 0
        val gate = OneShotActionGate()

        gate.install { calls++ }
        gate.enable()
        gate.enable()

        assertEquals(1, calls)
    }

    @Test
    fun `enable before install still executes installed action once`() {
        var calls = 0
        val gate = OneShotActionGate()

        gate.enable()
        gate.install { calls++ }
        gate.install { calls += 100 }

        assertEquals(1, calls)
    }
}
