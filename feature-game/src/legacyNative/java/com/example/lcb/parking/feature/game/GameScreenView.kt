package com.example.lcb.parking.feature.game

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Compose 游戏页的 Android 宿主。
 *
 * Activity 继续只依赖 bind、返回键和页面活跃状态三个稳定入口；HUD、弹层与布局由 Compose
 * 负责，规则和命令仍由 ViewModel 执行。棋盘由 Compose 页面内部复用单个 Canvas View。
 */
class GameScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface HostCallbacks {
        /** 当前结果没有可加载的下一关时交还宿主处理。 */
        fun onGameSliceCompleted() = Unit

        /** 领域 Quit 落盘并投影后才触发游戏首页导航。 */
        fun onExitGameRequested() = Unit

        /** 加载错误重试前允许宿主记录或刷新外围状态。 */
        fun onRetryRequested() = Unit
    }

    private var boundViewModel: MainGameViewModel? = null
    private var hostCallbacks: HostCallbacks = object : HostCallbacks {}
    private var stateCollectionJob: Job? = null
    private var latestState by mutableStateOf(MainGameUiState())
    private var presentationEffects: Flow<GamePresentationEffect> by mutableStateOf(emptyFlow())
    private var sessionGeneration by mutableIntStateOf(0)

    /* 页面切换时同步设置；生命周期 ON_STOP 可早于 Activity.onStop，因此不能临时握手。 */
    private var gamePageActive by mutableStateOf(false)

    init {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
    }

    @Composable
    override fun Content() {
        ParkingGameTheme {
            // 重绑会话时重建桥接器，避免相同 vehicleId 的瞬态动画跨会话残留。
            key(sessionGeneration) {
                ComposeGamePlayScreen(
                    state = latestState,
                    presentationEffects = presentationEffects,
                    callbacks = composeCallbacks,
                    hostActive = gamePageActive,
                )
            }
        }
    }

    private val composeCallbacks = object : ComposeGamePlayCallbacks {
        override fun onVehicleTapped(vehicleId: String) {
            boundViewModel?.onVehicleTapped(vehicleId)
        }

        override fun onPauseRequested() {
            boundViewModel?.onPauseRequested()
        }

        override fun onResumeRequested() {
            boundViewModel?.onResumeRequested()
        }

        override fun onRestartCurrentLevelRequested() {
            boundViewModel?.onRestartCurrentLevelRequested()
        }

        override fun onNextLevelRequested() {
            boundViewModel?.onNextLevelRequested()
        }

        override fun onRetryRequested() {
            hostCallbacks.onRetryRequested()
            boundViewModel?.onRetryRequested()
        }

        override fun onHostStopped() {
            if (!gamePageActive) return
            boundViewModel?.onHostStopped()
        }

        override fun onPresentationCompleted(effectId: String, vehicleId: String?) {
            boundViewModel?.onPresentationCompleted(effectId, vehicleId)
        }

        override fun onTerminalPresented(): Boolean {
            return boundViewModel?.onTerminalPresented() == true
        }

        override fun onQuitToHomeRequested(): Boolean {
            return boundViewModel?.onQuitToHomeRequested() == true
        }

        override fun onExitGameRequested() {
            hostCallbacks.onExitGameRequested()
        }

        override fun onGameSliceCompleted() {
            hostCallbacks.onGameSliceCompleted()
        }
    }

    /** 重复绑定会取消旧状态收集；表现流只由 Compose 页面消费一次。 */
    fun bind(
        viewModel: MainGameViewModel,
        lifecycleOwner: LifecycleOwner,
        hostCallbacks: HostCallbacks = object : HostCallbacks {},
    ) {
        stateCollectionJob?.cancel()
        boundViewModel = viewModel
        this.hostCallbacks = hostCallbacks
        gamePageActive = false
        latestState = viewModel.uiState.value
        presentationEffects = viewModel.presentationEffects
        sessionGeneration++
        stateCollectionJob = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> latestState = state }
            }
        }
    }

    /** true 表示页面已消费返回操作。结果页和错误页由 Activity 导航回游戏首页。 */
    fun handleSystemBack(): Boolean {
        return when (latestState.phase) {
            GameScreenPhase.PLAYING -> {
                boundViewModel?.onPauseRequested()
                true
            }
            GameScreenPhase.PAUSED -> {
                boundViewModel?.onResumeRequested()
                true
            }
            GameScreenPhase.LOADING,
            GameScreenPhase.COMPLETING,
            GameScreenPhase.FAILING,
            -> true
            GameScreenPhase.RESULT,
            GameScreenPhase.FAILURE,
            GameScreenPhase.QUIT,
            GameScreenPhase.ERROR,
            -> false
        }
    }

    /**
     * 宿主在页面可见性变化时同步调用。该状态在生命周期 ON_STOP 前已经稳定，避免 API 29+
     * 的 pre-stop 生命周期分发早于 Activity.onStop 而漏掉自动暂停。
     */
    fun setHostActive(active: Boolean) {
        gamePageActive = active
    }

    override fun onDetachedFromWindow() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        gamePageActive = false
        super.onDetachedFromWindow()
    }
}
