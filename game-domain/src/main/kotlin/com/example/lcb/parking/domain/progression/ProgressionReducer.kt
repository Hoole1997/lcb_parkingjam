package com.example.lcb.parking.domain.progression

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.RewardProfile
import com.example.lcb.parking.domain.model.RewardTransactionId
import com.example.lcb.parking.domain.rules.GameReducer

data class CompletionReward(
    val progress: PlayerProgress,
    val stars: Int,
    val awardedCoins: Int,
    val starterBonusCoins: Int,
    val duplicate: Boolean,
)

/** Pure, idempotent completion ledger. UI animation and analytics never act as a truth source. */
object ProgressionReducer {
    private const val L5_STARTER_BONUS = 100

    fun settleCompletion(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        progress: PlayerProgress,
        transactionId: RewardTransactionId,
    ): CompletionReward {
        require(snapshot.levelId == level.id) { "Snapshot and level IDs differ" }
        require(snapshot.levelVersion == level.levelVersion) { "Snapshot level version is stale" }
        require(snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            "Only a completed attempt can be settled"
        }

        val stars = StarRatingCalculator.calculate(snapshot)
        if (transactionId in progress.appliedRewardTransactionIds) {
            return CompletionReward(progress, stars, 0, 0, duplicate = true)
        }

        val previousRewardedCoins = progress.rewardedCoinsByLevel[level.id] ?: 0
        val targetRewardCoins = rewardFor(level.rewardProfile, stars)
        val rewardDelta = (targetRewardCoins - previousRewardedCoins).coerceAtLeast(0)
        val starterBonus = if (level.displayNumber == 5 && !progress.l5StarterRewardClaimed) {
            L5_STARTER_BONUS
        } else {
            0
        }
        val previousBestStars = progress.bestStarsByLevel[level.id] ?: 0

        val updated = progress.copy(
            coins = progress.coins + rewardDelta + starterBonus,
            bestStarsByLevel = progress.bestStarsByLevel +
                (level.id to maxOf(previousBestStars, stars)),
            rewardedCoinsByLevel = progress.rewardedCoinsByLevel +
                (level.id to maxOf(previousRewardedCoins, targetRewardCoins)),
            completedLevelIds = progress.completedLevelIds + level.id,
            appliedRewardTransactionIds = progress.appliedRewardTransactionIds + transactionId,
            l5StarterRewardClaimed = progress.l5StarterRewardClaimed || level.displayNumber == 5,
            revision = progress.revision + 1,
        )
        return CompletionReward(updated, stars, rewardDelta, starterBonus, duplicate = false)
    }

    private fun rewardFor(profile: RewardProfile, stars: Int): Int = when (profile) {
        RewardProfile.Normal -> when (stars) {
            1 -> 20
            2 -> 25
            else -> 35
        }
        is RewardProfile.Boss -> profile.baseCoins + when (stars) {
            1 -> 0
            2 -> 5
            else -> 15
        }
    }
}

object StarRatingCalculator {
    fun calculate(snapshot: GameSnapshot): Int {
        require(snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            "Stars are defined only for completed attempts"
        }
        return GameReducer.starsFor(
            ruleVersion = snapshot.ruleVersion,
            chainCollisionCount = snapshot.chain.collisionCount,
            towUsed = snapshot.chain.towUsed,
        )
    }
}
