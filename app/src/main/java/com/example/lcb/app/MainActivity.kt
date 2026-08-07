package com.example.lcb.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.example.lcb.parking.feature.game.GameLevelEntry
import com.example.lcb.parking.feature.game.GameHomePrimaryAction
import com.example.lcb.parking.feature.game.GameHomeView

/**
 * 游戏首页 Activity。
 *
 * 本页面只持有首页 UI 和隐私入口；关卡地图、实际游戏均由独立 Activity 承载，避免三个
 * 大页面同时驻留在一个 View 树中，也让返回栈语义与用户看到的页面保持一致。
 */
class MainActivity : ImmersiveGameActivity() {

    private lateinit var gameHome: GameHomeView
    private lateinit var progressStore: CaroutProgressStore
    private lateinit var pageAnalytics: PageFirstFrameAnalyticsController
    private val analyticsReporter = GameAnalyticsReporter()
    private var progress = CaroutProgressSnapshot(1, emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_main_home)

        progressStore = CaroutProgressStore(applicationContext)
        gameHome = findViewById(R.id.game_home)
        pageAnalytics = PageFirstFrameAnalyticsController(
            rootView = findViewById(R.id.main),
            session = PageAnalyticsSession(AnalyticsPage.GAME_HOME, analyticsReporter),
        )
        gameHome.setOnPrimaryActionListener(::handlePrimaryAction)
        gameHome.setOnLevelSelectClickListener {
            analyticsReporter.homeLevelSelectClick()
            GameActivityNavigator.openLevelSelect(this)
        }
        gameHome.setOnSettingsClickListener {
            GameActivityNavigator.openSettings(this)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = LauncherSdkGateway.returnToLauncher()
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // 游戏页可能刚刚写入通关进度；首页每次回到前台都读取唯一存档源。
        progress = progressStore.load()
        gameHome.render(progress.toHomeUiState())
        gameHome.setHostActive(true)
        pageAnalytics.onResume()
    }

    override fun onPause() {
        if (::pageAnalytics.isInitialized) pageAnalytics.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::pageAnalytics.isInitialized) pageAnalytics.onDestroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !hasWindowFocus()) {
            // Activity 间切换保留首页，防止返回时重建；整个应用退到后台后才按内存压力释放。
            gameHome.setHostActive(false)
        }
    }

    private fun handlePrimaryAction(action: GameHomePrimaryAction) {
        if (action == GameHomePrimaryAction.NONE) return
        val targetLevel = progress.continueLevel
        analyticsReporter.homePrimaryClick(targetLevel)
        GameActivityNavigator.openGame(this, targetLevel, GameLevelEntry.HOME)
    }
}
