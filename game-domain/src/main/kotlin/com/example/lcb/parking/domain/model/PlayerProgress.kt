package com.example.lcb.parking.domain.model

data class ToolInventory(
    val hints: Int = 0,
    val shields: Int = 0,
    val tows: Int = 0,
)

/** Persisted player-owned state. Transaction IDs make every currency award idempotent. */
data class PlayerProgress(
    val coins: Long = 0L,
    val bestStarsByLevel: Map<LevelId, Int> = emptyMap(),
    val rewardedCoinsByLevel: Map<LevelId, Int> = emptyMap(),
    val completedLevelIds: Set<LevelId> = emptySet(),
    val appliedRewardTransactionIds: Set<RewardTransactionId> = emptySet(),
    val l5StarterRewardClaimed: Boolean = false,
    val inventory: ToolInventory = ToolInventory(),
    val revision: Long = 0L,
)

