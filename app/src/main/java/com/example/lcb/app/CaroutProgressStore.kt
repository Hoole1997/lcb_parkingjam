package com.example.lcb.app

import android.content.Context
import com.example.lcb.parking.feature.game.GameHomePrimaryAction
import com.example.lcb.parking.feature.game.GameHomeUiState
import com.example.lcb.parking.feature.game.LevelNodeStatus
import com.example.lcb.parking.feature.game.LevelNodeUiState
import com.example.lcb.parking.feature.game.LevelSelectUiState
import com.example.lcb.parking.feature.game.StarProgressUiState
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * carout 进度的原生持久化边界。
 *
 * Web 层传来的 JSON 永远先经过范围校验和规范化，首页与选关页只消费不可变投影，避免
 * Activity、Compose 页面和 Canvas 玩法各自维护一套解锁状态。
 */
internal class CaroutProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val accessLock = Any()

    fun load(): CaroutProgressSnapshot = synchronized(accessLock) {
        CaroutProgressCodec.decode(preferences.getString(KEY_PROGRESS, null))
    }

    fun save(rawJson: String): CaroutProgressSnapshot = synchronized(accessLock) {
        val snapshot = CaroutProgressCodec.decode(rawJson)
        // 此方法由 WebView Bridge 工作线程调用。小体积关卡存档使用 commit 同步确认，确保
        // 通关后立即关闭 Activity 或杀进程时，首页仍能读取到已经落盘的最新进度。
        check(preferences.edit().putString(KEY_PROGRESS, snapshot.toJson()).commit()) {
            "Unable to persist carout progress"
        }
        snapshot
    }

    private companion object {
        const val PREFERENCES_NAME = "carout_progress"
        const val KEY_PROGRESS = "progress_json_v1"
    }
}

/** 纯数据编解码器便于单元测试，任何异常输入都会降级成合法的新存档。 */
internal object CaroutProgressCodec {
    private val gson = Gson()

    fun decode(rawJson: String?): CaroutProgressSnapshot {
        val raw = runCatching {
            gson.fromJson(rawJson.orEmpty(), RawProgress::class.java)
        }.getOrNull() ?: RawProgress()
        val completed = linkedSetOf<Int>()
        raw.done.orEmpty().forEach { (zeroBasedKey, isDone) ->
            val index = zeroBasedKey.toIntOrNull() ?: return@forEach
            if (index in 0 until LEVEL_COUNT && isDone) {
                completed += index + 1
            }
        }
        val unlockedFromSave = (raw.unlocked ?: 1).coerceIn(1, LEVEL_COUNT)
        val unlockedFromCompletion = (completed.maxOrNull() ?: 0) + 1
        return CaroutProgressSnapshot(
            unlockedLevel = maxOf(unlockedFromSave, unlockedFromCompletion).coerceAtMost(LEVEL_COUNT),
            completedLevels = completed,
        )
    }

    fun encode(snapshot: CaroutProgressSnapshot): String = gson.toJson(
        RawProgress(
            unlocked = snapshot.unlockedLevel,
            done = snapshot.completedLevels.sorted().associate { level -> (level - 1).toString() to true },
        ),
    )

    private data class RawProgress(
        // 字段名属于 Web/Android 共同使用的持久化协议，不能依赖 Kotlin/R8 生成的成员名。
        @SerializedName("unlocked") val unlocked: Int? = null,
        @SerializedName("done") val done: Map<String, Boolean>? = null,
    )
}

internal data class CaroutProgressSnapshot(
    val unlockedLevel: Int,
    val completedLevels: Set<Int>,
) {
    val continueLevel: Int
        get() = (1..LEVEL_COUNT).firstOrNull { it !in completedLevels } ?: LEVEL_COUNT

    /** 首页、选关 UI 和点击埋点共用同一套关卡状态判定。 */
    fun levelStatus(levelNumber: Int): LevelNodeStatus = when {
        levelNumber in completedLevels -> LevelNodeStatus.COMPLETED
        levelNumber == continueLevel -> LevelNodeStatus.CURRENT
        levelNumber in 1..unlockedLevel -> LevelNodeStatus.AVAILABLE
        else -> LevelNodeStatus.LOCKED
    }

    private val starProgress: StarProgressUiState
        get() = StarProgressUiState(
            earned = completedLevels.size * STARS_PER_LEVEL,
            maximum = LEVEL_COUNT * STARS_PER_LEVEL,
        )

    fun toJson(): String = CaroutProgressCodec.encode(this)

    fun toHomeUiState(): GameHomeUiState = GameHomeUiState(
        targetLevelNumber = continueLevel,
        completedLevelCount = completedLevels.size,
        totalLevelCount = LEVEL_COUNT,
        starProgress = starProgress,
        primaryAction = if (completedLevels.size == LEVEL_COUNT) {
            GameHomePrimaryAction.NONE
        } else {
            GameHomePrimaryAction.OPEN_CURRENT_LEVEL
        },
    )

    fun toLevelSelectUiState(): LevelSelectUiState = LevelSelectUiState(
        starProgress = starProgress,
        continueLevelNumber = continueLevel,
        nodes = (1..LEVEL_COUNT).map { level ->
            LevelNodeUiState(
                levelNumber = level,
                stars = if (level in completedLevels) STARS_PER_LEVEL else 0,
                status = levelStatus(level),
                isBoss = level % BOSS_INTERVAL == 0,
                isHardPreview = level % HARD_INTERVAL == 0,
            )
        },
    )
}

internal const val LEVEL_COUNT = 30
private const val STARS_PER_LEVEL = 3
private const val BOSS_INTERVAL = 10
private const val HARD_INTERVAL = 5
