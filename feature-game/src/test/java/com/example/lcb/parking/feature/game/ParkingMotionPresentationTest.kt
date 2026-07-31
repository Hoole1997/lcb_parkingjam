package com.example.lcb.parking.feature.game

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParkingMotionPresentationTest {

    @Test
    fun `parking motion waits for exact board exit handoff instead of a timer`() = runTest {
        val controller = ParkingMotionController(queueCapacity = 2)
        val arriving = vehicle(id = "handoff")
        val effect = GamePresentationEffect.MoveVehicle(
            effectId = "handoff_effect",
            vehicleId = arriving.id,
            deltaRows = -4f,
            deltaColumns = 0f,
            parkingMotion = ParkingMotionSpec(
                arrivingVehicle = arriving,
                destination = ParkingMotionDestination.WaitingSlot(0),
            ),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.run(animationsEnabled = { false })
        }

        controller.enqueue(effect)
        runCurrent()
        assertFalse(controller.isIdle)

        controller.onBoardExitReady(effect.effectId)
        runCurrent()
        assertTrue(controller.isIdle)
    }

    @Test
    fun `timing keeps every domain dispatch in sequential animation budget`() {
        val spec = ParkingMotionSpec(
            arrivingVehicle = vehicle(),
            destination = ParkingMotionDestination.CurrentOrder,
            dispatches = listOf(
                ParkingDispatchMotion("waiting_a", 3, VehicleArtVariant.MINT, lengthCells = 3),
                ParkingDispatchMotion("waiting_b", 0, VehicleArtVariant.BLUE, lengthCells = 2),
            ),
        )

        assertEquals(
            ParkingMotionTiming.ORDER_ARRIVAL_MILLIS +
                ParkingMotionTiming.DISPATCH_TO_ORDER_MILLIS * 2,
            spec.presentationDurationMillis,
        )
    }

    @Test
    fun `geometry centers narrowed parking group and addresses exact slot`() {
        val layout = GamePlayLayoutPolicy.calculate(
            availableWidthDp = 320f,
            availableHeightDp = 568f,
            boardRows = 10,
            boardColumns = 8,
            parkingCapacity = 5,
            fontScale = 1f,
        )

        val first = ParkingMotionGeometry.waitingSlotCenter(layout, 0)
        val last = ParkingMotionGeometry.waitingSlotCenter(layout, 4)

        assertEquals(56f, first.x, 0.001f)
        assertEquals(256f, last.x, 0.001f)
        assertEquals(first.y, last.y, 0.001f)
        assertEquals(layout.contentWidthDp / 2f, (first.x + last.x) / 2f, 0.001f)
    }

    @Test
    fun `visual ledger hides arrivals and holds dispatches without changing authority`() {
        val controller = ParkingMotionController(queueCapacity = 2)
        val arriving = vehicle(id = "arriving", color = VehicleArtVariant.CORAL)
        val effect = GamePresentationEffect.MoveVehicle(
            effectId = "effect",
            vehicleId = arriving.id,
            deltaRows = -4f,
            deltaColumns = 0f,
            parkingMotion = ParkingMotionSpec(
                arrivingVehicle = arriving,
                destination = ParkingMotionDestination.WaitingSlot(1),
                dispatches = listOf(
                    ParkingDispatchMotion(
                        "waiting",
                        0,
                        VehicleArtVariant.MINT,
                        lengthCells = 3,
                    ),
                ),
            ),
        )
        val authoritativeArrival = ParkingSlotUiState(
            index = 1,
            vehicleId = arriving.id,
            color = arriving.artVariant,
            lengthCells = arriving.lengthCells,
            arrivalSequence = 2L,
        )
        val authoritativeEmptiedSlot = ParkingSlotUiState(index = 0)

        controller.enqueue(effect)

        assertNull(controller.presentedArtKey(authoritativeArrival))
        assertEquals(
            ParkingVehicleArtKey(VehicleArtVariant.MINT, ParkingVehicleArtLength.LONG),
            controller.presentedArtKey(authoritativeEmptiedSlot),
        )
        assertFalse(controller.isIdle)

        controller.fastForward()

        assertEquals(arriving.parkingArtKey, controller.presentedArtKey(authoritativeArrival))
        assertNull(controller.presentedArtKey(authoritativeEmptiedSlot))
        assertTrue(controller.isIdle)
    }

    @Test
    fun `future dispatch cannot make a not-yet-arrived vehicle appear in its slot`() {
        val controller = ParkingMotionController(queueCapacity = 2)
        val waiting = vehicle(id = "waiting", color = VehicleArtVariant.BLUE)
        controller.enqueue(
            GamePresentationEffect.MoveVehicle(
                effectId = "arrive",
                vehicleId = waiting.id,
                deltaRows = -4f,
                deltaColumns = 0f,
                parkingMotion = ParkingMotionSpec(
                    arrivingVehicle = waiting,
                    destination = ParkingMotionDestination.WaitingSlot(0),
                ),
            ),
        )
        controller.enqueue(
            GamePresentationEffect.MoveVehicle(
                effectId = "dispatch_later",
                vehicleId = "order_car",
                deltaRows = -4f,
                deltaColumns = 0f,
                parkingMotion = ParkingMotionSpec(
                    arrivingVehicle = vehicle(id = "order_car"),
                    destination = ParkingMotionDestination.CurrentOrder,
                    dispatches = listOf(
                        ParkingDispatchMotion(
                            waiting.id,
                            0,
                            waiting.artVariant,
                            waiting.lengthCells,
                        ),
                    ),
                ),
            ),
        )

        assertNull(controller.presentedArtKey(ParkingSlotUiState(index = 0)))

        controller.fastForward()
    }

    private fun vehicle(
        id: String = "arriving",
        color: VehicleArtVariant = VehicleArtVariant.CORAL,
    ): VehicleRenderModel = VehicleRenderModel(
        id = id,
        row = 4,
        column = 2,
        widthCells = 1,
        heightCells = 2,
        direction = VehicleDirection.UP,
        visualState = VehicleVisualState.MOVING,
        artVariant = color,
        color = color.argb,
    )
}
