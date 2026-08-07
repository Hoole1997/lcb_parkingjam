package com.example.lcb.parking.feature.game

/** Web 游戏请求原生短提示时允许使用的时长，避免桥接层传递 Android 常量。 */
enum class GameToastDuration(val bridgeValue: String) {
    SHORT("short"),
    LONG("long"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameToastDuration? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}
