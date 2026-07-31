package com.example.lcb.parking.feature.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionCoordinatorTest {

    @Test
    fun `persist completes before effect and ui are published`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callOrder = mutableListOf<String>()
        val store = RecordingStore(FakeAggregate(0), callOrder)
        val coordinator = coordinator(
            store = store,
            dispatcher = dispatcher,
            callOrder = callOrder,
        )

        coordinator.start(this)
        advanceUntilIdle()
        callOrder.clear()
        val effect = async { coordinator.presentationEffects.first() }

        assertTrue(coordinator.submit(MainGameCommand.TapVehicle("car_a")))
        advanceUntilIdle()

        assertEquals(listOf("reduce", "persist", "effect_map", "ui_map"), callOrder)
        assertEquals(1, coordinator.uiState.value.levelNumber)
        assertEquals("car_a", (effect.await() as GamePresentationEffect.HighlightVehicle).vehicleId)
        coordinator.close()
    }

    @Test
    fun `persistence failure keeps previous aggregate and publishes no effect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecordingStore(FakeAggregate(0), mutableListOf()).apply { failPersist = true }
        val coordinator = coordinator(store, dispatcher, mutableListOf())

        coordinator.start(this)
        advanceUntilIdle()
        assertTrue(coordinator.submit(MainGameCommand.TapVehicle("car_a")))
        advanceUntilIdle()

        assertEquals(GameScreenPhase.ERROR, coordinator.uiState.value.phase)
        assertEquals(0, store.persistedAggregates.size)
        coordinator.close()
    }

    @Test
    fun `command channel is bounded and submit never blocks caller`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = coordinator(
            store = RecordingStore(FakeAggregate(0), mutableListOf()),
            dispatcher = dispatcher,
            callOrder = mutableListOf(),
            commandCapacity = 2,
        )

        assertFalse(coordinator.submit(MainGameCommand.Pause))
        coordinator.start(this)
        assertTrue(coordinator.submit(MainGameCommand.Pause))
        assertTrue(coordinator.submit(MainGameCommand.Resume))
        assertFalse(coordinator.submit(MainGameCommand.HostStopped))
        advanceUntilIdle()
        coordinator.close()
    }

    @Test
    fun `critical presentation acknowledgement has reserved bounded capacity`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = coordinator(
            store = RecordingStore(FakeAggregate(0), mutableListOf()),
            dispatcher = dispatcher,
            callOrder = mutableListOf(),
            commandCapacity = 1,
        )

        coordinator.start(this)
        assertTrue(coordinator.submit(MainGameCommand.Pause))
        assertFalse(coordinator.submit(MainGameCommand.Resume))
        assertTrue(
            coordinator.submitCritical(
                MainGameCommand.PresentationCompleted("effect_1", "car_a"),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, coordinator.uiState.value.levelNumber)
        coordinator.close()
    }

    @Test
    fun `host stop uses reserved capacity when ordinary command queue is full`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = coordinator(
            store = RecordingStore(FakeAggregate(0), mutableListOf()),
            dispatcher = dispatcher,
            callOrder = mutableListOf(),
            commandCapacity = 1,
        )

        coordinator.start(this)
        assertTrue(coordinator.submit(MainGameCommand.Pause))
        assertFalse(coordinator.submit(MainGameCommand.Resume))
        assertTrue(coordinator.submitCritical(MainGameCommand.HostStopped))
        advanceUntilIdle()

        assertEquals(2, coordinator.uiState.value.levelNumber)
        coordinator.close()
    }

    private fun coordinator(
        store: RecordingStore,
        dispatcher: TestDispatcher,
        callOrder: MutableList<String>,
        commandCapacity: Int = 8,
    ): GameSessionCoordinator<FakeAggregate, FakeIntent> {
        return GameSessionCoordinator(
            store = store,
            reducer = GameSessionReducer { aggregate, command ->
                callOrder += "reduce"
                val vehicleId = (command as? MainGameCommand.TapVehicle)?.vehicleId ?: "none"
                GameSessionDecision(
                    aggregate = aggregate.copy(value = aggregate.value + 1),
                    presentationIntents = listOf(FakeIntent(vehicleId)),
                )
            },
            uiMapper = MainGameUiMapper { aggregate ->
                callOrder += "ui_map"
                MainGameUiState(
                    phase = GameScreenPhase.PLAYING,
                    levelNumber = aggregate.value,
                )
            },
            presentationEffectMapper = PresentationEffectMapper { _, intent ->
                callOrder += "effect_map"
                GamePresentationEffect.HighlightVehicle(
                    effectId = "effect_${intent.vehicleId}",
                    vehicleId = intent.vehicleId,
                )
            },
            domainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            commandCapacity = commandCapacity,
        )
    }

    private data class FakeAggregate(val value: Int)
    private data class FakeIntent(val vehicleId: String)

    private class RecordingStore(
        private val loadedAggregate: FakeAggregate,
        private val callOrder: MutableList<String>,
    ) : GameSessionStore<FakeAggregate> {
        val persistedAggregates = mutableListOf<FakeAggregate>()
        var failPersist: Boolean = false

        override suspend fun load(): FakeAggregate = loadedAggregate

        override suspend fun persist(previous: FakeAggregate, next: FakeAggregate) {
            callOrder += "persist"
            if (failPersist) error("disk unavailable")
            persistedAggregates += next
        }
    }
}
