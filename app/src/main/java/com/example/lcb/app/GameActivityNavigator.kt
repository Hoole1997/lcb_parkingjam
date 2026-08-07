package com.example.lcb.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.example.lcb.parking.feature.game.GameLevelEntry

/** Activity 路由集中在应用层，feature-game 不感知 Android 页面结构。 */
internal object GameActivityNavigator {
    private const val EXTRA_LEVEL_NUMBER = "game_level_number"
    private const val EXTRA_LEVEL_ENTRY = "game_level_entry"

    fun openLevelSelect(activity: Activity) {
        activity.startActivity(Intent(activity, LevelSelectActivity::class.java))
    }

    fun openSettings(activity: Activity) {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    fun openGame(activity: Activity, levelNumber: Int, entry: GameLevelEntry) {
        if (levelNumber !in 1..LEVEL_COUNT) return
        activity.startActivity(
            Intent(activity, GameActivity::class.java)
                .putExtra(EXTRA_LEVEL_NUMBER, levelNumber)
                .putExtra(EXTRA_LEVEL_ENTRY, entry.bridgeValue),
        )
    }

    fun requestedLevel(activity: Activity): Int =
        activity.intent.getIntExtra(EXTRA_LEVEL_NUMBER, 1).coerceIn(1, LEVEL_COUNT)

    fun requestedEntry(activity: Activity): GameLevelEntry =
        GameLevelEntry.fromBridgeValue(activity.intent.getStringExtra(EXTRA_LEVEL_ENTRY).orEmpty())
            ?.takeIf { it == GameLevelEntry.HOME || it == GameLevelEntry.LEVEL_SELECT }
            ?: GameLevelEntry.HOME

    /** 游戏下方始终是首页；结束当前页面即可返回，不重建首页也不调用 Launcher。 */
    fun returnToGameHome(activity: Activity) {
        closeCurrentPage(activity)
    }

    /** 关闭栈顶游戏页面并禁用系统过渡，直接露出已经绘制完成的上一页。 */
    fun closeCurrentPage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 关闭系统默认的退出动画，由栈内已经绘制完成的首页直接接管画面。
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        activity.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }
}
