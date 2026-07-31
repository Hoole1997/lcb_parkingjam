package com.example.lcb.parking.feature.game

/**
 * 游戏层可申请的激励广告场景。
 *
 * bridgeValue 是 Web 与 Android 之间的稳定协议值；应用层可把 [adPosition] 直接作为
 * 广告平台的场景标识。玩法模块不依赖任何具体广告 SDK。
 */
enum class GameRewardedAdPlacement(
    val bridgeValue: String,
    val adPosition: String,
) {
    TOOL_REFRESH("tool_refresh", "parking_tool_refresh"),
    TOOL_REMOVE("tool_remove", "parking_tool_remove"),
    TOOL_SORT("tool_sort", "parking_tool_sort"),
    SLOT_UNLOCK("slot_unlock", "parking_slot_unlock"),
    SLOT_RESCUE("slot_rescue", "parking_slot_rescue"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameRewardedAdPlacement? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}
