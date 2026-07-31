package com.example.lcb.parking.feature.game

import androidx.compose.runtime.Immutable

/** 游戏首页主按钮的业务语义；宿主只处理导航，不参与页面绘制。 */
enum class GameHomePrimaryAction {
    OPEN_CURRENT_LEVEL,
    OPEN_NEXT_LEVEL,
    RESTART_CURRENT_LEVEL,
    RETRY_LOAD,
    NONE,
}

/** 首页与选关页共享的唯一星星统计口径。 */
@Immutable
data class StarProgressUiState(
    val earned: Int,
    val maximum: Int,
) {
    init {
        require(maximum > 0) { "Maximum stars must be positive" }
        require(earned in 0..maximum) { "Earned stars must be within the valid range" }
    }
}

/** 游戏首页只消费稳定展示状态，不持有 ViewModel、Activity 或 Launcher SDK。 */
@Immutable
data class GameHomeUiState(
    val targetLevelNumber: Int,
    val completedLevelCount: Int,
    val totalLevelCount: Int,
    val starProgress: StarProgressUiState,
    val primaryAction: GameHomePrimaryAction,
) {
    val totalStars: Int
        get() = starProgress.earned

    init {
        require(targetLevelNumber > 0) { "Target level number must be positive" }
        require(totalLevelCount > 0) { "Total level count must be positive" }
        require(completedLevelCount in 0..totalLevelCount) {
            "Completed level count must be within total level count"
        }
    }

}

/** 首页唯一的事件出口，避免 Composable 直接依赖 Android 宿主。 */
@Immutable
data class GameHomeCallbacks(
    val onPrimaryAction: (GameHomePrimaryAction) -> Unit = {},
    val onLevelSelect: () -> Unit = {},
    val onSettings: () -> Unit = {},
)
