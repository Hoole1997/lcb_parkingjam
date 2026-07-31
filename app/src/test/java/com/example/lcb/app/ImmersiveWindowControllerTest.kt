package com.example.lcb.app

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证各 Android 版本采用安全且能力匹配的挖孔屏策略。 */
class ImmersiveWindowControllerTest {

    @Test
    fun `pre pie device does not request unsupported cutout mode`() {
        assertNull(immersiveCutoutModeForSdk(27))
    }

    @Test
    fun `pie and q devices draw through short edges`() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            immersiveCutoutModeForSdk(28),
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            immersiveCutoutModeForSdk(29),
        )
    }

    @Test
    fun `android r and newer draw through every cutout edge`() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            immersiveCutoutModeForSdk(30),
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            immersiveCutoutModeForSdk(36),
        )
    }
}
