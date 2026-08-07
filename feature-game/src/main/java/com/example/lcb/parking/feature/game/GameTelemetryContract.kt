package com.example.lcb.parking.feature.game

/**
 * Web 游戏向 Android 宿主发送的稳定业务事件。
 *
 * 该契约只描述玩法语义，不依赖任何统计 SDK；应用层负责校验并转换成最终埋点字段。
 */
sealed interface GameTelemetryEvent {
    val levelNumber: Int

    data class LevelStarted(
        override val levelNumber: Int,
        val entry: GameLevelEntry,
    ) : GameTelemetryEvent

    data class ActionClicked(
        override val levelNumber: Int,
        val action: GameActionType,
    ) : GameTelemetryEvent

    data class LevelResult(
        override val levelNumber: Int,
        val result: GameResultType,
    ) : GameTelemetryEvent

    data class ResultActionClicked(
        override val levelNumber: Int,
        val result: GameResultType,
        val action: GameResultActionType,
    ) : GameTelemetryEvent
}

enum class GameLevelEntry(val bridgeValue: String) {
    HOME("home"),
    LEVEL_SELECT("level_select"),
    NEXT_LEVEL("next_level"),
    RETRY("retry"),
    RESTART("restart"),
    REFRESH("refresh"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameLevelEntry? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}

enum class GameActionType(val bridgeValue: String) {
    BACK("back"),
    RESTART("restart"),
    REFRESH("refresh"),
    SOUND_ON("sound_on"),
    SOUND_OFF("sound_off"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameActionType? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}

enum class GameResultType(val bridgeValue: String) {
    WIN("win"),
    FAIL("fail"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameResultType? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}

enum class GameResultActionType(val bridgeValue: String) {
    NEXT_LEVEL("next_level"),
    RETRY("retry"),
    HOME("home"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameResultActionType? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}
