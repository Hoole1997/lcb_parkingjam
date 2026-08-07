package com.example.lcb.app

import com.example.lcb.parking.feature.game.GameRewardedAdPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRewardedAdPlacementTest {

    @Test
    fun `all web placements map to their independent ad switch keys`() {
        val expected = mapOf(
            "tool_refresh" to "REWARD_REFRESH",
            "tool_remove" to "REWARD_REMOVE",
            "tool_sort" to "REWARD_SORT",
            "slot_6" to "REWARD_SLOT_6",
            "slot_7" to "REWARD_SLOT_7",
        )

        expected.forEach { (bridgeValue, switchKey) ->
            assertEquals(
                switchKey,
                GameRewardedAdPlacement.fromBridgeValue(bridgeValue)?.adSlotSwitchKey,
            )
        }
        assertEquals(expected.size, GameRewardedAdPlacement.entries.size)
        assertTrue(GameRewardedAdPlacement.entries.map { it.bridgeValue }.toSet().size == expected.size)
    }

    @Test
    fun `unknown web placement is rejected`() {
        assertNull(GameRewardedAdPlacement.fromBridgeValue("slot_unlock"))
    }
}
