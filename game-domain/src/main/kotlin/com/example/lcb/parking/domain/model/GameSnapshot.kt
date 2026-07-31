package com.example.lcb.parking.domain.model

/**
 * V3 车辆业务生命周期。动画只能确认这些状态，不能在 UI 内另建一份玩法真相。
 */
sealed interface VehicleRuleState {
    data object OnBoard : VehicleRuleState
    data class Locked(val keyVehicleId: VehicleId) : VehicleRuleState

    /** 已离开棋盘并预订车槽，等待进槽动画确认。 */
    data class DrivingToSlot(
        val exitId: ExitId,
        val commitSequence: Long,
        val slotIndex: Int,
        val effectId: EffectId,
    ) : VehicleRuleState

    /** 已到车槽，只有该状态可以接队首同色乘客。 */
    data class ParkedLoading(
        val slotIndex: Int,
        val seats: Int,
    ) : VehicleRuleState

    /** 已坐满并释放车槽，严格按 [departureSequence] 等待离场。 */
    data class WaitingToDepart(
        val slotIndex: Int,
        val seats: Int,
        val departureSequence: Long,
    ) : VehicleRuleState

    /** 当前唯一正在播放离场动画的车辆。 */
    data class Leaving(
        val slotIndex: Int,
        val seats: Int,
        val departureSequence: Long,
        val effectId: EffectId,
    ) : VehicleRuleState

    data object Gone : VehicleRuleState
}

val VehicleRuleState.exitCommitted: Boolean
    get() = this !is VehicleRuleState.OnBoard && this !is VehicleRuleState.Locked

data class BoardSnapshot(
    val vehicles: Map<VehicleId, VehicleRuleState>,
    val openGateIds: Set<GateId>,
    val nextCommitSequence: Long = 1L,
)

/** 唯一在途乘客；队首在创建 transit 时移出，抵达后才增加座位。 */
data class PassengerTransit(
    val effectId: EffectId,
    val vehicleId: VehicleId,
    val color: VehicleColor,
    val slotIndex: Int,
    val resultSeat: Int,
    val capacity: Int,
)

/**
 * slots 同时包含驶来途中和已停靠车辆；坐满时立即释放。departureQueue 的首项要么
 * Leaving，要么即将开始 Leaving，其余必须为 WaitingToDepart。
 */
data class ParkingLotSnapshot(
    val slots: List<VehicleId?>,
    val passengerQueue: List<VehicleColor>,
    val passengerTransit: PassengerTransit? = null,
    val departureQueue: List<VehicleId> = emptyList(),
    val nextDepartureSequence: Long = 1L,
    /** 跨进程持久化的短幂等窗口，防止动画完成回调重复消费乘客。 */
    val recentlyHandledEffectIds: List<EffectId> = emptyList(),
) {
    fun hasHandled(effectId: EffectId): Boolean = effectId in recentlyHandledEffectIds

    fun withHandled(effectId: EffectId): ParkingLotSnapshot {
        if (hasHandled(effectId)) return this
        return copy(
            recentlyHandledEffectIds = (recentlyHandledEffectIds + effectId)
                .takeLast(MAX_RECENT_EFFECT_IDS),
        )
    }

    companion object {
        const val MAX_RECENT_EFFECT_IDS: Int = 64

        fun initial(rules: ParkingRules): ParkingLotSnapshot = ParkingLotSnapshot(
            slots = List(rules.capacity.coerceAtLeast(0)) { null },
            passengerQueue = rules.expandedPassengerQueue(),
        )
    }
}

sealed interface SafetyState {
    data object TutorialUnlimited : SafetyState
    data class Limited(val initial: Int, val remaining: Int) : SafetyState
}

enum class AttemptBusinessState { ACTIVE, COMPLETE, FAIL, QUIT, CONTENT_ERROR }
enum class AttemptPresentationState { PLAYING, COMPLETION_PENDING, FAILURE_PENDING, PRESENTED }

data class AttemptSnapshot(
    val attemptId: AttemptId,
    val attemptChainId: AttemptChainId,
    val parentAttemptId: AttemptId? = null,
    val businessState: AttemptBusinessState = AttemptBusinessState.ACTIVE,
    val presentationState: AttemptPresentationState = AttemptPresentationState.PLAYING,
)

data class AttemptChainSnapshot(
    val id: AttemptChainId,
    val collisionCount: Int = 0,
    val tutorialMistakeCount: Int = 0,
    val shieldUsed: Boolean = false,
    val towUsed: Boolean = false,
    val continueUsed: Boolean = false,
)

data class TransientVehicleLock(val vehicleId: VehicleId, val effectId: EffectId)

data class GameSnapshot(
    val levelId: LevelId,
    val levelVersion: Int,
    val ruleVersion: Int,
    val board: BoardSnapshot,
    val parkingLot: ParkingLotSnapshot,
    val attempt: AttemptSnapshot,
    val chain: AttemptChainSnapshot,
    val safety: SafetyState,
    val paused: Boolean = false,
    val transientVehicleLocks: Map<VehicleId, EffectId> = emptyMap(),
    val revision: Long = 0L,
) {
    val transientLockedVehicleIds: Set<VehicleId>
        get() = transientVehicleLocks.keys

    /** 碰撞锁只约束当前进程的动画，不写入持久层。 */
    fun stableForPersistence(): GameSnapshot = copy(transientVehicleLocks = emptyMap())

    companion object {
        fun initial(level: LevelDefinition, attemptId: AttemptId, chainId: AttemptChainId): GameSnapshot {
            val vehicleStates = level.vehicles.associate { vehicle ->
                vehicle.id to (vehicle.lockedBy?.let(VehicleRuleState::Locked) ?: VehicleRuleState.OnBoard)
            }
            val safety = when (val configured = level.initialSafety) {
                InitialSafety.TutorialUnlimited -> SafetyState.TutorialUnlimited
                is InitialSafety.Limited -> SafetyState.Limited(configured.points, configured.points)
            }
            return GameSnapshot(
                levelId = level.id,
                levelVersion = level.levelVersion,
                ruleVersion = level.ruleVersion,
                board = BoardSnapshot(
                    vehicles = vehicleStates,
                    openGateIds = level.gates.filter(GateDefinition::initiallyOpen)
                        .mapTo(mutableSetOf(), GateDefinition::id),
                ),
                parkingLot = ParkingLotSnapshot.initial(level.parkingRules),
                attempt = AttemptSnapshot(attemptId, chainId),
                chain = AttemptChainSnapshot(chainId),
                safety = safety,
            )
        }
    }
}
