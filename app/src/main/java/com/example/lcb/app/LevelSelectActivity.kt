package com.example.lcb.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.example.lcb.parking.feature.game.ComposeLevelSelectView

/** 独立关卡选择 Activity；页面仅投影持久化进度并负责发起关卡导航。 */
class LevelSelectActivity : ImmersiveGameActivity() {
    private lateinit var levelSelect: ComposeLevelSelectView
    private lateinit var progressStore: CaroutProgressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_level_select)
        progressStore = CaroutProgressStore(applicationContext)
        levelSelect = findViewById(R.id.game_levels)
        levelSelect.setHostCallbacks(
            object : ComposeLevelSelectView.HostCallbacks {
                override fun onBackRequested() = finish()

                override fun onLevelSelected(levelNumber: Int) = openLevel(levelNumber)

                override fun onContinueRequested(levelNumber: Int) = openLevel(levelNumber)
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = finish()
            },
        )
    }

    override fun onResume() {
        super.onResume()
        levelSelect.render(progressStore.load().toLevelSelectUiState())
    }

    private fun openLevel(levelNumber: Int) {
        val progress = progressStore.load()
        if (levelNumber !in 1..progress.unlockedLevel) return
        GameActivityNavigator.openGame(this, levelNumber)
        // 游戏内返回统一回首页，选关页无需在 WebView 游戏期间继续占用 Compose 资源。
        finish()
    }
}
