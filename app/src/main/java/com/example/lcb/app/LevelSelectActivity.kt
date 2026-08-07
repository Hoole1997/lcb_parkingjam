package com.example.lcb.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.example.lcb.parking.feature.game.ComposeLevelSelectView
import com.example.lcb.parking.feature.game.GameLevelEntry
import com.example.lcb.parking.feature.game.LevelNodeStatus

/** 独立关卡选择 Activity；页面仅投影持久化进度并负责发起关卡导航。 */
class LevelSelectActivity : ImmersiveGameActivity() {
    private lateinit var levelSelect: ComposeLevelSelectView
    private lateinit var progressStore: CaroutProgressStore
    private lateinit var pageAnalytics: PageFirstFrameAnalyticsController
    private val analyticsReporter = GameAnalyticsReporter()
    private var navigationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_level_select)
        progressStore = CaroutProgressStore(applicationContext)
        levelSelect = findViewById(R.id.game_levels)
        pageAnalytics = PageFirstFrameAnalyticsController(
            rootView = findViewById(R.id.level_select_root),
            session = PageAnalyticsSession(AnalyticsPage.LEVEL_SELECT, analyticsReporter),
        )
        levelSelect.setHostCallbacks(
            object : ComposeLevelSelectView.HostCallbacks {
                override fun onBackRequested() = finishIfIdle()

                override fun onLevelSelected(levelNumber: Int) = handleLevelSelected(levelNumber)

                override fun onContinueRequested(levelNumber: Int) = handleContinue(levelNumber)
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = finishIfIdle()
            },
        )
    }

    override fun onResume() {
        super.onResume()
        levelSelect.render(progressStore.load().toLevelSelectUiState())
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

    private fun handleLevelSelected(levelNumber: Int) {
        val progress = progressStore.load()
        val status = progress.levelStatus(levelNumber)
        if (status == LevelNodeStatus.LOCKED) return
        if (!beginLevelNavigation()) return
        analyticsReporter.levelSelectClick(levelNumber, status)
        openLevel(levelNumber)
    }

    private fun handleContinue(levelNumber: Int) {
        val progress = progressStore.load()
        if (levelNumber != progress.continueLevel || progress.levelStatus(levelNumber) == LevelNodeStatus.LOCKED) {
            return
        }
        if (!beginLevelNavigation()) return
        analyticsReporter.levelContinueClick(levelNumber)
        openLevel(levelNumber)
    }

    /**
     * Activity 是导航幂等性的最终边界。Compose 禁用用于即时反馈，这里的同步门禁负责拦截
     * 已经进入主线程消息队列的第二个手势，确保一次选关页最多启动一个游戏 Activity。
     */
    private fun beginLevelNavigation(): Boolean {
        if (navigationPending || isFinishing || isDestroyed) return false
        navigationPending = true
        levelSelect.setNavigationEnabled(false)
        return true
    }

    private fun finishIfIdle() {
        if (!navigationPending && !isFinishing && !isDestroyed) finish()
    }

    private fun openLevel(levelNumber: Int) {
        GameActivityNavigator.openGame(this, levelNumber, GameLevelEntry.LEVEL_SELECT)
        // 游戏内返回统一回首页，选关页无需在 WebView 游戏期间继续占用 Compose 资源。
        finish()
    }
}
