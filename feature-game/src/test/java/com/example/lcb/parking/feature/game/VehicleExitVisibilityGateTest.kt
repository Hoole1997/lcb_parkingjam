package com.example.lcb.parking.feature.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleExitVisibilityGateTest {

    private val gate = VehicleExitVisibilityGate()

    @Test
    fun `moving vehicle remains suppressed while repeated snapshot is pending`() {
        gate.suppressUntilSnapshotRemoval(VEHICLE_ID)

        gate.reconcile(boardWith(VehicleVisualState.MOVING))
        gate.reconcile(boardWith(VehicleVisualState.MOVING))

        assertTrue(gate.isSuppressed(VEHICLE_ID))
    }

    @Test
    fun `terminal snapshot releases tombstone and reused id is visible`() {
        gate.suppressUntilSnapshotRemoval(VEHICLE_ID)

        gate.reconcile(boardWith(VehicleVisualState.EXITED))

        assertFalse(gate.isSuppressed(VEHICLE_ID))
        gate.reconcile(boardWith(VehicleVisualState.PARKED))
        assertFalse(gate.isSuppressed(VEHICLE_ID))
    }

    @Test
    fun `vehicle removal and explicit reset both release tombstone`() {
        gate.suppressUntilSnapshotRemoval(VEHICLE_ID)
        gate.reconcile(BoardRenderModel.EMPTY)
        assertFalse(gate.isSuppressed(VEHICLE_ID))

        gate.suppressUntilSnapshotRemoval(VEHICLE_ID)
        gate.reset()
        assertFalse(gate.isSuppressed(VEHICLE_ID))
    }

    private fun boardWith(state: VehicleVisualState): BoardRenderModel {
        return BoardRenderModel(
            rows = 6,
            columns = 5,
            vehicles = listOf(
                VehicleRenderModel(
                    id = VEHICLE_ID,
                    row = 2,
                    column = 2,
                    widthCells = 1,
                    heightCells = 2,
                    direction = VehicleDirection.UP,
                    visualState = state,
                ),
            ),
        )
    }

    private companion object {
        const val VEHICLE_ID = "car_a"
    }
}
