package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.ports.GameStateCommit
import com.example.lcb.parking.domain.ports.GameStateStore
import com.example.lcb.parking.domain.ports.LevelLoadResult
import com.example.lcb.parking.domain.ports.LevelSource
import com.example.lcb.parking.domain.ports.StoreWriteResult
import com.example.lcb.parking.domain.ports.StoredStateBundleResult
import com.example.lcb.parking.domain.progression.StarRatingCalculator
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.validation.SavedSnapshotValidator

fun interface TutorialMessageProvider {
    fun message(level: LevelDefinition): String?
}

/**
 * game-domain ports 的 feature 适配器。加载、初始会话创建和提交都由 Coordinator 切至 IO dispatcher。
 */
class DomainGameSessionStore(
    private val levelSource: LevelSource,
    private val gameStateStore: GameStateStore,
    private val levelIds: List<LevelId>,
    private val idFactory: DomainSessionIdFactory,
    private val tutorialMessageProvider: TutorialMessageProvider = TutorialMessageProvider { null },
) : GameSessionStore<DomainGameSessionAggregate> {

    init {
        require(levelIds.isNotEmpty()) { "At least one levelId is required" }
        require(levelIds.distinct().size == levelIds.size) { "levelIds must be unique" }
    }

    override suspend fun load(): DomainGameSessionAggregate {
        val levels = ArrayList<LevelDefinition>(levelIds.size)
        var levelIndex = 0
        while (levelIndex < levelIds.size) {
            levels += loadLevel(levelIds[levelIndex])
            levelIndex++
        }
        // One bundle call lets persistent stores read and decode the envelope exactly once.
        val stateBundle = when (val result = gameStateStore.loadStateBundle(levelIds)) {
            is StoredStateBundleResult.Loaded -> result
            is StoredStateBundleResult.Incompatible -> throw DomainSessionLoadException(result.reason)
            is StoredStateBundleResult.Corrupt -> throw DomainSessionLoadException(result.reason)
            is StoredStateBundleResult.Unavailable -> throw DomainSessionLoadException(result.reason)
        }
        val progress = stateBundle.progress ?: PlayerProgress()
        val snapshots = LinkedHashMap<LevelId, GameSnapshot>(levels.size)
        levelIndex = 0
        while (levelIndex < levels.size) {
            val level = levels[levelIndex]
            stateBundle.snapshotsByLevel[level.id]?.let { storedSnapshot ->
                snapshots[level.id] = validateLoadedSnapshot(level, storedSnapshot)
            }
            levelIndex++
        }
        /*
         * 业务完成与结果面板展示是两个独立持久化点。若进程死在二者之间，必须先恢复原关
         * 结算，不能因为 completedLevelIds 已写入就直接跳到下一关。
         */
        val pendingCompletionIndex = levels.indexOfFirst { candidate ->
            val candidateSnapshot = snapshots[candidate.id]
            candidateSnapshot?.attempt?.businessState == AttemptBusinessState.COMPLETE &&
                candidateSnapshot.attempt.presentationState != AttemptPresentationState.PRESENTED
        }
        val firstIncompleteIndex = levels.indexOfFirst { candidate ->
            !MainLevelProgressionPolicy.isOptional(candidate) &&
                candidate.id !in progress.completedLevelIds
        }
        val currentLevelIndex = when {
            pendingCompletionIndex >= 0 -> pendingCompletionIndex
            firstIncompleteIndex >= 0 -> firstIncompleteIndex
            else -> levels.indexOfLast { !MainLevelProgressionPolicy.isOptional(it) }
                .coerceAtLeast(0)
        }
        val level = levels[currentLevelIndex]
        val snapshot = snapshots[level.id] ?: createAndPersistInitial(
            level = level,
            progress = progress,
            progressWasMissing = stateBundle.progress == null,
        ).also {
            snapshots[level.id] = it
        }
        val isPendingCompletion =
            snapshot.attempt.businessState == AttemptBusinessState.COMPLETE &&
                snapshot.attempt.presentationState != AttemptPresentationState.PRESENTED
        val resultStars = if (snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            StarRatingCalculator.calculate(snapshot)
        } else {
            0
        }
        val restoredEarnedCoins = if (isPendingCompletion) {
            (progress.rewardedCoinsByLevel[level.id] ?: 0) +
                if (level.displayNumber == 5 && progress.l5StarterRewardClaimed) L5_STARTER_BONUS else 0
        } else {
            0
        }
        val tutorialMessages = levels.associate { candidate ->
            candidate.id to tutorialMessageProvider.message(candidate)
        }
        return DomainGameSessionAggregate(
            projection = DomainGameProjection(
                level = level,
                snapshot = snapshot,
                resultStars = resultStars,
                earnedCoins = restoredEarnedCoins,
                coinBalance = progress.coins,
                tutorialMessage = tutorialMessages[level.id],
                hasNextLevel = MainLevelProgressionPolicy.nextMainLevelIndex(
                    levels,
                    currentLevelIndex,
                ) != null,
                // 进程恢复没有待播动画：ExitCommitted 直接投影为 EXITED。
                pendingExitVehicleIds = emptySet(),
            ),
            progress = progress,
            levels = levels,
            snapshotsByLevel = snapshots,
            currentLevelIndex = currentLevelIndex,
            tutorialMessagesByLevel = tutorialMessages,
        )
    }

    override suspend fun persist(
        previous: DomainGameSessionAggregate,
        next: DomainGameSessionAggregate,
    ) {
        val progressChanged = next.progress.revision != previous.progress.revision
        val previousRevisionForTarget = previous.snapshotsByLevel[next.projection.level.id]?.revision ?: 0L
        val commit = GameStateCommit(
            expectedSnapshotRevision = previousRevisionForTarget,
            snapshot = next.projection.snapshot.stableForPersistence(),
            facts = next.pendingFacts,
            expectedProgressRevision = if (progressChanged) previous.progress.revision else null,
            progress = if (progressChanged) next.progress else null,
        )
        requireSaved(gameStateStore.commit(commit))
    }

    private suspend fun loadLevel(levelId: LevelId): LevelDefinition {
        return when (val result = levelSource.load(levelId)) {
            is LevelLoadResult.Loaded -> result.level
            LevelLoadResult.NotFound -> throw DomainSessionLoadException("Level not found: ${levelId.value}")
            is LevelLoadResult.UnsupportedRuleVersion -> throw DomainSessionLoadException(
                "Unsupported rule version: ${result.ruleVersion}",
            )
            is LevelLoadResult.Corrupt -> throw DomainSessionLoadException(result.reason)
            is LevelLoadResult.Unavailable -> throw DomainSessionLoadException(result.reason)
        }
    }

    private suspend fun createAndPersistInitial(
        level: LevelDefinition,
        progress: PlayerProgress,
        progressWasMissing: Boolean,
    ): GameSnapshot {
        val attemptId = idFactory.newAttemptId()
        val chainId = idFactory.newAttemptChainId()
        val snapshot = GameSnapshot.initial(level, attemptId, chainId)
        val commit = GameStateCommit(
            expectedSnapshotRevision = snapshot.revision,
            snapshot = snapshot,
            facts = listOf(
                DomainFact.AttemptStarted(
                    attemptId = attemptId,
                    attemptChainId = chainId,
                    continued = false,
                ),
            ),
            expectedProgressRevision = if (progressWasMissing) progress.revision else null,
            progress = if (progressWasMissing) progress else null,
        )
        requireSaved(gameStateStore.commit(commit))
        return snapshot
    }

    private fun validateLoadedSnapshot(level: LevelDefinition, snapshot: GameSnapshot): GameSnapshot {
        // Presentation locks are runtime-only. Validate the stable projection against authored
        // level data before it can enter a live session, rather than trusting DTO shape alone.
        val stableSnapshot = snapshot.stableForPersistence()
        val report = SavedSnapshotValidator.validate(level, stableSnapshot)
        if (!report.isValid) {
            val reason = report.issues
                .take(MAX_REPORTED_SNAPSHOT_ISSUES)
                .joinToString(separator = "; ") { issue -> "${issue.code}: ${issue.message}" }
            throw DomainSessionLoadException("Stored level snapshot is invalid: $reason")
        }
        return stableSnapshot
    }

    private fun requireSaved(result: StoreWriteResult) {
        when (result) {
            is StoreWriteResult.Saved -> Unit
            is StoreWriteResult.RevisionConflict -> throw DomainSessionPersistenceException(
                "Revision conflict: ${result.actualRevision}",
            )
            is StoreWriteResult.Failed -> throw DomainSessionPersistenceException(result.reason)
        }
    }
}

class DomainSessionLoadException(message: String) : IllegalStateException(message)
class DomainSessionPersistenceException(message: String) : IllegalStateException(message)

private const val L5_STARTER_BONUS = 100
private const val MAX_REPORTED_SNAPSHOT_ISSUES = 3
