package com.example.lcb.parking.feature.game

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Compose 选关页的 Android 宿主。Activity 只依赖稳定回调和不可变状态，不接触 Compose 导航。
 */
class ComposeLevelSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface HostCallbacks {
        fun onBackRequested()
        fun onLevelSelected(levelNumber: Int)
        fun onContinueRequested(levelNumber: Int)
    }

    private var hostCallbacks: HostCallbacks? = null
    private var uiState by mutableStateOf(
        LevelSelectUiState(
            starProgress = StarProgressUiState(earned = 0, maximum = 1),
            continueLevelNumber = 1,
            nodes = emptyList(),
        ),
    )

    init {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
    }

    @Composable
    override fun Content() {
        ParkingGameTheme {
            val palette = LocalParkingGamePalette.current
            ComposeLevelSelectScreen(
                state = uiState,
                onBackRequested = { hostCallbacks?.onBackRequested() },
                onLevelSelected = { level -> hostCallbacks?.onLevelSelected(level) },
                onContinueRequested = { level -> hostCallbacks?.onContinueRequested(level) },
                colors = palette.toLevelSelectColors(),
            )
        }
    }

    fun setHostCallbacks(callbacks: HostCallbacks?) {
        hostCallbacks = callbacks
    }

    @MainThread
    fun render(state: LevelSelectUiState) {
        val stableState = state.copy(nodes = state.nodes.sortedBy(LevelNodeUiState::levelNumber))
        if (uiState != stableState) uiState = stableState
    }
}

/** 地图状态色由共享主题派生，Boss/Hard 的语义强调色仍保持独立。 */
private fun ParkingGamePalette.toLevelSelectColors(): ComposeLevelSelectColors {
    return ComposeLevelSelectColors(
        chromeScrim = cream,
        surface = panel,
        outline = mintDeep.copy(alpha = 0.24f),
        cream = cream,
        route = mint,
        routeEdge = panel,
        teal = mintDeep,
        completed = mintDeep,
        current = coral,
        available = panel,
        locked = locked,
        lockGlyph = inkSecondary,
        hard = Color(0xFF8D6AC0),
        gold = sun,
        goldDark = Color(0xFFD89B22),
        textPrimary = ink,
        textSecondary = inkSecondary,
        shadow = ink.copy(alpha = 0.18f),
    )
}
