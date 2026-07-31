package com.example.lcb.parking.domain.ports

import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.rules.DomainFact

sealed interface StoredGameResult {
    data class Loaded(val snapshot: GameSnapshot) : StoredGameResult
    data object Missing : StoredGameResult
    data class Incompatible(val reason: String) : StoredGameResult
    data class Corrupt(val reason: String) : StoredGameResult
    data class Unavailable(val reason: String) : StoredGameResult
}

sealed interface StoredProgressResult {
    data class Loaded(val progress: PlayerProgress) : StoredProgressResult
    data object Missing : StoredProgressResult
    data class Corrupt(val reason: String) : StoredProgressResult
    data class Unavailable(val reason: String) : StoredProgressResult
}

sealed interface StoreWriteResult {
    data class Saved(val revision: Long) : StoreWriteResult
    data class RevisionConflict(val actualRevision: Long?) : StoreWriteResult
    data class Failed(val reason: String) : StoreWriteResult
}

/** One immutable telemetry record. Sequence defines FIFO; eventId survives upload retries. */
data class PendingFactRecord(
    val sequence: Long,
    val eventId: String,
    val fact: DomainFact,
) {
    init {
        require(sequence > 0L) { "Pending fact sequence must be positive" }
        require(eventId.isNotBlank()) { "Pending fact eventId must not be blank" }
    }
}

data class PendingFactBatch(
    val records: List<PendingFactRecord>,
    /** Total telemetry records dropped locally under corruption/capacity/size pressure. */
    val droppedTelemetryCount: Long,
) {
    init {
        require(droppedTelemetryCount >= 0L) { "Dropped telemetry count must be non-negative" }
    }
}

sealed interface PendingFactsResult {
    data class Loaded(val batch: PendingFactBatch) : PendingFactsResult
    data class Incompatible(val reason: String) : PendingFactsResult
    data class Corrupt(val reason: String) : PendingFactsResult
    data class Unavailable(val reason: String) : PendingFactsResult
}

sealed interface PendingFactsAcknowledgeResult {
    data class Acknowledged(
        val throughSequence: Long,
        val removedCount: Int,
    ) : PendingFactsAcknowledgeResult

    data class Failed(val reason: String) : PendingFactsAcknowledgeResult
}

/** A coherent projection of exactly one physical save read/decode. */
sealed interface StoredStateBundleResult {
    data class Loaded(
        val snapshotsByLevel: Map<LevelId, GameSnapshot>,
        val missingLevelIds: Set<LevelId>,
        /** Null means no progress has been persisted yet. */
        val progress: PlayerProgress?,
    ) : StoredStateBundleResult

    data class Incompatible(val reason: String) : StoredStateBundleResult
    data class Corrupt(val reason: String) : StoredStateBundleResult
    data class Unavailable(val reason: String) : StoredStateBundleResult
}

/**
 * Implementations must commit snapshot, optional progress, and facts/outbox in one local transaction.
 * [expectedSnapshotRevision] provides optimistic concurrency against duplicate UI/ad callbacks.
 */
data class GameStateCommit(
    val expectedSnapshotRevision: Long,
    val snapshot: GameSnapshot,
    val facts: List<DomainFact>,
    val expectedProgressRevision: Long? = null,
    val progress: PlayerProgress? = null,
)

interface GameStateStore {
    suspend fun loadGame(levelId: LevelId): StoredGameResult
    suspend fun loadPlayerProgress(): StoredProgressResult
    suspend fun commit(commit: GameStateCommit): StoreWriteResult

    /**
     * Compatibility default for alternate stores. Persistent stores should override this method so
     * all requested levels and progress are projected from one physical read/decode.
     */
    suspend fun loadStateBundle(levelIds: Collection<LevelId>): StoredStateBundleResult {
        require(levelIds.size == levelIds.toSet().size) { "levelIds must be unique" }
        val progress = when (val result = loadPlayerProgress()) {
            is StoredProgressResult.Loaded -> result.progress
            StoredProgressResult.Missing -> null
            is StoredProgressResult.Corrupt -> return StoredStateBundleResult.Corrupt(result.reason)
            is StoredProgressResult.Unavailable -> {
                return StoredStateBundleResult.Unavailable(result.reason)
            }
        }
        val snapshots = LinkedHashMap<LevelId, GameSnapshot>(levelIds.size)
        val missing = linkedSetOf<LevelId>()
        levelIds.forEach { levelId ->
            when (val result = loadGame(levelId)) {
                is StoredGameResult.Loaded -> snapshots[levelId] = result.snapshot
                StoredGameResult.Missing -> missing += levelId
                is StoredGameResult.Incompatible -> {
                    return StoredStateBundleResult.Incompatible(result.reason)
                }
                is StoredGameResult.Corrupt -> return StoredStateBundleResult.Corrupt(result.reason)
                is StoredGameResult.Unavailable -> {
                    return StoredStateBundleResult.Unavailable(result.reason)
                }
            }
        }
        return StoredStateBundleResult.Loaded(snapshots, missing, progress)
    }

    /** Returns the oldest unacknowledged facts without mutating durable state. */
    suspend fun peekPendingFacts(limit: Int): PendingFactsResult =
        PendingFactsResult.Unavailable("Telemetry outbox is not supported")

    /** Removes records through the last sequence confirmed by the remote consumer. */
    suspend fun acknowledgePendingFacts(throughSequence: Long): PendingFactsAcknowledgeResult =
        PendingFactsAcknowledgeResult.Failed("Telemetry outbox is not supported")
}
