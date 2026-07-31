package com.example.lcb.app

import android.annotation.SuppressLint
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 集中管理游戏宿主窗口的沉浸式策略，不持有 Activity，也不感知具体业务页面。
 *
 * Activity 应在 `enableEdgeToEdge()` 和 `setContentView()` 之后调用本类的 `onCreate`，
 * 并分别从 `onResume` 和 `onWindowFocusChanged` 转发生命周期：
 *
 * ```kotlin
 * immersiveWindowController.onCreate()
 * immersiveWindowController.onResume()
 * immersiveWindowController.onWindowFocusChanged(hasFocus)
 * ```
 *
 * 窗口重新获得焦点时再次应用策略，可覆盖隐私弹窗、广告 Activity 或系统界面返回后
 * 部分设备恢复系统栏的情况。内容安全区由各页面通过 WindowInsets 单独处理。
 */
internal class ImmersiveWindowController(
    private val window: Window,
    private val autoRehideTransientBars: Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val rehideSystemBars = Runnable { hideSystemBars() }
    private var insetsListenerInstalled = false

    /** Activity 完成 edge-to-edge 配置并设置内容视图后调用。 */
    @MainThread
    fun onCreate() {
        installTransientBarListener()
        applyImmersivePolicy()
    }

    /** Activity 恢复到前台时调用，用于恢复可能被外部页面改变的系统栏状态。 */
    @MainThread
    fun onResume() {
        applyImmersivePolicy()
    }

    /** Activity 的窗口重新获得焦点时调用；失去焦点时不主动修改系统 UI。 */
    @MainThread
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) applyImmersivePolicy()
    }

    @MainThread
    fun onDestroy() {
        window.decorView.removeCallbacks(rehideSystemBars)
        if (insetsListenerInstalled) {
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView, null)
            insetsListenerInstalled = false
        }
    }

    private fun applyImmersivePolicy() {
        // 背景可以绘制到状态栏、导航栏和挖孔区域；交互控件由页面消费安全 Insets。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyDisplayCutoutMode()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            // “Light bars” 在 Android API 中表示使用深色图标，适合当前浅色游戏场景。
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideSystemBars()
    }

    private fun installTransientBarListener() {
        if (!autoRehideTransientBars || insetsListenerInstalled) return
        insetsListenerInstalled = true
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.systemBars())) {
                // 系统手势不能被应用拦截；手势完成后尽快恢复沉浸式，且不改变内容布局尺寸。
                view.removeCallbacks(rehideSystemBars)
                view.postDelayed(rehideSystemBars, TRANSIENT_BAR_REHIDE_DELAY_MILLIS)
            }
            insets
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyDisplayCutoutMode() {
        // 字段本身从 API 28 才存在；同时检查真实系统版本与注入版本，保证设备安全并可测试策略。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || sdkInt < Build.VERSION_CODES.P) return
        val cutoutMode = immersiveCutoutModeForSdk(sdkInt) ?: return
        val attributes = window.attributes
        if (attributes.layoutInDisplayCutoutMode == cutoutMode) return
        attributes.layoutInDisplayCutoutMode = cutoutMode
        window.attributes = attributes
    }

    private companion object {
        const val TRANSIENT_BAR_REHIDE_DELAY_MILLIS = 300L
    }
}

/**
 * 无 Android 对象依赖的 API 级别策略，便于在普通 JVM 单元测试中覆盖版本边界。
 */
// 常量会被编译器内联，真正写入 Window 前仍由 applyDisplayCutoutMode 做 API 28 守卫。
@SuppressLint("InlinedApi")
internal fun immersiveCutoutModeForSdk(sdkInt: Int): Int? = when {
    sdkInt >= Build.VERSION_CODES.R -> {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    sdkInt >= Build.VERSION_CODES.P -> {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    else -> null
}
