package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.PlayerProgress

/**
 * 首页与关卡地图共享的只读进度投影。
 *
 * 它只包含轻量展示数据，不暴露完整关卡定义、存档实现或 Android 类型。
 */
data class GameProgressUiState(
    val coins: Long = 0L,
    val totalStars: Int = 0,
    val completedLevelCount: Int = 0,
    val totalLevelCount: Int = 1,
    val continueLevelNumber: Int = 1,
    val allMainLevelsCompleted: Boolean = false,
    val levelNodes: List<LevelNodeUiState> = emptyList(),
) {
    init {
        require(coins >= 0L) { "Coins cannot be negative" }
        require(totalStars >= 0) { "Total stars cannot be negative" }
        require(totalLevelCount > 0) { "Total level count must be positive" }
        require(completedLevelCount in 0..totalLevelCount) {
            "Completed level count must be within total level count"
        }
        require(continueLevelNumber > 0) { "Continue level number must be positive" }
    }
}

/**
 * 前 30 关的单一解锁与主线路由策略。
 *
 * L26 是关卡包声明的可跳过 Hard 预览：完成 L25 后 L26、L27 同时可选；主线“下一关”
 * 和首页“继续”会前往 L27。可跳过标记与前置关卡均来自 [LevelDefinition.progression]，
 * 以后新增必须完成的 Hard 关不会被难度枚举误判为支线。
 */
internal object MainLevelProgressionPolicy {

    fun isOptional(level: LevelDefinition): Boolean = level.progression.skippable

    fun continueLevelIndex(
        levels: List<LevelDefinition>,
        completedLevelIds: Set<LevelId>,
    ): Int {
        require(levels.isNotEmpty()) { "At least one level is required" }
        return levels.indexOfFirst { level ->
            !isOptional(level) && level.id !in completedLevelIds
        }.takeIf { it >= 0 } ?: levels.indexOfLast { !isOptional(it) }.coerceAtLeast(0)
    }

    fun nextMainLevelIndex(
        levels: List<LevelDefinition>,
        currentLevelIndex: Int,
    ): Int? {
        if (currentLevelIndex !in levels.indices) return null
        var index = currentLevelIndex + 1
        while (index < levels.size) {
            if (!isOptional(levels[index])) return index
            index++
        }
        return null
    }

    fun isUnlocked(
        levels: List<LevelDefinition>,
        targetIndex: Int,
        completedLevelIds: Set<LevelId>,
    ): Boolean {
        if (targetIndex !in levels.indices) return false
        val target = levels[targetIndex]
        if (target.id in completedLevelIds || targetIndex == 0) return true

        val prerequisites = target.progression.prerequisiteLevelIds
        if (prerequisites.isNotEmpty()) {
            return prerequisites.all(completedLevelIds::contains)
        }
        // 兼容测试/动态关卡未声明 progression 的线性关卡包。
        return levels.getOrNull(targetIndex - 1)?.id in completedLevelIds
    }
}

/** 将可靠的玩家存档投影为首页和地图使用的轻量状态。 */
internal object GameProgressUiMapper {

    fun map(aggregate: DomainGameSessionAggregate): GameProgressUiState {
        val levels = aggregate.levels
        require(levels.isNotEmpty()) { "At least one level is required" }
        val progress = aggregate.progress
        val knownLevelIds = levels.mapTo(linkedSetOf(), LevelDefinition::id)
        val completedIds = progress.completedLevelIds.intersect(knownLevelIds)
        val continueIndex = MainLevelProgressionPolicy.continueLevelIndex(levels, completedIds)
        val continueLevelNumber = levels[continueIndex].displayNumber
        val mainLevels = levels.filterNot(MainLevelProgressionPolicy::isOptional)
        val allMainCompleted = mainLevels.all { it.id in completedIds }

        val nodes = ArrayList<LevelNodeUiState>(levels.size)
        var index = 0
        while (index < levels.size) {
            val level = levels[index]
            val completed = level.id in completedIds
            val unlocked = MainLevelProgressionPolicy.isUnlocked(levels, index, completedIds)
            val status = when {
                completed -> LevelNodeStatus.COMPLETED
                index == continueIndex -> LevelNodeStatus.CURRENT
                unlocked -> LevelNodeStatus.AVAILABLE
                else -> LevelNodeStatus.LOCKED
            }
            nodes += LevelNodeUiState(
                levelNumber = level.displayNumber,
                stars = progress.bestStarsByLevel[level.id] ?: 0,
                status = status,
                isBoss = level.mode == LevelMode.BOSS || BOSS_CONTENT_TAG in level.contentTags,
                isHardPreview = MainLevelProgressionPolicy.isOptional(level),
            )
            index++
        }

        return GameProgressUiState(
            coins = progress.coins,
            totalStars = progress.bestStarsByLevel
                .filterKeys(knownLevelIds::contains)
                .values
                .sum(),
            completedLevelCount = completedIds.size,
            totalLevelCount = levels.size,
            continueLevelNumber = continueLevelNumber,
            allMainLevelsCompleted = allMainCompleted,
            levelNodes = nodes,
        )
    }
    private const val BOSS_CONTENT_TAG = "boss"
}
