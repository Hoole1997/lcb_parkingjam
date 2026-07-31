package com.example.lcb.parking.data.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptChainSnapshot
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptSnapshot
import com.example.lcb.parking.domain.model.BoardSnapshot
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.ports.GameStateCommit
import com.example.lcb.parking.domain.ports.PendingFactsAcknowledgeResult
import com.example.lcb.parking.domain.ports.PendingFactsResult
import com.example.lcb.parking.domain.ports.StoreWriteResult
import com.example.lcb.parking.domain.ports.StoredGameResult
import com.example.lcb.parking.domain.ports.StoredProgressResult
import com.example.lcb.parking.domain.ports.StoredStateBundleResult
import com.example.lcb.parking.domain.rules.DomainFact
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesGameStateStoreTest {

    @Test
    fun `commit atomically persists snapshot and fact`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val snapshot = sampleSnapshot(revision = 0L)
        val commit = GameStateCommit(
            expectedSnapshotRevision = 0L,
            snapshot = snapshot,
            facts = listOf(
                DomainFact.AttemptStarted(
                    attemptId = snapshot.attempt.attemptId,
                    attemptChainId = snapshot.attempt.attemptChainId,
                    continued = false,
                ),
            ),
        )

        val write = runImmediate { store.commit(commit) }
        val loaded = runImmediate { store.loadGame(snapshot.levelId) }

        assertEquals(StoreWriteResult.Saved(0L), write)
        assertEquals(StoredGameResult.Loaded(snapshot), loaded)
        val raw = dataStore.current[stringPreferencesKey("game_state_envelope")].orEmpty()
        assertTrue(raw.contains("attempt_started"))
        assertTrue(raw.contains("telemetry_outbox"))
        assertTrue(raw.contains("\"schemaVersion\":3"))
    }

    @Test
    fun `duplicate zero revision commit is rejected and does not append fact`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val snapshot = sampleSnapshot(revision = 0L)
        val commit = GameStateCommit(0L, snapshot, emptyList())

        assertEquals(StoreWriteResult.Saved(0L), runImmediate { store.commit(commit) })
        val rawBefore = dataStore.current
        val duplicate = runImmediate { store.commit(commit) }

        assertEquals(StoreWriteResult.RevisionConflict(0L), duplicate)
        assertEquals(rawBefore, dataStore.current)
    }

    @Test
    fun `future envelope remains untouched`() {
        val key = stringPreferencesKey("game_state_envelope")
        val futureRaw = """{"schemaVersion":99,"payload":{}}"""
        val dataStore = InMemoryPreferencesDataStore(preferencesOf(key to futureRaw))
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)

        val loaded = runImmediate { store.loadGame(LevelId("main_001")) }
        val write = runImmediate {
            store.commit(GameStateCommit(0L, sampleSnapshot(0L), emptyList()))
        }
        val acknowledge = runImmediate { store.acknowledgePendingFacts(10L) }

        assertTrue(loaded is StoredGameResult.Incompatible)
        assertTrue(write is StoreWriteResult.Failed)
        assertTrue(acknowledge is PendingFactsAcknowledgeResult.Failed)
        assertEquals(futureRaw, dataStore.current[key])
    }

    @Test
    fun `V1 envelope drops incompatible attempts but preserves player progress`() {
        val key = stringPreferencesKey("game_state_envelope")
        val legacyRaw = """
            {
              "schemaVersion": 1,
              "payload": {
                "game_snapshots": [{}],
                "player_progress": {
                  "coins": 88,
                  "best_stars_by_level": [],
                  "rewarded_coins_by_level": [],
                  "completed_level_ids": [],
                  "applied_reward_transaction_ids": [],
                  "l5_starter_reward_claimed": false,
                  "inventory": {"hints": 0, "shields": 0, "tows": 0},
                  "revision": 3
                },
                "pending_facts": [
                  {
                    "type": "attempt_started",
                    "attempt_id": "legacy_attempt",
                    "attempt_chain_id": "legacy_chain",
                    "continued": false
                  }
                ]
              }
            }
        """.trimIndent()
        val dataStore = InMemoryPreferencesDataStore(preferencesOf(key to legacyRaw))
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)

        assertEquals(StoredGameResult.Missing, runImmediate { store.loadGame(LevelId("main_001")) })
        val progress = runImmediate { store.loadPlayerProgress() }

        assertTrue(progress is StoredProgressResult.Loaded)
        progress as StoredProgressResult.Loaded
        assertEquals(PlayerProgress(coins = 88L, revision = 3L), progress.progress)
        val firstPeek = loadedBatch(runImmediate { store.peekPendingFacts(10) })
        val secondPeek = loadedBatch(runImmediate { store.peekPendingFacts(10) })
        assertEquals(listOf(1L), firstPeek.records.map { it.sequence })
        assertEquals("telemetry-1", firstPeek.records.single().eventId)
        assertEquals(firstPeek.records, secondPeek.records)
        assertTrue(firstPeek.records.single().fact is DomainFact.AttemptStarted)
    }

    @Test
    fun `V2 envelope upgrades its old outbox to durable V3 records and remains consumable`() {
        val key = stringPreferencesKey("game_state_envelope")
        val legacyRaw = """
            {
              "schemaVersion": 2,
              "payload": {
                "game_snapshots": [],
                "pending_facts": [
                  {"type":"presentation_acknowledged","effect_id":"legacy_1"},
                  {"type":"presentation_acknowledged","effect_id":"legacy_2"}
                ]
              }
            }
        """.trimIndent()
        val dataStore = InMemoryPreferencesDataStore(preferencesOf(key to legacyRaw))
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)

        val migrated = loadedBatch(runImmediate { store.peekPendingFacts(10) })
        assertEquals(listOf(1L, 2L), migrated.records.map { it.sequence })
        assertEquals(listOf("telemetry-1", "telemetry-2"), migrated.records.map { it.eventId })
        assertEquals(
            PendingFactsAcknowledgeResult.Acknowledged(1L, removedCount = 1),
            runImmediate { store.acknowledgePendingFacts(1L) },
        )

        val persistedV3 = dataStore.current[key].orEmpty()
        assertTrue(persistedV3.contains("\"schemaVersion\":3"))
        assertEquals(
            listOf(2L),
            loadedBatch(runImmediate { store.peekPendingFacts(10) }).records.map { it.sequence },
        )
    }

    @Test
    fun `outbox peek is FIFO and acknowledgement removes only the confirmed prefix`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val snapshot = sampleSnapshot(revision = 0L)
        val facts = (1..3).map { index ->
            DomainFact.PresentationAcknowledged(EffectId("effect_$index"))
        }
        assertEquals(
            StoreWriteResult.Saved(0L),
            runImmediate { store.commit(GameStateCommit(0L, snapshot, facts)) },
        )

        val first = loadedBatch(runImmediate { store.peekPendingFacts(2) })
        val retry = loadedBatch(
            runImmediate {
                PreferencesGameStateStore(dataStore, Dispatchers.Unconfined).peekPendingFacts(2)
            },
        )
        assertEquals(listOf(1L, 2L), first.records.map { it.sequence })
        assertEquals(first.records, retry.records)

        assertEquals(
            PendingFactsAcknowledgeResult.Acknowledged(2L, removedCount = 2),
            runImmediate { store.acknowledgePendingFacts(2L) },
        )
        assertEquals(
            PendingFactsAcknowledgeResult.Acknowledged(2L, removedCount = 0),
            runImmediate { store.acknowledgePendingFacts(2L) },
        )
        assertEquals(
            listOf(3L),
            loadedBatch(runImmediate { store.peekPendingFacts(10) }).records.map { it.sequence },
        )
    }

    @Test
    fun `capacity pressure drops oldest telemetry but still commits snapshot and rewards`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val snapshot = sampleSnapshot(revision = 0L)
        val extraCount = 100
        val facts = (1..(TelemetryOutboxPolicy.MAX_RECORDS + extraCount)).map { index ->
            DomainFact.PresentationAcknowledged(EffectId("capacity_$index"))
        }
        val progress = PlayerProgress(coins = 77L, revision = 1L)
        val commit = GameStateCommit(
            expectedSnapshotRevision = 0L,
            snapshot = snapshot,
            facts = facts,
            expectedProgressRevision = 0L,
            progress = progress,
        )

        assertEquals(StoreWriteResult.Saved(0L), runImmediate { store.commit(commit) })
        assertEquals(StoredGameResult.Loaded(snapshot), runImmediate { store.loadGame(snapshot.levelId) })
        assertEquals(
            StoredProgressResult.Loaded(progress),
            runImmediate { store.loadPlayerProgress() },
        )
        val batch = loadedBatch(runImmediate { store.peekPendingFacts(Int.MAX_VALUE) })
        assertEquals(TelemetryOutboxPolicy.MAX_RECORDS, batch.records.size)
        assertEquals(extraCount.toLong(), batch.droppedTelemetryCount)
        assertEquals((extraCount + 1).toLong(), batch.records.first().sequence)
    }

    @Test
    fun `two megabyte pressure trims telemetry instead of rejecting business state`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val snapshot = sampleSnapshot(revision = 0L)
        val oversizedId = "x".repeat(1_100_000)
        val facts = listOf(
            DomainFact.PresentationAcknowledged(EffectId("first_$oversizedId")),
            DomainFact.PresentationAcknowledged(EffectId("second_$oversizedId")),
        )

        assertEquals(
            StoreWriteResult.Saved(0L),
            runImmediate { store.commit(GameStateCommit(0L, snapshot, facts)) },
        )
        assertEquals(StoredGameResult.Loaded(snapshot), runImmediate { store.loadGame(snapshot.levelId) })
        val batch = loadedBatch(runImmediate { store.peekPendingFacts(10) })
        assertTrue(batch.droppedTelemetryCount >= 1L)
        val raw = dataStore.current[stringPreferencesKey("game_state_envelope")].orEmpty()
        assertTrue(raw.length <= 2 * 1024 * 1024)
    }

    @Test
    fun `state bundle reads two levels and progress with one envelope collection`() {
        val dataStore = InMemoryPreferencesDataStore()
        val store = PreferencesGameStateStore(dataStore, Dispatchers.Unconfined)
        val first = sampleSnapshot(0L, LevelId("main_001"), "first")
        val second = sampleSnapshot(0L, LevelId("main_002"), "second")
        val progress = PlayerProgress(coins = 12L, revision = 1L)
        assertEquals(
            StoreWriteResult.Saved(0L),
            runImmediate {
                store.commit(
                    GameStateCommit(0L, first, emptyList(), 0L, progress),
                )
            },
        )
        assertEquals(
            StoreWriteResult.Saved(0L),
            runImmediate { store.commit(GameStateCommit(0L, second, emptyList())) },
        )
        dataStore.dataCollectionCount = 0

        val result = runImmediate { store.loadStateBundle(listOf(first.levelId, second.levelId)) }

        assertTrue(result is StoredStateBundleResult.Loaded)
        result as StoredStateBundleResult.Loaded
        assertEquals(mapOf(first.levelId to first, second.levelId to second), result.snapshotsByLevel)
        assertEquals(progress, result.progress)
        assertEquals(1, dataStore.dataCollectionCount)
    }

    private fun sampleSnapshot(
        revision: Long,
        levelId: LevelId = LevelId("main_001"),
        identity: String = "1",
    ): GameSnapshot = GameSnapshot(
        levelId = levelId,
        levelVersion = 2,
        ruleVersion = 2,
        board = BoardSnapshot(
            vehicles = mapOf(VehicleId("A") to VehicleRuleState.Parked),
            openGateIds = emptySet(),
        ),
        parkingLot = ParkingLotSnapshot(
            slots = listOf(null, null),
            fulfilledVehicleIdsByOrder = mapOf(OrderId("order_01") to emptyList()),
        ),
        attempt = AttemptSnapshot(AttemptId("attempt_$identity"), AttemptChainId("chain_$identity")),
        chain = AttemptChainSnapshot(AttemptChainId("chain_$identity")),
        safety = SafetyState.TutorialUnlimited,
        revision = revision,
    )

    private fun loadedBatch(result: PendingFactsResult) =
        (result as PendingFactsResult.Loaded).batch

    /** Executes only immediate in-memory DataStore operations; production code never blocks threads. */
    private fun <T> runImmediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome) { "Test coroutine unexpectedly suspended" }.getOrThrow()
    }

    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var dataCollectionCount: Int = 0
        override val data: Flow<Preferences> = flow {
            dataCollectionCount += 1
            emit(state.value)
        }
        val current: Preferences get() = state.value

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return transform(state.value).also { state.value = it }
        }
    }
}
