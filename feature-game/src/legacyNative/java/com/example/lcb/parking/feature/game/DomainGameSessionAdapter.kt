package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.RewardTransactionId
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.progression.ProgressionReducer
import com.example.lcb.parking.domain.progression.StarRatingCalculator
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.rules.GameCommand
import com.example.lcb.parking.domain.rules.GameReducer
import com.example.lcb.parking.domain.rules.ParkingDestination
import com.example.lcb.parking.domain.rules.PresentationIntent
import com.example.lcb.parking.domain.rules.RuleDecision
import java.util.UUID

/** 一个关卡会话的业务、进度与进程内表现投影；只有 snapshot/progress/facts 会进入数据层。 */
data class DomainGameSessionAggregate(
    val projection: DomainGameProjection,
    val progress: PlayerProgress,
    val levels: List<LevelDefinition> = listOf(projection.level),
    val snapshotsByLevel: Map<LevelId, GameSnapshot> =
        mapOf(projection.level.id to projection.snapshot),
    val currentLevelIndex: Int = 0,
    val tutorialMessagesByLevel: Map<LevelId, String?> = emptyMap(),
    val pendingFacts: List<DomainFact> = emptyList(),
    /** 离场动画必须按 effectId 精确确认，避免同车的早期高亮回调提前结束离场表现。 */
    val pendingExitEffectIdsByVehicle: Map<VehicleId, EffectId> = emptyMap(),
    val visuallyCompletedEffectIds: Set<EffectId> = emptySet(),
)

interface DomainSessionIdFactory {
    fun newEffectId(): EffectId
    fun newAttemptId(): AttemptId
    fun newAttemptChainId(): AttemptChainId
}

/** UUID 只在 Android 应用层生成并作为命令输入，领域 reducer 本身仍完全确定。 */
class UuidDomainSessionIdFactory : DomainSessionIdFactory {
    override fun newEffectId(): EffectId = EffectId(UUID.randomUUID().toString())
    override fun newAttemptId(): AttemptId = AttemptId(UUID.randomUUID().toString())
    override fun newAttemptChainId(): AttemptChainId = AttemptChainId(UUID.randomUUID().toString())
}

fun interface DomainRuleEngine {
    fun reduce(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        command: GameCommand,
    ): RuleDecision
}

/** 将 feature 命令适配为 game-domain API，并维护不落盘的动画完成投影。 */
class DomainGameReducerAdapter(
    private val idFactory: DomainSessionIdFactory,
    private val ruleEngine: DomainRuleEngine = DomainRuleEngine(GameReducer::reduce),
) : GameSessionReducer<DomainGameSessionAggregate, PresentationIntent> {

    override fun reduce(
        aggregate: DomainGameSessionAggregate,
        command: MainGameCommand,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        return when (command) {
            is MainGameCommand.TapVehicle -> applyDomainCommand(
                aggregate,
                GameCommand.TapVehicle(VehicleId(command.vehicleId), idFactory.newEffectId()),
            )
            MainGameCommand.Pause,
            MainGameCommand.HostStopped,
            -> applyDomainCommand(aggregate, GameCommand.SetPaused(paused = true))
            MainGameCommand.Resume -> applyDomainCommand(aggregate, GameCommand.SetPaused(paused = false))
            is MainGameCommand.PresentationCompleted -> confirmPresentation(aggregate, command)
            MainGameCommand.TerminalPresented -> applyDomainCommand(
                aggregate,
                GameCommand.ConfirmTerminalPresentation(idFactory.newEffectId()),
            )
            MainGameCommand.NextLevel -> nextLevel(aggregate)
            is MainGameCommand.OpenLevel -> openLevel(aggregate, command.levelNumber)
            MainGameCommand.QuitToHome -> applyDomainCommand(
                aggregate,
                GameCommand.Quit(idFactory.newEffectId()),
            )
            MainGameCommand.RestartCurrentLevel -> applyDomainCommand(
                aggregate,
                GameCommand.Restart(
                    newAttemptId = idFactory.newAttemptId(),
                    newAttemptChainId = idFactory.newAttemptChainId(),
                    effectId = idFactory.newEffectId(),
                ),
            )
            MainGameCommand.Retry -> unchanged(aggregate)
        }
    }

    private fun nextLevel(
        aggregate: DomainGameSessionAggregate,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        if (aggregate.projection.snapshot.attempt.businessState != AttemptBusinessState.COMPLETE) {
            return unchanged(aggregate)
        }
        val nextIndex = MainLevelProgressionPolicy.nextMainLevelIndex(
            aggregate.levels,
            aggregate.currentLevelIndex,
        ) ?: return unchanged(aggregate)
        return activateLevel(aggregate, nextIndex)
    }

    private fun openLevel(
        aggregate: DomainGameSessionAggregate,
        levelNumber: Int,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        val targetIndex = aggregate.levels.indexOfFirst { it.displayNumber == levelNumber }
        if (
            targetIndex < 0 ||
            !MainLevelProgressionPolicy.isUnlocked(
                aggregate.levels,
                targetIndex,
                aggregate.progress.completedLevelIds,
            )
        ) {
            return unchanged(aggregate)
        }
        return activateLevel(aggregate, targetIndex)
    }

    /**
     * 切换关卡时只恢复仍可继续的 ACTIVE Attempt；已结算、失败或主动退出的关卡创建新 Attempt。
     * 新快照沿用该关旧 revision 的下一值，满足 DataStore 的乐观锁约束。
     */
    private fun activateLevel(
        aggregate: DomainGameSessionAggregate,
        targetIndex: Int,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        val targetLevel = aggregate.levels[targetIndex]
        val storedSnapshot = aggregate.snapshotsByLevel[targetLevel.id]
        if (storedSnapshot == null) {
            val attemptId = idFactory.newAttemptId()
            val chainId = idFactory.newAttemptChainId()
            val snapshot = GameSnapshot.initial(
                targetLevel,
                attemptId,
                chainId,
            )
            val facts = listOf(
                DomainFact.AttemptStarted(
                    attemptId = attemptId,
                    attemptChainId = chainId,
                    continued = false,
                ),
            )
            val next = aggregateForLevel(
                aggregate = aggregate,
                levelIndex = targetIndex,
                snapshot = snapshot,
                pendingFacts = facts,
            )
            return GameSessionDecision(next, requiresPersistence = true)
        }

        val selected = aggregateForLevel(
            aggregate = aggregate,
            levelIndex = targetIndex,
            snapshot = storedSnapshot,
        )
        return when {
            storedSnapshot.attempt.businessState != AttemptBusinessState.ACTIVE -> {
                applyDomainCommand(
                    selected,
                    GameCommand.Restart(
                        newAttemptId = idFactory.newAttemptId(),
                        newAttemptChainId = idFactory.newAttemptChainId(),
                        effectId = idFactory.newEffectId(),
                    ),
                )
            }
            storedSnapshot.paused -> applyDomainCommand(
                selected,
                GameCommand.SetPaused(paused = false),
            )
            else -> unchanged(selected)
        }
    }

    private fun aggregateForLevel(
        aggregate: DomainGameSessionAggregate,
        levelIndex: Int,
        snapshot: GameSnapshot,
        pendingFacts: List<DomainFact> = emptyList(),
    ): DomainGameSessionAggregate {
        val level = aggregate.levels[levelIndex]
        val resultStars = if (snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            StarRatingCalculator.calculate(snapshot)
        } else {
            0
        }
        return aggregate.copy(
            projection = DomainGameProjection(
                level = level,
                snapshot = snapshot,
                resultStars = resultStars,
                earnedCoins = 0,
                coinBalance = aggregate.progress.coins,
                tutorialMessage = aggregate.tutorialMessagesByLevel[level.id],
                hasNextLevel = MainLevelProgressionPolicy.nextMainLevelIndex(
                    aggregate.levels,
                    levelIndex,
                ) != null,
                pendingExitVehicleIds = emptySet(),
            ),
            snapshotsByLevel = aggregate.snapshotsByLevel + (level.id to snapshot),
            currentLevelIndex = levelIndex,
            pendingFacts = pendingFacts,
            pendingExitEffectIdsByVehicle = emptyMap(),
            visuallyCompletedEffectIds = emptySet(),
        )
    }

    private fun confirmPresentation(
        aggregate: DomainGameSessionAggregate,
        command: MainGameCommand.PresentationCompleted,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        val effectId = EffectId(command.effectId)
        if (effectId in aggregate.visuallyCompletedEffectIds) return unchanged(aggregate)
        val vehicleId = command.vehicleId?.let(::VehicleId)
        val pendingExits = aggregate.projection.pendingExitVehicleIds
        if (
            vehicleId != null &&
            aggregate.pendingExitEffectIdsByVehicle[vehicleId] == effectId
        ) {
            val remainingPendingExits = pendingExits - vehicleId
            val visuallyCompleted = rememberCompletedEffect(aggregate.visuallyCompletedEffectIds, effectId)
            val visuallyUpdated = aggregate.copy(
                projection = aggregate.projection.copy(pendingExitVehicleIds = remainingPendingExits),
                pendingFacts = emptyList(),
                pendingExitEffectIdsByVehicle =
                    aggregate.pendingExitEffectIdsByVehicle - vehicleId,
                visuallyCompletedEffectIds = visuallyCompleted,
            )
            // 这里仅解除动画门；结果面板真正可见时再由 TerminalPresented 持久化 PRESENTED。
            return unchanged(visuallyUpdated)
        }

        if (
            vehicleId != null &&
            aggregate.projection.snapshot.transientVehicleLocks[vehicleId] == effectId
        ) {
            return applyDomainCommand(
                aggregate.copy(
                    visuallyCompletedEffectIds = rememberCompletedEffect(
                        aggregate.visuallyCompletedEffectIds,
                        effectId,
                    ),
                ),
                GameCommand.ConfirmCollisionPresentation(vehicleId, effectId),
            )
        }
        return unchanged(
            aggregate.copy(
                visuallyCompletedEffectIds = rememberCompletedEffect(
                    aggregate.visuallyCompletedEffectIds,
                    effectId,
                ),
            ),
        )
    }

    /** 只保留近期去重窗口，避免长会话中的动画确认 ID 无界增长。 */
    private fun rememberCompletedEffect(
        completed: Set<EffectId>,
        effectId: EffectId,
    ): Set<EffectId> {
        if (effectId in completed) return completed
        if (completed.size < MAX_COMPLETED_EFFECT_IDS) return completed + effectId

        val retained = LinkedHashSet<EffectId>(MAX_COMPLETED_EFFECT_IDS)
        val iterator = completed.iterator()
        if (iterator.hasNext()) iterator.next()
        while (iterator.hasNext()) retained += iterator.next()
        retained += effectId
        return retained
    }

    private fun applyDomainCommand(
        aggregate: DomainGameSessionAggregate,
        command: GameCommand,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        return when (
            val decision = ruleEngine.reduce(
                aggregate.projection.level,
                aggregate.projection.snapshot,
                command,
            )
        ) {
            is RuleDecision.Rejected -> GameSessionDecision(
                aggregate = aggregate.copy(pendingFacts = emptyList()),
                presentationIntents = decision.presentationIntents,
                requiresPersistence = false,
            )
            is RuleDecision.Applied -> applied(aggregate, decision)
        }
    }

    private fun applied(
        current: DomainGameSessionAggregate,
        decision: RuleDecision.Applied,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        var progress = current.progress
        var stars = current.projection.resultStars
        var earnedCoins = current.projection.earnedCoins
        val becameComplete =
            current.projection.snapshot.attempt.businessState != AttemptBusinessState.COMPLETE &&
                decision.snapshot.attempt.businessState == AttemptBusinessState.COMPLETE
        if (becameComplete) {
            val transactionId = RewardTransactionId(
                "completion:${decision.snapshot.attempt.attemptId.value}",
            )
            val reward = ProgressionReducer.settleCompletion(
                level = current.projection.level,
                snapshot = decision.snapshot,
                progress = current.progress,
                transactionId = transactionId,
            )
            progress = reward.progress
            stars = reward.stars
            earnedCoins = reward.awardedCoins + reward.starterBonusCoins
        }

        val pendingExitIds = current.projection.pendingExitVehicleIds.toMutableSet()
        val pendingExitEffects = current.pendingExitEffectIdsByVehicle.toMutableMap()
        var committedExit = false
        var index = 0
        while (index < decision.presentationIntents.size) {
            val intent = decision.presentationIntents[index]
            if (intent is PresentationIntent.ExitCommitted) {
                pendingExitIds += intent.vehicleId
                pendingExitEffects[intent.vehicleId] = intent.effectId
                committedExit = true
            }
            index++
        }
        val next = current.copy(
            projection = current.projection.copy(
                snapshot = decision.snapshot,
                resultStars = stars,
                earnedCoins = earnedCoins,
                coinBalance = progress.coins,
                tutorialMessage = if (committedExit) null else current.projection.tutorialMessage,
                pendingExitVehicleIds = pendingExitIds,
            ),
            progress = progress,
            snapshotsByLevel = current.snapshotsByLevel +
                (current.projection.level.id to decision.snapshot),
            pendingFacts = decision.facts,
            pendingExitEffectIdsByVehicle = pendingExitEffects,
        )
        return GameSessionDecision(
            aggregate = next,
            presentationIntents = decision.presentationIntents,
            requiresPersistence = decision.requiresPersistence,
        )
    }

    private fun unchanged(
        aggregate: DomainGameSessionAggregate,
    ): GameSessionDecision<DomainGameSessionAggregate, PresentationIntent> {
        return GameSessionDecision(
            aggregate = aggregate.copy(pendingFacts = emptyList()),
            requiresPersistence = false,
        )
    }

    private companion object {
        const val MAX_COMPLETED_EFFECT_IDS = 64
    }
}

/** 把领域表现 Intent 映射成轻量 Canvas 动画；未知/无动画 Intent 安全跳过。 */
class DomainPresentationEffectMapper :
    PresentationEffectMapper<DomainGameSessionAggregate, PresentationIntent> {

    override fun map(
        aggregate: DomainGameSessionAggregate,
        intent: PresentationIntent,
    ): GamePresentationEffect? {
        val level = aggregate.projection.level
        return when (intent) {
            is PresentationIntent.ExitCommitted -> {
                val vehicle = level.vehicleById[intent.vehicleId] ?: return null
                val distance = intent.sweepPath.size + vehicle.length
                val renderVehicle = vehicle.toRenderModel(VehicleVisualState.MOVING)
                val parkingMotion = ParkingMotionSpec(
                    arrivingVehicle = renderVehicle,
                    destination = when (val destination = intent.parkingDestination) {
                        is ParkingDestination.Slot -> {
                            ParkingMotionDestination.WaitingSlot(destination.slotIndex)
                        }
                        is ParkingDestination.Order -> ParkingMotionDestination.CurrentOrder
                        ParkingDestination.Bypass -> ParkingMotionDestination.Bypass
                    },
                    // Reducer 已按 arrivalSequence 排好顺序；表现层严格保留该顺序。
                    dispatches = intent.parkingDispatches.mapNotNull { dispatch ->
                        level.vehicleById[dispatch.vehicleId]?.let { dispatchedVehicle ->
                            ParkingDispatchMotion(
                                vehicleId = dispatch.vehicleId.value,
                                fromSlotIndex = dispatch.fromSlotIndex,
                                artVariant = dispatchedVehicle.color.toArtVariant(),
                                lengthCells = dispatchedVehicle.length,
                            )
                        }
                    },
                )
                GamePresentationEffect.MoveVehicle(
                    effectId = intent.effectId.value,
                    vehicleId = intent.vehicleId.value,
                    deltaRows = vehicle.direction.dy * distance.toFloat(),
                    deltaColumns = vehicle.direction.dx * distance.toFloat(),
                    durationMillis = EXIT_DURATION_MILLIS,
                    hideSourceUntilStateUpdate = true,
                    renderVehicle = renderVehicle,
                    parkingMotion = parkingMotion,
                )
            }
            is PresentationIntent.Collision -> {
                val vehicle = level.vehicleById[intent.vehicleId] ?: return null
                GamePresentationEffect.ReboundVehicle(
                    effectId = intent.effectId.value,
                    vehicleId = intent.vehicleId.value,
                    deltaRows = vehicle.direction.dy * REBOUND_CELLS,
                    deltaColumns = vehicle.direction.dx * REBOUND_CELLS,
                )
            }
            is PresentationIntent.ParkingLotFull -> {
                val vehicle = level.vehicleById[intent.vehicleId] ?: return null
                // 满位拒绝仍只反馈当前车辆；停车场容量由 UiState 同步高亮，不冻结整张棋盘。
                GamePresentationEffect.ReboundVehicle(
                    effectId = intent.effectId.value,
                    vehicleId = intent.vehicleId.value,
                    deltaRows = vehicle.direction.dy * REBOUND_CELLS,
                    deltaColumns = vehicle.direction.dx * REBOUND_CELLS,
                )
            }
            is PresentationIntent.LockedVehicleFeedback -> GamePresentationEffect.HighlightVehicle(
                effectId = intent.effectId.value,
                vehicleId = intent.vehicleId.value,
            )
            is PresentationIntent.VehicleTowed,
            is PresentationIntent.ShieldApplied,
            is PresentationIntent.FailureReady,
            is PresentationIntent.AttemptContinued,
            is PresentationIntent.AttemptRestarted,
            is PresentationIntent.AttemptQuit,
            -> null
        }
    }

    private companion object {
        const val EXIT_DURATION_MILLIS = 450L
        const val REBOUND_CELLS = 0.18f
    }
}
