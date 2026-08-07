package com.example.lcb.app

import android.os.SystemClock
import android.util.Log
import com.example.lcb.parking.feature.game.GameActionType
import com.example.lcb.parking.feature.game.GameLevelEntry
import com.example.lcb.parking.feature.game.GameResultActionType
import com.example.lcb.parking.feature.game.GameResultType
import com.example.lcb.parking.feature.game.LevelNodeStatus
import net.corekit.core.report.ReportDataManager

/** 页面名称的唯一数据口径，避免 Activity 直接拼接埋点字符串。 */
internal enum class AnalyticsPage(val value: String) {
    GAME_HOME("game_home"),
    LEVEL_SELECT("level_select"),
    GAMEPLAY("gameplay"),
}

/** 将具体统计 SDK 隔离在应用边界，业务类和单元测试只依赖这个窄接口。 */
internal fun interface AnalyticsEventSink {
    fun report(eventName: String, data: Map<String, Any>)
}

internal object CoreKitAnalyticsEventSink : AnalyticsEventSink {
    override fun report(eventName: String, data: Map<String, Any>) {
        runCatching {
            ReportDataManager.reportData(eventName, data)
        }.onFailure { error ->
            // 统计 SDK 异常不得影响页面跳转、游戏结算或广告发奖。
            Log.w(LOG_TAG, "Failed to report event=$eventName", error)
        }
    }

    private const val LOG_TAG = "GameAnalytics"
}

/**
 * 游戏埋点的唯一出口。
 *
 * 所有字段名、枚举值和关卡范围在这里集中校验，页面与 Web 桥不直接依赖
 * [ReportDataManager]，后续更换统计实现时无需修改业务流程。
 */
internal class GameAnalyticsReporter(
    private val sink: AnalyticsEventSink = CoreKitAnalyticsEventSink,
) {
    fun pageShow(page: AnalyticsPage) {
        sink.report(EVENT_PAGE_SHOW, mapOf(PROP_PAGE_NAME to page.value))
    }

    fun pageLeave(page: AnalyticsPage, durationMillis: Long) {
        sink.report(
            EVENT_PAGE_LEAVE,
            mapOf(
                PROP_PAGE_NAME to page.value,
                PROP_DURATION_MS to durationMillis.coerceAtLeast(0L),
            ),
        )
    }

    fun homePrimaryClick(targetLevel: Int) {
        if (!targetLevel.isValidLevel()) return
        sink.report(EVENT_HOME_PRIMARY_CLICK, mapOf(PROP_TARGET_LEVEL to targetLevel))
    }

    fun homeLevelSelectClick() {
        sink.report(EVENT_HOME_LEVEL_SELECT_CLICK, mapOf())
    }

    fun levelSelectClick(levelNumber: Int, status: LevelNodeStatus) {
        if (!levelNumber.isValidLevel() || status == LevelNodeStatus.LOCKED) return
        sink.report(
            EVENT_LEVEL_SELECT_CLICK,
            mapOf(
                PROP_LEVEL_NUMBER to levelNumber,
                PROP_LEVEL_STATUS to status.analyticsValue,
            ),
        )
    }

    fun levelContinueClick(levelNumber: Int) {
        if (!levelNumber.isValidLevel()) return
        sink.report(EVENT_LEVEL_CONTINUE_CLICK, mapOf(PROP_LEVEL_NUMBER to levelNumber))
    }

    fun levelStart(levelNumber: Int, entry: GameLevelEntry) {
        if (!levelNumber.isValidLevel()) return
        sink.report(
            EVENT_LEVEL_START,
            mapOf(
                PROP_LEVEL_NUMBER to levelNumber,
                PROP_ENTRY to entry.bridgeValue,
            ),
        )
    }

    fun gameActionClick(levelNumber: Int, action: GameActionType) {
        if (!levelNumber.isValidLevel()) return
        sink.report(
            EVENT_GAME_ACTION_CLICK,
            mapOf(
                PROP_LEVEL_NUMBER to levelNumber,
                PROP_ACTION to action.bridgeValue,
            ),
        )
    }

    fun levelResult(
        levelNumber: Int,
        result: GameResultType,
        durationMillis: Long,
    ) {
        if (!levelNumber.isValidLevel()) return
        sink.report(
            EVENT_LEVEL_RESULT,
            mapOf(
                PROP_LEVEL_NUMBER to levelNumber,
                PROP_RESULT to result.bridgeValue,
                PROP_DURATION_MS to durationMillis.coerceAtLeast(0L),
            ),
        )
    }

    fun resultActionClick(
        levelNumber: Int,
        result: GameResultType,
        action: GameResultActionType,
    ) {
        if (!levelNumber.isValidLevel()) return
        sink.report(
            EVENT_RESULT_ACTION_CLICK,
            mapOf(
                PROP_LEVEL_NUMBER to levelNumber,
                PROP_RESULT to result.bridgeValue,
                PROP_ACTION to action.bridgeValue,
            ),
        )
    }

    private fun Int.isValidLevel(): Boolean = this in 1..LEVEL_COUNT

    private val LevelNodeStatus.analyticsValue: String
        get() = when (this) {
            LevelNodeStatus.COMPLETED -> "completed"
            LevelNodeStatus.CURRENT -> "current"
            LevelNodeStatus.AVAILABLE -> "available"
            LevelNodeStatus.LOCKED -> error("Locked levels are not reportable")
        }

    private companion object {
        const val EVENT_PAGE_SHOW = "page_show"
        const val EVENT_PAGE_LEAVE = "page_leave"
        const val EVENT_HOME_PRIMARY_CLICK = "home_primary_click"
        const val EVENT_HOME_LEVEL_SELECT_CLICK = "home_level_select_click"
        const val EVENT_LEVEL_SELECT_CLICK = "level_select_click"
        const val EVENT_LEVEL_CONTINUE_CLICK = "level_continue_click"
        const val EVENT_LEVEL_START = "level_start"
        const val EVENT_GAME_ACTION_CLICK = "game_action_click"
        const val EVENT_LEVEL_RESULT = "level_result"
        const val EVENT_RESULT_ACTION_CLICK = "result_action_click"

        const val PROP_PAGE_NAME = "page_name"
        const val PROP_DURATION_MS = "duration_ms"
        const val PROP_TARGET_LEVEL = "target_level"
        const val PROP_LEVEL_NUMBER = "level_number"
        const val PROP_LEVEL_STATUS = "level_status"
        const val PROP_ENTRY = "entry"
        const val PROP_ACTION = "action"
        const val PROP_RESULT = "result"
    }
}

/** 可注入的单调时钟，页面停留和关卡耗时不受系统时间修改影响。 */
internal fun interface MonotonicTimeSource {
    fun nowMillis(): Long
}

internal object ElapsedRealtimeSource : MonotonicTimeSource {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}

/** 一次页面展示只产生一组 show/leave，重复生命周期回调不会重复上报。 */
internal class PageAnalyticsSession(
    private val page: AnalyticsPage,
    private val reporter: GameAnalyticsReporter,
    private val timeSource: MonotonicTimeSource = ElapsedRealtimeSource,
) {
    private var shownAtMillis: Long? = null

    val isShown: Boolean
        get() = shownAtMillis != null

    fun show(): Boolean {
        if (shownAtMillis != null) return false
        shownAtMillis = timeSource.nowMillis()
        reporter.pageShow(page)
        return true
    }

    fun leave(): Boolean {
        val startedAt = shownAtMillis ?: return false
        shownAtMillis = null
        reporter.pageLeave(
            page = page,
            durationMillis = (timeSource.nowMillis() - startedAt).coerceAtLeast(0L),
        )
        return true
    }
}
