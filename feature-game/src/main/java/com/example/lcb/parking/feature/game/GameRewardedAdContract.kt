package com.example.lcb.parking.feature.game

/**
 * 游戏层可申请的激励广告场景。
 *
 * [bridgeValue] 是 Web 与 Android 之间的稳定协议值；[adSlotSwitchKey] 是应用层广告位
 * 开关 Key。玩法模块仍不依赖具体广告 SDK。
 */
enum class GameRewardedAdPlacement(
    val bridgeValue: String,
    val adSlotSwitchKey: String,
) {
    TOOL_REFRESH("tool_refresh", "REWARD_REFRESH"),
    TOOL_REMOVE("tool_remove", "REWARD_REMOVE"),
    TOOL_SORT("tool_sort", "REWARD_SORT"),
    SLOT_6("slot_6", "REWARD_SLOT_6"),
    SLOT_7("slot_7", "REWARD_SLOT_7"),
    ;

    companion object {
        fun fromBridgeValue(value: String): GameRewardedAdPlacement? =
            entries.firstOrNull { it.bridgeValue == value }
    }
}
