package com.example.lcb.parking.feature.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android 生命周期入口。命令全部非阻塞入有界队列；规则和 IO 不在主线程执行。
 */
class MainGameViewModel(
    private val sessionController: GameSessionController,
) : ViewModel() {

    val uiState: StateFlow<MainGameUiState> = sessionController.uiState
    val presentationEffects: Flow<GamePresentationEffect> = sessionController.presentationEffects

    init {
        sessionController.start(viewModelScope)
    }

    fun onVehicleTapped(vehicleId: String) {
        if (vehicleId.isNotBlank()) sessionController.submit(MainGameCommand.TapVehicle(vehicleId))
    }

    fun onPauseRequested() {
        sessionController.submit(MainGameCommand.Pause)
    }

    fun onResumeRequested() {
        sessionController.submit(MainGameCommand.Resume)
    }

    fun onNextLevelRequested() {
        sessionController.submit(MainGameCommand.NextLevel)
    }

    /** 非阻塞请求打开指定关卡；false 表示命令队列暂时不可用。 */
    fun onLevelSelected(levelNumber: Int): Boolean {
        if (levelNumber <= 0) return false
        return sessionController.submit(MainGameCommand.OpenLevel(levelNumber))
    }

    /** 返回游戏首页前先提交领域 Quit；只有落盘后的 QUIT UI 状态才触发页面导航。 */
    fun onQuitToHomeRequested(): Boolean {
        return sessionController.submit(MainGameCommand.QuitToHome)
    }

    fun onRestartCurrentLevelRequested() {
        sessionController.submit(MainGameCommand.RestartCurrentLevel)
    }

    fun onRetryRequested() {
        sessionController.submit(MainGameCommand.Retry)
    }

    fun onHostStopped() {
        // 生命周期暂停不能与高频点击争抢普通队列，否则退后台时可能漏存暂停状态。
        sessionController.submitCritical(MainGameCommand.HostStopped)
    }

    fun onPresentationCompleted(effectId: String, vehicleId: String?) {
        if (effectId.isNotBlank()) {
            sessionController.submitCritical(MainGameCommand.PresentationCompleted(effectId, vehicleId))
        }
    }

    fun onTerminalPresented(): Boolean {
        return sessionController.submitCritical(MainGameCommand.TerminalPresented)
    }

    override fun onCleared() {
        sessionController.close()
        super.onCleared()
    }

    class Factory(
        private val sessionControllerFactory: () -> GameSessionController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainGameViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return MainGameViewModel(sessionControllerFactory()) as T
        }
    }
}
