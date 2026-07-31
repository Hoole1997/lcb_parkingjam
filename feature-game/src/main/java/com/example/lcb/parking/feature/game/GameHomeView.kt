package com.example.lcb.parking.feature.game

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/** Compose 首页的 View 宿主，保留稳定 Android API 以隔离 Activity 与具体 UI 技术。 */
class GameHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var primaryActionListener: ((GameHomePrimaryAction) -> Unit)? = null
    private var levelSelectListener: (() -> Unit)? = null
    private var settingsListener: (() -> Unit)? = null
    private var homeContentActive by mutableStateOf(false)
    private var uiState by mutableStateOf(
        GameHomeUiState(
            targetLevelNumber = 1,
            completedLevelCount = 0,
            totalLevelCount = 1,
            starProgress = StarProgressUiState(earned = 0, maximum = 3),
            primaryAction = GameHomePrimaryAction.OPEN_CURRENT_LEVEL,
        ),
    )

    init {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
    }

    @Composable
    override fun Content() {
        // 非首页页面不保留含大图的 Compose 子树，减少解码占用与无效重组。
        if (!homeContentActive) return
        ParkingGameTheme {
            GameHomeScreen(
                state = uiState,
                callbacks = GameHomeCallbacks(
                    onPrimaryAction = { action ->
                        if (action != GameHomePrimaryAction.NONE) {
                            primaryActionListener?.invoke(action)
                        }
                    },
                    onLevelSelect = { levelSelectListener?.invoke() },
                    onSettings = { settingsListener?.invoke() },
                ),
            )
        }
    }

    @MainThread
    fun render(state: GameHomeUiState) {
        if (uiState != state) uiState = state
    }

    /** 宿主只在应用整体进入后台且需要回收 UI 内存时关闭组合树。 */
    @MainThread
    fun setHostActive(active: Boolean) {
        if (homeContentActive != active) homeContentActive = active
    }

    @MainThread
    fun setOnPrimaryActionListener(listener: ((GameHomePrimaryAction) -> Unit)?) {
        primaryActionListener = listener
    }

    @MainThread
    fun setOnLevelSelectClickListener(listener: (() -> Unit)?) {
        levelSelectListener = listener
    }

    @MainThread
    fun setOnSettingsClickListener(listener: (() -> Unit)?) {
        settingsListener = listener
    }
}
