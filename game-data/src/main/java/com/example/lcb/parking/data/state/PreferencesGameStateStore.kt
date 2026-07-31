package com.example.lcb.parking.data.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.ports.GameStateCommit
import com.example.lcb.parking.domain.ports.GameStateStore
import com.example.lcb.parking.domain.ports.PendingFactsAcknowledgeResult
import com.example.lcb.parking.domain.ports.PendingFactsResult
import com.example.lcb.parking.domain.ports.StoreWriteResult
import com.example.lcb.parking.domain.ports.StoredGameResult
import com.example.lcb.parking.domain.ports.StoredProgressResult
import com.example.lcb.parking.domain.ports.StoredStateBundleResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 使用一个 Preferences String 保存版本化 JSON envelope。
 *
 * `updateData` 将棋盘、玩家进度和可丢弃的 telemetry facts 放在同一个串行事务中。遇到未来
 * schema 或损坏 envelope 时只返回错误，绝不以默认值覆盖原数据。
 */
class PreferencesGameStateStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gson: Gson = Gson(),
) : GameStateStore {

    private val codec = VersionedEnvelopeCodec(
        gson = gson,
        currentSchemaVersion = CURRENT_SCHEMA_VERSION,
        encodePayload = { payload: GameStatePayloadDto -> gson.toJsonTree(payload) },
        decodePayload = { json ->
            gson.fromJson(json, GameStatePayloadDto::class.java)
                ?: throw IllegalArgumentException("Game-state payload is null")
        },
        migrations = mapOf(
            1 to ::migrateV1Payload,
            2 to ::migrateV2Payload,
        ),
    )

    /**
     * Rule V2 changes the in-level snapshot shape and every built-in level version. Old active
     * attempts cannot be resumed safely, so migration discards only snapshots while preserving
     * durable player progress and the fact outbox.
     */
    private fun migrateV1Payload(payload: JsonElement): JsonElement {
        require(payload.isJsonObject) { "V1 game-state payload must be an object" }
        return payload.deepCopy().asJsonObject.apply {
            add("game_snapshots", JsonArray())
        }
    }

    /** Wraps the V1/V2 fact array with durable FIFO identity without changing fact payloads. */
    private fun migrateV2Payload(payload: JsonElement): JsonElement {
        require(payload.isJsonObject) { "V2 game-state payload must be an object" }
        return payload.deepCopy().asJsonObject.apply {
            val legacyFactsElement = remove("pending_facts")
            val legacyFacts = when {
                legacyFactsElement == null -> JsonArray()
                legacyFactsElement.isJsonArray -> legacyFactsElement.asJsonArray
                else -> throw IllegalArgumentException("V2 pending_facts must be an array")
            }
            val records = JsonArray()
            legacyFacts.forEachIndexed { index, fact ->
                val sequence = index + 1L
                records.add(
                    JsonObject().apply {
                        addProperty("sequence", sequence)
                        addProperty("event_id", "telemetry-$sequence")
                        add("fact", fact.deepCopy())
                    },
                )
            }
            add(
                "telemetry_outbox",
                JsonObject().apply {
                    add("records", records)
                    addProperty("next_sequence", legacyFacts.size() + 1L)
                    addProperty("dropped_telemetry_count", 0L)
                },
            )
        }
    }

    override suspend fun loadGame(levelId: LevelId): StoredGameResult =
        when (val bundle = loadStateBundle(listOf(levelId))) {
            is StoredStateBundleResult.Loaded -> bundle.snapshotsByLevel[levelId]
                ?.let(StoredGameResult::Loaded)
                ?: StoredGameResult.Missing
            is StoredStateBundleResult.Incompatible -> StoredGameResult.Incompatible(bundle.reason)
            is StoredStateBundleResult.Corrupt -> StoredGameResult.Corrupt(bundle.reason)
            is StoredStateBundleResult.Unavailable -> StoredGameResult.Unavailable(bundle.reason)
        }

    override suspend fun loadPlayerProgress(): StoredProgressResult =
        when (val bundle = loadStateBundle(emptyList())) {
            is StoredStateBundleResult.Loaded -> bundle.progress
                ?.let(StoredProgressResult::Loaded)
                ?: StoredProgressResult.Missing
            is StoredStateBundleResult.Incompatible -> StoredProgressResult.Unavailable(bundle.reason)
            is StoredStateBundleResult.Corrupt -> StoredProgressResult.Corrupt(bundle.reason)
            is StoredStateBundleResult.Unavailable -> StoredProgressResult.Unavailable(bundle.reason)
        }

    override suspend fun loadStateBundle(
        levelIds: Collection<LevelId>,
    ): StoredStateBundleResult = withContext(ioDispatcher) {
        require(levelIds.size == levelIds.toSet().size) { "levelIds must be unique" }
        val requestedIds = levelIds.toList()
        when (val stored = readPayload()) {
            PayloadReadResult.Missing -> StoredStateBundleResult.Loaded(
                snapshotsByLevel = emptyMap(),
                missingLevelIds = requestedIds.toSet(),
                progress = null,
            )
            is PayloadReadResult.FutureVersion -> {
                StoredStateBundleResult.Incompatible(stored.reason)
            }
            is PayloadReadResult.Corrupt -> StoredStateBundleResult.Corrupt(stored.reason)
            is PayloadReadResult.Unavailable -> StoredStateBundleResult.Unavailable(stored.reason)
            is PayloadReadResult.Loaded -> mapStateBundle(stored.payload, requestedIds)
        }
    }

    override suspend fun commit(commit: GameStateCommit): StoreWriteResult = withContext(ioDispatcher) {
        var result: StoreWriteResult? = null
        try {
            dataStore.updateData { preferences ->
                val payload = when (val decoded = decode(preferences[ENVELOPE_KEY])) {
                    PayloadReadResult.Missing -> GameStatePayloadDto()
                    is PayloadReadResult.Loaded -> decoded.payload
                    is PayloadReadResult.FutureVersion -> {
                        result = StoreWriteResult.Failed(decoded.reason)
                        return@updateData preferences
                    }
                    is PayloadReadResult.Corrupt -> {
                        result = StoreWriteResult.Failed(decoded.reason)
                        return@updateData preferences
                    }
                    is PayloadReadResult.Unavailable -> {
                        result = StoreWriteResult.Failed(decoded.reason)
                        return@updateData preferences
                    }
                }

                val persistedSnapshots = payload.gameSnapshots.orEmpty()
                val existingSnapshots = persistedSnapshots.filter {
                    it.levelId == commit.snapshot.levelId.value
                }
                if (existingSnapshots.size > 1) {
                    result = StoreWriteResult.Failed("Duplicate persisted snapshots for level")
                    return@updateData preferences
                }
                val existingSnapshot = existingSnapshots.singleOrNull()
                val actualSnapshotRevision = existingSnapshot?.revision
                if (!revisionMatches(commit.expectedSnapshotRevision, actualSnapshotRevision)) {
                    result = StoreWriteResult.RevisionConflict(actualSnapshotRevision)
                    return@updateData preferences
                }
                if (existingSnapshot != null && commit.snapshot.revision <= existingSnapshot.revision) {
                    result = StoreWriteResult.RevisionConflict(existingSnapshot.revision)
                    return@updateData preferences
                }
                if (existingSnapshot == null && commit.snapshot.revision < 0L) {
                    result = StoreWriteResult.Failed("Snapshot revision must be non-negative")
                    return@updateData preferences
                }

                val progressResult = validateProgressRevision(payload.playerProgress, commit)
                if (progressResult != null) {
                    result = progressResult
                    return@updateData preferences
                }

                val updatedSnapshots = persistedSnapshots
                    .filterNot { it.levelId == commit.snapshot.levelId.value }
                    .plus(GameStateMapper.toDto(commit.snapshot))
                    .sortedBy(GameSnapshotDto::levelId)
                val updatedPayload = payload.copy(
                    gameSnapshots = updatedSnapshots,
                    playerProgress = commit.progress?.let(GameStateMapper::toDto) ?: payload.playerProgress,
                    telemetryOutbox = TelemetryOutboxPolicy.append(
                        payload.telemetryOutbox,
                        commit.facts,
                    ),
                )
                val encoded = encodeBounded(updatedPayload)

                result = StoreWriteResult.Saved(commit.snapshot.revision)
                preferences.toMutablePreferences().apply {
                    this[ENVELOPE_KEY] = encoded.raw
                }
            }
            result ?: StoreWriteResult.Failed("DataStore transaction produced no result")
        } catch (error: IOException) {
            StoreWriteResult.Failed(error.safeReason("Unable to write game state"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ClassCastException) {
            StoreWriteResult.Failed(error.safeReason("Invalid Preferences value type"))
        } catch (error: IllegalArgumentException) {
            StoreWriteResult.Failed(error.safeReason("Invalid game-state commit"))
        } catch (error: IllegalStateException) {
            StoreWriteResult.Failed(error.safeReason("Invalid DataStore state"))
        }
    }

    override suspend fun peekPendingFacts(limit: Int): PendingFactsResult = withContext(ioDispatcher) {
        require(limit > 0) { "limit must be positive" }
        when (val stored = readPayload()) {
            PayloadReadResult.Missing -> PendingFactsResult.Loaded(
                TelemetryOutboxPolicy.toBatch(null, limit),
            )
            is PayloadReadResult.Loaded -> PendingFactsResult.Loaded(
                TelemetryOutboxPolicy.toBatch(stored.payload.telemetryOutbox, limit),
            )
            is PayloadReadResult.FutureVersion -> PendingFactsResult.Incompatible(stored.reason)
            is PayloadReadResult.Corrupt -> PendingFactsResult.Corrupt(stored.reason)
            is PayloadReadResult.Unavailable -> PendingFactsResult.Unavailable(stored.reason)
        }
    }

    override suspend fun acknowledgePendingFacts(
        throughSequence: Long,
    ): PendingFactsAcknowledgeResult = withContext(ioDispatcher) {
        require(throughSequence >= 0L) { "throughSequence must be non-negative" }
        var result: PendingFactsAcknowledgeResult? = null
        try {
            dataStore.updateData { preferences ->
                when (val decoded = decode(preferences[ENVELOPE_KEY])) {
                    PayloadReadResult.Missing -> {
                        result = PendingFactsAcknowledgeResult.Acknowledged(throughSequence, 0)
                        preferences
                    }
                    is PayloadReadResult.Loaded -> {
                        val (outbox, removedCount) = TelemetryOutboxPolicy.acknowledge(
                            decoded.payload.telemetryOutbox,
                            throughSequence,
                        )
                        val encoded = encodeBounded(decoded.payload.copy(telemetryOutbox = outbox))
                        result = PendingFactsAcknowledgeResult.Acknowledged(
                            throughSequence,
                            removedCount,
                        )
                        preferences.toMutablePreferences().apply {
                            this[ENVELOPE_KEY] = encoded.raw
                        }
                    }
                    is PayloadReadResult.FutureVersion -> {
                        result = PendingFactsAcknowledgeResult.Failed(decoded.reason)
                        preferences
                    }
                    is PayloadReadResult.Corrupt -> {
                        result = PendingFactsAcknowledgeResult.Failed(decoded.reason)
                        preferences
                    }
                    is PayloadReadResult.Unavailable -> {
                        result = PendingFactsAcknowledgeResult.Failed(decoded.reason)
                        preferences
                    }
                }
            }
            result ?: PendingFactsAcknowledgeResult.Failed(
                "DataStore acknowledgement produced no result",
            )
        } catch (error: IOException) {
            PendingFactsAcknowledgeResult.Failed(error.safeReason("Unable to acknowledge facts"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ClassCastException) {
            PendingFactsAcknowledgeResult.Failed(
                error.safeReason("Invalid Preferences value type"),
            )
        } catch (error: IllegalArgumentException) {
            PendingFactsAcknowledgeResult.Failed(
                error.safeReason("Invalid fact acknowledgement"),
            )
        } catch (error: IllegalStateException) {
            PendingFactsAcknowledgeResult.Failed(error.safeReason("Invalid DataStore state"))
        }
    }

    private suspend fun readPayload(): PayloadReadResult {
        return try {
            decode(dataStore.data.first()[ENVELOPE_KEY])
        } catch (error: IOException) {
            PayloadReadResult.Unavailable(error.safeReason("Unable to read game state"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ClassCastException) {
            PayloadReadResult.Corrupt(error.safeReason("Invalid Preferences value type"))
        } catch (error: IllegalStateException) {
            PayloadReadResult.Unavailable(error.safeReason("DataStore is unavailable"))
        }
    }

    private fun decode(raw: String?): PayloadReadResult {
        if (raw == null) return PayloadReadResult.Missing
        return when (val decoded = codec.decode(raw)) {
            is EnvelopeDecodeResult.Success -> PayloadReadResult.Loaded(decoded.value)
            is EnvelopeDecodeResult.FutureVersion -> PayloadReadResult.FutureVersion(
                "Save schema ${decoded.foundVersion} is newer than supported ${decoded.supportedVersion}",
            )
            is EnvelopeDecodeResult.MigrationMissing -> PayloadReadResult.Corrupt(
                "Missing save migration ${decoded.fromVersion}->${decoded.toVersion}",
            )
            is EnvelopeDecodeResult.Corrupt -> PayloadReadResult.Corrupt(
                decoded.reason.take(MAX_ERROR_LENGTH),
            )
        }
    }

    private fun mapStateBundle(
        payload: GameStatePayloadDto,
        requestedIds: List<LevelId>,
    ): StoredStateBundleResult {
        return try {
            val persistedSnapshots = payload.gameSnapshots.orEmpty()
            val snapshotsById = persistedSnapshots.groupBy(GameSnapshotDto::levelId)
            val duplicateId = snapshotsById.entries.firstOrNull { it.value.size > 1 }?.key
            if (duplicateId != null) {
                return StoredStateBundleResult.Corrupt(
                    "Duplicate persisted snapshots for level $duplicateId",
                )
            }

            val loaded = LinkedHashMap<LevelId, GameSnapshot>()
            val missing = linkedSetOf<LevelId>()
            requestedIds.forEach { levelId ->
                val dto = snapshotsById[levelId.value]?.singleOrNull()
                if (dto == null) {
                    missing += levelId
                } else {
                    loaded[levelId] = GameStateMapper.toDomain(dto)
                }
            }
            val progress = payload.playerProgress?.let(GameStateMapper::toDomain)
            StoredStateBundleResult.Loaded(loaded, missing, progress)
        } catch (error: IllegalArgumentException) {
            StoredStateBundleResult.Corrupt(error.safeReason("Invalid game-state bundle"))
        } catch (error: IllegalStateException) {
            StoredStateBundleResult.Corrupt(error.safeReason("Invalid game-state bundle state"))
        } catch (_: NullPointerException) {
            StoredStateBundleResult.Corrupt("Game-state bundle is missing a required field")
        }
    }

    private fun encodeBounded(payload: GameStatePayloadDto): TelemetryOutboxPolicy.EncodedPayload =
        TelemetryOutboxPolicy.encodeWithinTarget(
            source = payload,
            maxBytes = TARGET_ENVELOPE_BYTES,
            encode = codec::encode,
        )

    private fun validateProgressRevision(
        existingProgress: PlayerProgressDto?,
        commit: GameStateCommit,
    ): StoreWriteResult? {
        val progress = commit.progress
        val expectedRevision = commit.expectedProgressRevision
        if (progress == null && expectedRevision == null) return null
        if (progress == null || expectedRevision == null) {
            return StoreWriteResult.Failed("Progress and expectedProgressRevision must be supplied together")
        }

        val actualRevision = existingProgress?.revision
        if (!revisionMatches(expectedRevision, actualRevision)) {
            return StoreWriteResult.RevisionConflict(actualRevision)
        }
        if (existingProgress != null && progress.revision <= existingProgress.revision) {
            return StoreWriteResult.RevisionConflict(existingProgress.revision)
        }
        if (existingProgress == null && progress.revision < 0L) {
            return StoreWriteResult.Failed("Progress revision must be non-negative")
        }
        return null
    }

    private fun revisionMatches(expected: Long, actual: Long?): Boolean {
        return if (actual == null) expected == INITIAL_REVISION else expected == actual
    }

    private fun Throwable.safeReason(fallback: String): String {
        return message?.take(MAX_ERROR_LENGTH) ?: fallback
    }

    private sealed interface PayloadReadResult {
        data object Missing : PayloadReadResult
        data class Loaded(val payload: GameStatePayloadDto) : PayloadReadResult
        data class FutureVersion(val reason: String) : PayloadReadResult
        data class Corrupt(val reason: String) : PayloadReadResult
        data class Unavailable(val reason: String) : PayloadReadResult
    }

    private companion object {
        val ENVELOPE_KEY = stringPreferencesKey("game_state_envelope")
        const val CURRENT_SCHEMA_VERSION = 3
        const val INITIAL_REVISION = 0L
        const val TARGET_ENVELOPE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_LENGTH = 512
    }
}
