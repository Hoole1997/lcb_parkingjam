package com.example.lcb.parking.feature.game

import android.animation.ValueAnimator
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.lcb.parking.feature.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Compose 游戏 HUD 尚未进入共享领域状态的数据。
 *
 * 金币只属于外围 HUD；车辆、订单和停车场进度全部来自 [MainGameUiState] 的权威投影。
 */
@Immutable
data class ComposeGamePlayHudState(
    val coinBalance: Long? = null,
    val showCoins: Boolean? = null,
) {
    init {
        require(coinBalance == null || coinBalance >= 0L) { "coinBalance cannot be negative" }
    }
}

/**
 * Compose 页面与现有 ViewModel/导航宿主之间的显式边界。
 *
 * 页面不持有 ViewModel、Activity 或 Launcher。所有命令仍由宿主转发到既有非阻塞命令队列；
 * 动画完成回调必须转发给 [MainGameViewModel.onPresentationCompleted]，否则领域表现门不会解锁。
 */
interface ComposeGamePlayCallbacks {
    fun onVehicleTapped(vehicleId: String)
    fun onPauseRequested()
    fun onResumeRequested()
    fun onRestartCurrentLevelRequested()
    fun onNextLevelRequested()
    fun onRetryRequested()
    fun onHostStopped()
    fun onPresentationCompleted(effectId: String, vehicleId: String?)
    fun onTerminalPresented(): Boolean
    fun onQuitToHomeRequested(): Boolean
    fun onExitGameRequested()
    fun onGameSliceCompleted()
}

/**
 * Compose 版游戏页面。
 *
 * 调用方应在外层使用项目统一的 `ParkingGameTheme`，并由宿主 View 的 NinePatch 绘制整屏背景；
 * 本页面保持透明，只绘制 HUD、棋盘、教程和状态弹层。棋盘继续复用 [ParkingBoardView]，从而
 * 保留已经验证的命中、位图缓存和 Canvas 动画路径，Compose 重组不会重建逐帧对象。
 */
@Composable
fun ComposeGamePlayScreen(
    state: MainGameUiState,
    presentationEffects: Flow<GamePresentationEffect>,
    callbacks: ComposeGamePlayCallbacks,
    hostActive: Boolean,
    modifier: Modifier = Modifier,
    hudState: ComposeGamePlayHudState = ComposeGamePlayHudState(),
) {
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentHostActive by rememberUpdatedState(hostActive)
    val currentAcceptsBoardInput by rememberUpdatedState(
        hostActive && state.acceptsBoardInput,
    )
    val boardBridge = remember { ComposeParkingBoardBridge() }
    val parkingMotionController = remember { ParkingMotionController() }
    val vehicleImages = rememberParkingVehicleImagesByKey()
    var boardAnimationsIdle by remember { mutableStateOf(true) }
    val parkingAnimationsIdle = parkingMotionController.isIdle
    val terminalToken = state.result?.presentationToken
        ?: state.failure?.presentationToken.orEmpty()
    var terminalReady by remember(terminalToken) { mutableStateOf(false) }

    // Scheduler 与 AndroidView 只创建一次；重组只替换轻量 callback 引用和稳定棋盘快照。
    SideEffect {
        boardBridge.onVehicleTapped = currentCallbacks::onVehicleTapped
        boardBridge.onEffectCompleted = { effect ->
            currentCallbacks.onPresentationCompleted(effect.effectId, effect.vehicleIdOrNull())
        }
        boardBridge.onBoardExitReady = { effect ->
            parkingMotionController.onBoardExitReady(effect.effectId)
        }
        boardBridge.onAnimationIdleChanged = { idle -> boardAnimationsIdle = idle }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, presentationEffects, boardBridge, hostActive) {
        if (!hostActive) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 先登记停车视觉账本，再交给棋盘；实际跨层起点由棋盘最后一帧事件触发。
            presentationEffects.collect { effect ->
                parkingMotionController.enqueue(effect)
                boardBridge.enqueue(effect)
            }
        }
    }
    LaunchedEffect(lifecycleOwner, parkingMotionController, hostActive) {
        if (!hostActive) {
            parkingMotionController.fastForward()
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            parkingMotionController.run(ValueAnimator::areAnimatorsEnabled)
        }
    }
    DisposableEffect(lifecycleOwner, boardBridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentHostActive) {
                // 与旧 View 页面一致：先快进并确认已消费动画，再提交宿主暂停命令。
                boardBridge.finishAllAndDisableInput()
                currentCallbacks.onHostStopped()
            } else if (event == Lifecycle.Event.ON_START && currentHostActive) {
                // 快照可能未变化，不能依赖 AndroidView.update 在前台恢复输入。
                boardBridge.restoreInput(currentAcceptsBoardInput)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            boardBridge.release()
        }
    }

    // 终态可能紧随最后一个效果发布；至少等待一帧并确认队列空闲，避免弹层抢在动画前显示。
    LaunchedEffect(
        state.phase,
        terminalToken,
        boardAnimationsIdle,
        parkingAnimationsIdle,
        hostActive,
    ) {
        terminalReady = false
        val terminalPhase = state.phase == GameScreenPhase.RESULT ||
            state.phase == GameScreenPhase.FAILURE
        if (hostActive && terminalPhase && boardAnimationsIdle && parkingAnimationsIdle) {
            withFrameNanos { }
            terminalReady = boardBridge.isAnimationIdle && parkingMotionController.isIdle
        }
    }
    LaunchedEffect(terminalReady, terminalToken, hostActive) {
        if (hostActive && terminalReady && terminalToken.isNotBlank()) {
            // 临时队列背压时保持可取消重试，避免已展示终态未确认而在进程恢复后重复出现。
            while (!currentCallbacks.onTerminalPresented()) {
                delay(TERMINAL_ACK_RETRY_MILLIS)
            }
        }
    }
    LaunchedEffect(state.phase, hostActive) {
        if (hostActive && state.phase == GameScreenPhase.QUIT) {
            currentCallbacks.onExitGameRequested()
        }
    }
    LaunchedEffect(hostActive) {
        if (hostActive) {
            boardBridge.restoreInput(state.acceptsBoardInput)
        } else {
            // GONE 不会销毁 Compose；切离游戏页时主动结束隐藏动画并释放输入。
            parkingMotionController.fastForward()
            boardBridge.finishAllAndDisableInput()
        }
    }

    val contentInsets = WindowInsets.safeDrawing
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.mandatorySystemGestures)
        .union(WindowInsets.tappableElement)
    val modalOverlayVisible = state.phase == GameScreenPhase.LOADING ||
        state.phase == GameScreenPhase.ERROR ||
        state.phase == GameScreenPhase.PAUSED ||
        (state.phase == GameScreenPhase.FAILURE && terminalReady) ||
        (state.phase == GameScreenPhase.RESULT && terminalReady)

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets)
                .padding(top = 8.dp, bottom = 8.dp)
                .semantics {
                    if (modalOverlayVisible) hideFromAccessibility()
                },
        ) {
            val density = LocalDensity.current
            val layout = remember(
                maxWidth,
                maxHeight,
                density.fontScale,
                state.board.rows,
                state.board.columns,
                state.parkingLot.capacity,
            ) {
                GamePlayLayoutPolicy.calculate(
                    availableWidthDp = maxWidth.value,
                    availableHeightDp = maxHeight.value,
                    boardRows = state.board.rows,
                    boardColumns = state.board.columns,
                    parkingCapacity = state.parkingLot.capacity,
                    fontScale = density.fontScale,
                )
            }
            val compact = layout.mode == GamePlayLayoutMode.COMPACT

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(layout.contentWidthDp.dp)
                    .height(layout.occupiedHeightDp.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                ) {
                    ComposeGameHud(
                        state = state,
                        hudState = hudState,
                        compact = compact,
                        onPause = currentCallbacks::onPauseRequested,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(layout.hudHeightDp.dp),
                    )

                    if (state.parkingLot.capacity > 0) {
                        Spacer(Modifier.height(layout.verticalGapDp.dp))
                        ActiveOrderProgress(
                            state = state.parkingLot,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(layout.orderIndicatorHeightDp.dp),
                            compact = compact,
                        )
                    }

                    Spacer(Modifier.height(layout.verticalGapDp.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(layout.boardViewportWidthDp.dp)
                            .height(layout.boardViewportHeightDp.dp),
                    ) {
                        ComposeParkingBoard(
                            bridge = boardBridge,
                            state = state,
                            hostActive = hostActive,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (
                            !state.tutorialMessage.isNullOrBlank() &&
                            state.phase == GameScreenPhase.PLAYING
                        ) {
                            TutorialBubble(
                                message = state.tutorialMessage.orEmpty(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }

                    if (state.parkingLot.capacity > 0) {
                        Spacer(Modifier.height(layout.verticalGapDp.dp))
                        TemporaryParkingLot(
                            state = state.parkingLot,
                            layout = layout,
                            motionController = parkingMotionController,
                            vehicleImages = vehicleImages,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(layout.parkingContentWidthDp.dp)
                                .height(layout.parkingSlotsHeightDp.dp),
                        )
                    }
                }

                ComposeParkingMotionLayer(
                    controller = parkingMotionController,
                    layout = layout,
                    vehicleImages = vehicleImages,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when {
            state.phase == GameScreenPhase.LOADING -> LoadingOverlay()
            state.phase == GameScreenPhase.ERROR -> ErrorOverlay(
                message = state.errorMessage,
                onRetry = currentCallbacks::onRetryRequested,
            )
            state.phase == GameScreenPhase.FAILURE && terminalReady -> FailureOverlay(
                onRestart = currentCallbacks::onRestartCurrentLevelRequested,
                // Attempt 已经是 FAIL，不能再提交只允许 ACTIVE 的 Quit；直接交还游戏内宿主导航。
                onExit = currentCallbacks::onExitGameRequested,
            )
            state.phase == GameScreenPhase.PAUSED -> PauseOverlay(
                callbacks = currentCallbacks,
            )
            state.phase == GameScreenPhase.RESULT && terminalReady -> ResultOverlay(
                result = state.result,
                onNext = {
                    if (state.result?.hasNextLevel == true) {
                        currentCallbacks.onNextLevelRequested()
                    } else {
                        currentCallbacks.onGameSliceCompleted()
                    }
                },
                onExit = currentCallbacks::onExitGameRequested,
            )
        }
    }
}

@Composable
private fun ComposeGameHud(
    state: MainGameUiState,
    hudState: ComposeGamePlayHudState,
    compact: Boolean,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coinBalance = hudState.coinBalance ?: state.progress.coins
    val showCoins = hudState.showCoins ?: (state.levelNumber >= COIN_REVEAL_LEVEL)
    val controlsEnabled = state.phase == GameScreenPhase.PLAYING
    val levelLabel = stringResource(R.string.feature_game_level_format, state.levelNumber)
    val coinLabel = stringResource(R.string.feature_game_coin_hud_format, coinBalance)

    Box(
        modifier = modifier,
    ) {
        if (showCoins) {
            CoinPill(
                balance = coinBalance,
                description = coinLabel,
                compact = compact,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        HudPill(
            text = levelLabel,
            compact = compact,
            modifier = Modifier
                .align(Alignment.Center)
                .width(if (compact) 106.dp else 122.dp)
                .fillMaxHeight(),
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            PauseButton(enabled = controlsEnabled, onClick = onPause)
        }
    }
}

@Composable
private fun CoinPill(
    balance: Long,
    description: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(if (compact) 40.dp else 42.dp)
            .widthIn(min = if (compact) 76.dp else 84.dp, max = 108.dp)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = HUD_ALPHA),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(if (compact) 20.dp else 22.dp)
                    .semantics { hideFromAccessibility() },
            ) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.14f),
                    radius = size.minDimension * 0.45f,
                    center = center + androidx.compose.ui.geometry.Offset(0f, 1.5.dp.toPx()),
                )
                drawCircle(
                    color = Color(0xFFFFC94F),
                    radius = size.minDimension * 0.45f,
                )
                drawCircle(
                    color = Color(0xFFFFE49A),
                    radius = size.minDimension * 0.34f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = maxOf(1f, size.minDimension * 0.08f),
                    ),
                )
            }
            Text(
                text = balance.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PauseButton(enabled: Boolean, onClick: () -> Unit) {
    val pauseDescription = stringResource(R.string.feature_game_pause)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = HUD_ALPHA),
        tonalElevation = 4.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = pauseDescription
                },
        ) {
            PauseGlyph(
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (enabled) 1f else DISABLED_ALPHA),
            )
        }
    }
}

@Composable
private fun HudPill(text: String, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = HUD_ALPHA),
        tonalElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/*
 * Keep the glyph vector-based so it stays crisp at every density without another bitmap.
 */
@Composable
private fun PauseGlyph(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val barWidth = size.width * 0.22f
        val gap = size.width * 0.18f
        val top = size.height * 0.14f
        val bottom = size.height * 0.86f
        val centerX = size.width / 2f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(centerX - gap / 2f - barWidth, top),
            size = androidx.compose.ui.geometry.Size(barWidth, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(centerX + gap / 2f, top),
            size = androidx.compose.ui.geometry.Size(barWidth, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
        )
    }
}

@Composable
private fun ComposeParkingBoard(
    bridge: ComposeParkingBoardBridge,
    state: MainGameUiState,
    hostActive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        // Preview 不创建真实 View/位图缓存，避免 IDE 预览线程触发 Android 动画设施。
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.feature_game_level_format, state.levelNumber),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    AndroidView(
        factory = { context ->
            ParkingBoardView(context).also { boardView ->
                boardView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                boardView.setBoardVerticalBias(0.5f)
                bridge.attach(boardView)
            }
        },
        modifier = modifier,
        update = { boardView ->
            bridge.render(
                boardView = boardView,
                board = state.board,
                acceptsInput = hostActive && state.acceptsBoardInput,
            )
        },
        onRelease = bridge::detach,
    )
}

@Composable
private fun TutorialBubble(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.96f),
        shadowElevation = 5.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LoadingOverlay() {
    val title = stringResource(R.string.feature_game_loading)
    ModalScrim(paneTitle = title) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String?, onRetry: () -> Unit) {
    ModalCard(
        title = stringResource(R.string.feature_game_error),
        body = message,
        primaryText = stringResource(R.string.feature_game_retry),
        onPrimary = onRetry,
    )
}

@Composable
private fun FailureOverlay(
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    ModalCard(
        title = stringResource(R.string.feature_game_failure_title),
        body = stringResource(R.string.feature_game_failure_message),
        primaryText = stringResource(R.string.feature_game_home_restart),
        onPrimary = onRestart,
        secondaryText = stringResource(R.string.feature_game_exit_game),
        onSecondary = onExit,
    )
}

@Composable
private fun PauseOverlay(callbacks: ComposeGamePlayCallbacks) {
    var quitSubmitted by remember { mutableStateOf(false) }
    ModalCard(
        title = stringResource(R.string.feature_game_paused_title),
        primaryText = stringResource(R.string.feature_game_resume),
        onPrimary = callbacks::onResumeRequested,
        secondaryText = stringResource(R.string.feature_game_exit_game),
        onSecondary = {
            if (!quitSubmitted && callbacks.onQuitToHomeRequested()) quitSubmitted = true
        },
        secondaryEnabled = !quitSubmitted,
    )
}

@Composable
private fun ResultOverlay(
    result: GameResultUiState?,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    if (result == null) return
    val starsDescription = stringResource(R.string.feature_game_stars_format, result.stars)
    val coinText = when {
        !result.showCoins -> null
        result.earnedCoins > 0 -> stringResource(
            R.string.feature_game_coin_balance_with_reward_format,
            result.coinBalance,
            result.earnedCoins,
        )
        else -> stringResource(R.string.feature_game_coin_balance_format, result.coinBalance)
    }
    val title = stringResource(R.string.feature_game_result_title)
    ModalScrim(paneTitle = title) {
        Card(
            modifier = Modifier.widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "★".repeat(result.stars.coerceIn(0, MAX_STARS)),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 34.sp,
                    modifier = Modifier.semantics {
                        contentDescription = starsDescription
                    },
                )
                if (coinText != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = coinText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                            .heightIn(min = 52.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (result.hasNextLevel) {
                                R.string.feature_game_next_level
                            } else {
                                R.string.feature_game_finish_slice
                            },
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (result.hasNextLevel) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp),
                    ) {
                        Text(stringResource(R.string.feature_game_exit_game))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalCard(
    title: String,
    primaryText: String,
    onPrimary: () -> Unit,
    body: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
) {
    ModalScrim(paneTitle = title) {
        Card(
            modifier = Modifier.widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(primaryText, fontWeight = FontWeight.Bold)
                }
                if (secondaryText != null && onSecondary != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onSecondary,
                        enabled = secondaryEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp),
                    ) {
                        Text(secondaryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalScrim(
    paneTitle: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            // 消费弹层外触摸但不创建一个无标签的 TalkBack “按钮”。
            .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .semantics {
                this.paneTitle = paneTitle
                liveRegion = LiveRegionMode.Polite
                isTraversalGroup = true
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Compose 与原 Canvas View 之间的稳定桥。它持有一个 View 和一个有界 Scheduler；
 * Compose 重组只调用 [render]，不会重建 Animator、Bitmap、Paint 或棋盘 View。
 */
private class ComposeParkingBoardBridge {
    private var boardView: ParkingBoardView? = null
    private var scheduler: GameAnimationScheduler? = null
    private val pendingBeforeAttach = ArrayDeque<GamePresentationEffect>(MAX_PENDING_BEFORE_ATTACH)
    private var lastBoard: BoardRenderModel? = null
    private var lastAcceptsInput: Boolean? = null

    var onVehicleTapped: ((String) -> Unit)? = null
    var onEffectCompleted: ((GamePresentationEffect) -> Unit)? = null
    var onBoardExitReady: ((GamePresentationEffect.MoveVehicle) -> Unit)? = null
    var onAnimationIdleChanged: ((Boolean) -> Unit)? = null

    val isAnimationIdle: Boolean
        get() = scheduler?.isIdle != false && pendingBeforeAttach.isEmpty()

    fun attach(view: ParkingBoardView) {
        if (boardView === view) return
        releaseAttachedView()
        boardView = view
        view.setOnVehicleTapListener { vehicleId -> onVehicleTapped?.invoke(vehicleId) }
        scheduler = GameAnimationScheduler(view).also { animationScheduler ->
            animationScheduler.onEffectCompleted = { effect -> onEffectCompleted?.invoke(effect) }
            animationScheduler.onBoardExitReady = { effect -> onBoardExitReady?.invoke(effect) }
            animationScheduler.onQueueIdle = { onAnimationIdleChanged?.invoke(true) }
        }
        while (pendingBeforeAttach.isNotEmpty()) {
            scheduler?.enqueue(pendingBeforeAttach.removeFirst())
        }
        onAnimationIdleChanged?.invoke(isAnimationIdle)
    }

    fun render(
        boardView: ParkingBoardView,
        board: BoardRenderModel,
        acceptsInput: Boolean,
    ) {
        if (this.boardView !== boardView) attach(boardView)
        if (lastBoard == board && lastAcceptsInput == acceptsInput) return
        lastBoard = board
        lastAcceptsInput = acceptsInput
        boardView.render(board, acceptsInput)
    }

    fun enqueue(effect: GamePresentationEffect) {
        onAnimationIdleChanged?.invoke(false)
        val activeScheduler = scheduler
        if (activeScheduler != null) {
            activeScheduler.enqueue(effect)
            return
        }
        if (pendingBeforeAttach.size < MAX_PENDING_BEFORE_ATTACH) {
            pendingBeforeAttach.addLast(effect)
        } else {
            // View 尚未附着且队列已满时直接确认，业务状态已落盘，不能无限持有效果或阻塞结算。
            onEffectCompleted?.invoke(effect)
        }
    }

    fun finishAllAndDisableInput() {
        val view = boardView
        val board = lastBoard
        if (view != null && board != null) {
            view.render(board, acceptsInput = false)
            lastAcceptsInput = false
        }
        scheduler?.finishAll()
        while (pendingBeforeAttach.isNotEmpty()) {
            onEffectCompleted?.invoke(pendingBeforeAttach.removeFirst())
        }
        onAnimationIdleChanged?.invoke(true)
    }

    /** 生命周期恢复时强制同步输入，不依赖 AndroidView 是否因相同状态再次执行 update。 */
    fun restoreInput(acceptsInput: Boolean) {
        val view = boardView ?: return
        val board = lastBoard ?: return
        view.render(board, acceptsInput)
        lastAcceptsInput = acceptsInput
    }

    fun detach(view: ParkingBoardView) {
        if (boardView === view) release()
    }

    fun release() {
        releaseAttachedView()
        // 完整释放意味着页面已经离开；尚未来得及交给 View 的效果也必须确认，不能永久阻塞领域。
        while (pendingBeforeAttach.isNotEmpty()) {
            onEffectCompleted?.invoke(pendingBeforeAttach.removeFirst())
        }
        onAnimationIdleChanged?.invoke(true)
    }

    private fun releaseAttachedView() {
        scheduler?.finishAll()
        scheduler = null
        boardView?.run {
            setOnVehicleTapListener(null)
            render(BoardRenderModel.EMPTY, acceptsInput = false)
            resetPresentationState()
        }
        boardView = null
        lastBoard = null
        lastAcceptsInput = null
    }

    private companion object {
        const val MAX_PENDING_BEFORE_ATTACH = 32
    }
}

private fun GamePresentationEffect.vehicleIdOrNull(): String? = when (this) {
    is GamePresentationEffect.MoveVehicle -> vehicleId
    is GamePresentationEffect.ReboundVehicle -> vehicleId
    is GamePresentationEffect.HighlightVehicle -> vehicleId
}

private const val COIN_REVEAL_LEVEL = 5
private const val MAX_STARS = 3
private const val HUD_ALPHA = 0.94f
private const val SCRIM_ALPHA = 0.66f
private const val DISABLED_ALPHA = 0.45f
private const val TERMINAL_ACK_RETRY_MILLIS = 50L
