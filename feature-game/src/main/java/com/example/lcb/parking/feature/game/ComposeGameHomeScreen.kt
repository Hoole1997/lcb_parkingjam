package com.example.lcb.parking.feature.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.lcb.parking.feature.R

/**
 * V4 首页的运行时还原。
 *
 * 背景采用裁切而非拉伸；停车场是独立透明素材；所有动态文字、进度与点击区域均由
 * Compose 绘制。页面只消费稳定状态并上抛事件，不直接依赖 Activity、Launcher 或 SDK。
 */
@Composable
fun GameHomeScreen(
    state: GameHomeUiState,
    callbacks: GameHomeCallbacks,
    modifier: Modifier = Modifier,
    // 仅使用不会随临时系统栏显隐变化的挖孔安全区，避免返回首页时布局尺寸跳变。
    contentWindowInsets: WindowInsets = WindowInsets.displayCutout.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
    ),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.parking_home_courtyard),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentWindowInsets),
        ) {
            val policy = calculateHomeLayoutPolicy(
                viewportWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
            )
            HomeDesignStage(
                state = state,
                callbacks = callbacks,
                policy = policy,
            )
        }
    }
}

@Composable
private fun BoxScope.HomeDesignStage(
    state: GameHomeUiState,
    callbacks: GameHomeCallbacks,
    policy: HomeLayoutPolicy,
) {
    val scale = policy.contentScale

    HomeHeader(
        scale = scale,
        onSettings = callbacks.onSettings,
        modifier = Modifier.designBounds(policy, HomeDesignGrid.header),
    )

    Image(
        painter = painterResource(R.drawable.parking_home_hero),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.designBounds(policy, HomeDesignGrid.hero),
    )

    PrimaryLevelButton(
        state = state,
        scale = scale,
        onClick = { callbacks.onPrimaryAction(state.primaryAction) },
        modifier = Modifier.designBounds(policy, HomeDesignGrid.primary),
    )

    HomeProgressPanel(
        state = state,
        scale = scale,
        modifier = Modifier.designBounds(policy, HomeDesignGrid.progress),
    )

    LevelSelectButton(
        scale = scale,
        onClick = callbacks.onLevelSelect,
        modifier = Modifier.designBounds(policy, HomeDesignGrid.levelSelect),
    )
}

private fun Modifier.designBounds(
    policy: HomeLayoutPolicy,
    rect: HomeDesignRect,
): Modifier = offset(
    x = policy.x(rect.x).dp,
    y = policy.y(rect.y).dp,
).size(
    width = policy.size(rect.width).dp,
    height = policy.size(rect.height).dp,
)

@Preview(
    name = "Home V4 - 360 x 640",
    widthDp = 360,
    heightDp = 640,
    showBackground = true,
)
@Composable
private fun GameHomeScreenReferencePreview() {
    ParkingGameTheme {
        GameHomeScreen(
            state = previewHomeState(),
            callbacks = GameHomeCallbacks(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        )
    }
}

@Preview(
    name = "Home V4 - tall phone",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
)
@Composable
private fun GameHomeScreenTallPreview() {
    ParkingGameTheme {
        GameHomeScreen(
            state = previewHomeState(),
            callbacks = GameHomeCallbacks(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        )
    }
}

private fun previewHomeState(): GameHomeUiState = GameHomeUiState(
    targetLevelNumber = 3,
    completedLevelCount = 2,
    totalLevelCount = 30,
    starProgress = StarProgressUiState(earned = 6, maximum = 90),
    primaryAction = GameHomePrimaryAction.OPEN_CURRENT_LEVEL,
)
