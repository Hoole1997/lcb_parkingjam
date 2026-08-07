package com.example.lcb.app

import com.example.lcb.parking.feature.game.GameTelemetryEvent
import java.util.ArrayDeque

/**
 * 协调游戏页面曝光与 Web 玩法事件的顺序，并维护一次关卡运行的耗时和结果幂等性。
 *
 * Web 可能在 Activity 首帧提交前完成关卡初始化，因此事件先暂存；只有 `gameplay`
 * 页面真正曝光后才按原顺序上报，保证 `page_show` 始终先于首次 `level_start`。
 */
internal class GameplayAnalyticsCoordinator(
    private val reporter: GameAnalyticsReporter,
    private val timeSource: MonotonicTimeSource = ElapsedRealtimeSource,
) {
    private val pageSession = PageAnalyticsSession(
        page = AnalyticsPage.GAMEPLAY,
        reporter = reporter,
        timeSource = timeSource,
    )
    private val pendingEvents = ArrayDeque<GameTelemetryEvent>()
    private var activeAttempt: Attempt? = null
    private var lastResult: CompletedResult? = null

    fun onPageShown() {
        pageSession.show()
        while (pageSession.isShown && pendingEvents.isNotEmpty()) {
            dispatch(pendingEvents.removeFirst())
        }
    }

    fun onPageLeave() {
        pageSession.leave()
    }

    fun onTelemetry(event: GameTelemetryEvent) {
        if (!pageSession.isShown) {
            pendingEvents.addLast(event)
            return
        }
        dispatch(event)
    }

    private fun dispatch(event: GameTelemetryEvent) {
        when (event) {
            is GameTelemetryEvent.LevelStarted -> {
                reporter.levelStart(event.levelNumber, event.entry)
                activeAttempt = Attempt(
                    levelNumber = event.levelNumber,
                    startedAtMillis = timeSource.nowMillis(),
                )
                lastResult = null
            }

            is GameTelemetryEvent.ActionClicked -> {
                reporter.gameActionClick(event.levelNumber, event.action)
            }

            is GameTelemetryEvent.LevelResult -> {
                val attempt = activeAttempt ?: return
                if (attempt.levelNumber != event.levelNumber || attempt.resultReported) return
                attempt.resultReported = true
                val duration = (timeSource.nowMillis() - attempt.startedAtMillis).coerceAtLeast(0L)
                reporter.levelResult(event.levelNumber, event.result, duration)
                lastResult = CompletedResult(event.levelNumber, event.result)
            }

            is GameTelemetryEvent.ResultActionClicked -> {
                val result = lastResult ?: return
                if (
                    result.levelNumber != event.levelNumber ||
                    result.result != event.result ||
                    result.actionReported
                ) {
                    return
                }
                result.actionReported = true
                reporter.resultActionClick(event.levelNumber, event.result, event.action)
            }
        }
    }

    private data class Attempt(
        val levelNumber: Int,
        val startedAtMillis: Long,
        var resultReported: Boolean = false,
    )

    private data class CompletedResult(
        val levelNumber: Int,
        val result: com.example.lcb.parking.feature.game.GameResultType,
        var actionReported: Boolean = false,
    )
}
