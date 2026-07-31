package com.example.lcb.parking.data.state

import com.google.gson.annotations.SerializedName

/** Preferences 中 envelope payload 的稳定 DTO；禁止直接序列化 Domain 对象。 */
internal data class GameStatePayloadDto(
    @SerializedName("game_snapshots") val gameSnapshots: List<GameSnapshotDto> = emptyList(),
    @SerializedName("player_progress") val playerProgress: PlayerProgressDto? = null,
    @SerializedName("telemetry_outbox") val telemetryOutbox: TelemetryOutboxDto? = TelemetryOutboxDto(),
)

/** Telemetry is intentionally bounded and never acts as reward or progression truth. */
internal data class TelemetryOutboxDto(
    val records: List<PendingFactRecordDto?> = emptyList(),
    @SerializedName("next_sequence") val nextSequence: Long = 1L,
    @SerializedName("dropped_telemetry_count") val droppedTelemetryCount: Long = 0L,
)

internal data class PendingFactRecordDto(
    val sequence: Long,
    @SerializedName("event_id") val eventId: String?,
    val fact: DomainFactDto?,
)

internal data class GameSnapshotDto(
    @SerializedName("level_id") val levelId: String,
    @SerializedName("level_version") val levelVersion: Int,
    @SerializedName("rule_version") val ruleVersion: Int,
    val board: BoardSnapshotDto,
    @SerializedName("parking_lot") val parkingLot: ParkingLotSnapshotDto,
    val attempt: AttemptSnapshotDto,
    val chain: AttemptChainSnapshotDto,
    val safety: SafetyStateDto,
    val paused: Boolean,
    val revision: Long,
)

/** Ordered-list form keeps save JSON deterministic and allows duplicate-order corruption checks. */
internal data class ParkingLotSnapshotDto(
    val slots: List<WaitingVehicleDto?>,
    @SerializedName("fulfilled_orders") val fulfilledOrders: List<FulfilledOrderDto>,
    @SerializedName("next_arrival_sequence") val nextArrivalSequence: Long,
)

internal data class WaitingVehicleDto(
    @SerializedName("vehicle_id") val vehicleId: String,
    @SerializedName("arrival_sequence") val arrivalSequence: Long,
)

internal data class FulfilledOrderDto(
    @SerializedName("order_id") val orderId: String,
    @SerializedName("vehicle_ids") val vehicleIds: List<String>,
)

internal data class BoardSnapshotDto(
    val vehicles: List<VehicleRuleStateDto>,
    @SerializedName("open_gate_ids") val openGateIds: List<String>,
    @SerializedName("next_commit_sequence") val nextCommitSequence: Long,
)

internal data class VehicleRuleStateDto(
    @SerializedName("vehicle_id") val vehicleId: String,
    val state: String,
    @SerializedName("key_vehicle_id") val keyVehicleId: String? = null,
    @SerializedName("exit_id") val exitId: String? = null,
    @SerializedName("commit_sequence") val commitSequence: Long? = null,
)

internal data class AttemptSnapshotDto(
    @SerializedName("attempt_id") val attemptId: String,
    @SerializedName("attempt_chain_id") val attemptChainId: String,
    @SerializedName("parent_attempt_id") val parentAttemptId: String? = null,
    @SerializedName("business_state") val businessState: String,
    @SerializedName("presentation_state") val presentationState: String,
)

internal data class AttemptChainSnapshotDto(
    val id: String,
    @SerializedName("collision_count") val collisionCount: Int,
    @SerializedName("tutorial_mistake_count") val tutorialMistakeCount: Int,
    @SerializedName("shield_used") val shieldUsed: Boolean,
    @SerializedName("tow_used") val towUsed: Boolean,
    @SerializedName("continue_used") val continueUsed: Boolean,
)

internal data class SafetyStateDto(
    val mode: String,
    val initial: Int? = null,
    val remaining: Int? = null,
)

internal data class PlayerProgressDto(
    val coins: Long,
    @SerializedName("best_stars_by_level") val bestStarsByLevel: List<LevelIntEntryDto>,
    @SerializedName("rewarded_coins_by_level") val rewardedCoinsByLevel: List<LevelIntEntryDto>,
    @SerializedName("completed_level_ids") val completedLevelIds: List<String>,
    @SerializedName("applied_reward_transaction_ids") val appliedRewardTransactionIds: List<String>,
    @SerializedName("l5_starter_reward_claimed") val l5StarterRewardClaimed: Boolean,
    val inventory: ToolInventoryDto,
    val revision: Long,
)

internal data class LevelIntEntryDto(
    @SerializedName("level_id") val levelId: String,
    val value: Int,
)

internal data class ToolInventoryDto(
    val hints: Int,
    val shields: Int,
    val tows: Int,
)

/** DomainFact 的确定性 tagged-union；字段随事实类型逐步扩充。 */
internal data class DomainFactDto(
    val type: String,
    @SerializedName("attempt_id") val attemptId: String? = null,
    @SerializedName("attempt_chain_id") val attemptChainId: String? = null,
    @SerializedName("parent_attempt_id") val parentAttemptId: String? = null,
    @SerializedName("vehicle_id") val vehicleId: String? = null,
    @SerializedName("color_id") val colorId: String? = null,
    @SerializedName("order_id") val orderId: String? = null,
    @SerializedName("slot_index") val slotIndex: Int? = null,
    @SerializedName("from_slot_index") val fromSlotIndex: Int? = null,
    @SerializedName("arrival_sequence") val arrivalSequence: Long? = null,
    val capacity: Int? = null,
    @SerializedName("commit_sequence") val commitSequence: Long? = null,
    @SerializedName("opened_gate_ids") val openedGateIds: List<String> = emptyList(),
    @SerializedName("collision_kind") val collisionKind: String? = null,
    @SerializedName("chain_collision_count") val chainCollisionCount: Int? = null,
    /** Read-only compatibility field for V1 collision facts retained in an upgraded outbox. */
    @SerializedName("safety_remaining") val safetyRemaining: Int? = null,
    @SerializedName("tutorial_mistake_count") val tutorialMistakeCount: Int? = null,
    val tool: String? = null,
    val result: String? = null,
    val stars: Int? = null,
    val continued: Boolean? = null,
    @SerializedName("effect_id") val effectId: String? = null,
)
