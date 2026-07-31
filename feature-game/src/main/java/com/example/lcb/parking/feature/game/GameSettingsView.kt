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

/** 独立设置页的稳定 Android View 边界，避免应用层直接依赖 Compose 实现细节。 */
class GameSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface HostCallbacks {
        fun onBackRequested()
        fun onLanguageRequested()
        fun onFeedbackRequested()
        fun onPrivacyPolicyRequested()
    }

    private var hostCallbacks: HostCallbacks? = null
    private var uiState by mutableStateOf(
        GameSettingsUiState(
            languageDisplayName = "—",
            versionDisplayName = "v—",
        ),
    )

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    @Composable
    override fun Content() {
        ParkingGameTheme {
            ComposeGameSettingsScreen(
                state = uiState,
                onBackRequested = { hostCallbacks?.onBackRequested() },
                onLanguageRequested = { hostCallbacks?.onLanguageRequested() },
                onFeedbackRequested = { hostCallbacks?.onFeedbackRequested() },
                onPrivacyPolicyRequested = { hostCallbacks?.onPrivacyPolicyRequested() },
            )
        }
    }

    @MainThread
    fun render(state: GameSettingsUiState) {
        if (uiState != state) uiState = state
    }

    @MainThread
    fun setHostCallbacks(callbacks: HostCallbacks?) {
        hostCallbacks = callbacks
    }
}

