package com.example.lcb.app

import com.example.lcb.parking.feature.game.LevelNodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaroutProgressStoreTest {

    @Test
    fun decode_clampsUntrustedBridgeData() {
        val snapshot = CaroutProgressCodec.decode(
            """{"unlocked":99,"done":{"-1":true,"0":true,"29":true,"30":true},"coins":-8}""",
        )

        assertEquals(LEVEL_COUNT, snapshot.unlockedLevel)
        assertEquals(setOf(1, 30), snapshot.completedLevels)
    }

    @Test
    fun uiProjection_usesSameUnlockAndCompletionStateForHomeAndMap() {
        val snapshot = CaroutProgressSnapshot(
            unlockedLevel = 4,
            completedLevels = setOf(1, 2),
        )

        val home = snapshot.toHomeUiState()
        val map = snapshot.toLevelSelectUiState()

        assertEquals(3, home.targetLevelNumber)
        assertEquals(2, home.completedLevelCount)
        assertEquals(home.starProgress, map.starProgress)
        assertEquals(6, home.starProgress.earned)
        assertEquals(90, home.starProgress.maximum)
        assertEquals(3, map.continueLevelNumber)
        assertEquals(LevelNodeStatus.CURRENT, map.nodes[2].status)
        assertEquals(LevelNodeStatus.AVAILABLE, map.nodes[3].status)
        assertEquals(LevelNodeStatus.LOCKED, map.nodes[4].status)
    }

    @Test
    fun jsonRoundTrip_preservesStableProgress() {
        val original = CaroutProgressSnapshot(7, setOf(1, 3, 6))
        val decoded = CaroutProgressCodec.decode(original.toJson())

        assertEquals(original, decoded)
        assertFalse(decoded.toJson().contains("coins"))
        assertFalse(decoded.toJson().contains("stars"))
    }
}
