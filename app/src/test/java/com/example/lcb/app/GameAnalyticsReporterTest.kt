package com.example.lcb.app

import com.example.lcb.parking.feature.game.GameActionType
import com.example.lcb.parking.feature.game.GameLevelEntry
import com.example.lcb.parking.feature.game.GameResultActionType
import com.example.lcb.parking.feature.game.GameResultType
import com.example.lcb.parking.feature.game.GameTelemetryEvent
import com.example.lcb.parking.feature.game.LevelNodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAnalyticsReporterTest {

    @Test
    fun noPropertyEvent_usesEmptyMap() {
        val sink = RecordingSink()
        val reporter = GameAnalyticsReporter(sink)

        reporter.homeLevelSelectClick()

        assertEquals(listOf(ReportedEvent("home_level_select_click", emptyMap())), sink.events)
    }

    @Test
    fun clickEvents_useExactSchemaValues() {
        val sink = RecordingSink()
        val reporter = GameAnalyticsReporter(sink)

        reporter.homePrimaryClick(6)
        reporter.levelSelectClick(4, LevelNodeStatus.COMPLETED)
        reporter.levelContinueClick(6)
        reporter.gameActionClick(6, GameActionType.SOUND_OFF)

        assertEquals(
            listOf(
                ReportedEvent("home_primary_click", mapOf("target_level" to 6)),
                ReportedEvent(
                    "level_select_click",
                    mapOf("level_number" to 4, "level_status" to "completed"),
                ),
                ReportedEvent("level_continue_click", mapOf("level_number" to 6)),
                ReportedEvent(
                    "game_action_click",
                    mapOf("level_number" to 6, "action" to "sound_off"),
                ),
            ),
            sink.events,
        )
    }

    @Test
    fun invalidOrLockedLevelClick_isIgnored() {
        val sink = RecordingSink()
        val reporter = GameAnalyticsReporter(sink)

        reporter.homePrimaryClick(31)
        reporter.levelSelectClick(7, LevelNodeStatus.LOCKED)

        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun bridgeEnums_acceptOnlyDocumentedValues() {
        assertEquals(GameLevelEntry.NEXT_LEVEL, GameLevelEntry.fromBridgeValue("next_level"))
        assertEquals(GameActionType.SOUND_ON, GameActionType.fromBridgeValue("sound_on"))
        assertEquals(GameResultType.FAIL, GameResultType.fromBridgeValue("fail"))
        assertEquals(
            GameResultActionType.RETRY,
            GameResultActionType.fromBridgeValue("retry"),
        )
        assertEquals(null, GameActionType.fromBridgeValue("unknown"))
    }

    @Test
    fun pageSession_reportsOnePairAndNonNegativeDuration() {
        val sink = RecordingSink()
        val clock = MutableClock(1_000L)
        val session = PageAnalyticsSession(
            page = AnalyticsPage.GAME_HOME,
            reporter = GameAnalyticsReporter(sink),
            timeSource = clock,
        )

        session.show()
        session.show()
        clock.value = 850L
        session.leave()
        session.leave()

        assertEquals(
            listOf(
                ReportedEvent("page_show", mapOf("page_name" to "game_home")),
                ReportedEvent(
                    "page_leave",
                    mapOf("page_name" to "game_home", "duration_ms" to 0L),
                ),
            ),
            sink.events,
        )
    }

    @Test
    fun gameplayCoordinator_ordersFirstFrameAndDeduplicatesResult() {
        val sink = RecordingSink()
        val clock = MutableClock(100L)
        val coordinator = GameplayAnalyticsCoordinator(GameAnalyticsReporter(sink), clock)

        coordinator.onTelemetry(GameTelemetryEvent.LevelStarted(3, GameLevelEntry.HOME))
        assertTrue(sink.events.isEmpty())

        coordinator.onPageShown()
        clock.value = 640L
        coordinator.onTelemetry(GameTelemetryEvent.LevelResult(3, GameResultType.WIN))
        coordinator.onTelemetry(GameTelemetryEvent.LevelResult(3, GameResultType.WIN))
        coordinator.onTelemetry(
            GameTelemetryEvent.ResultActionClicked(
                levelNumber = 3,
                result = GameResultType.WIN,
                action = GameResultActionType.NEXT_LEVEL,
            ),
        )
        coordinator.onTelemetry(
            GameTelemetryEvent.ResultActionClicked(
                levelNumber = 3,
                result = GameResultType.WIN,
                action = GameResultActionType.NEXT_LEVEL,
            ),
        )

        assertEquals("page_show", sink.events[0].name)
        assertEquals(
            ReportedEvent(
                "level_start",
                mapOf("level_number" to 3, "entry" to "home"),
            ),
            sink.events[1],
        )
        assertEquals(
            ReportedEvent(
                "level_result",
                mapOf(
                    "level_number" to 3,
                    "result" to "win",
                    "duration_ms" to 540L,
                ),
            ),
            sink.events[2],
        )
        assertEquals(1, sink.events.count { it.name == "level_result" })
        assertEquals(
            ReportedEvent(
                "result_action_click",
                mapOf(
                    "level_number" to 3,
                    "result" to "win",
                    "action" to "next_level",
                ),
            ),
            sink.events[3],
        )
        assertEquals(1, sink.events.count { it.name == "result_action_click" })
    }

    @Test
    fun rescueRetry_startsNewAttemptBeforeLaterWin() {
        val sink = RecordingSink()
        val clock = MutableClock(0L)
        val coordinator = GameplayAnalyticsCoordinator(GameAnalyticsReporter(sink), clock)

        coordinator.onPageShown()
        coordinator.onTelemetry(GameTelemetryEvent.LevelStarted(8, GameLevelEntry.LEVEL_SELECT))
        clock.value = 200L
        coordinator.onTelemetry(GameTelemetryEvent.LevelResult(8, GameResultType.FAIL))
        coordinator.onTelemetry(GameTelemetryEvent.LevelStarted(8, GameLevelEntry.RETRY))
        clock.value = 500L
        coordinator.onTelemetry(GameTelemetryEvent.LevelResult(8, GameResultType.WIN))

        assertEquals(2, sink.events.count { it.name == "level_start" })
        assertEquals(2, sink.events.count { it.name == "level_result" })
        assertEquals(
            listOf("fail", "win"),
            sink.events.filter { it.name == "level_result" }.map { it.data["result"] },
        )
    }

    private data class ReportedEvent(
        val name: String,
        val data: Map<String, Any>,
    )

    private class RecordingSink : AnalyticsEventSink {
        val events = mutableListOf<ReportedEvent>()

        override fun report(eventName: String, data: Map<String, Any>) {
            events += ReportedEvent(eventName, data)
        }
    }

    private class MutableClock(var value: Long) : MonotonicTimeSource {
        override fun nowMillis(): Long = value
    }
}
