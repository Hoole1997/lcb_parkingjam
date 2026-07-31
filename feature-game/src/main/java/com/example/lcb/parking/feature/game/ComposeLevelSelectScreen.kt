package com.example.lcb.parking.feature.game

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lcb.parking.feature.R
import kotlin.math.roundToInt

/**
 * 素材驱动的三列关卡选择页。
 *
 * 黏土边框、纹理、阴影和图标由设计素材负责，Compose 仅叠加可国际化文字与状态。
 * 页面不读取存档、不启动 Activity，也不依赖 Launcher 或广告 SDK。
 */
@Composable
fun ComposeLevelSelectScreen(
    state: LevelSelectUiState,
    onBackRequested: () -> Unit,
    onLevelSelected: (levelNumber: Int) -> Unit,
    onContinueRequested: (levelNumber: Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: ComposeLevelSelectColors = ComposeLevelSelectDefaults.colors,
) {
    CompositionLocalProvider(LocalComposeLevelSelectColors provides colors) {
        LevelGridContent(
            state = state,
            onBackRequested = onBackRequested,
            onLevelSelected = onLevelSelected,
            onContinueRequested = onContinueRequested,
            modifier = modifier,
        )
    }
}

@Composable
private fun LevelGridContent(
    state: LevelSelectUiState,
    onBackRequested: () -> Unit,
    onLevelSelected: (Int) -> Unit,
    onContinueRequested: (Int) -> Unit,
    modifier: Modifier,
) {
    val nodes = remember(state.nodes) { levelGridNodes(state.nodes) }
    val gridState = rememberLazyGridState()
    val continueNode = nodes.firstOrNull { it.levelNumber == state.continueLevelNumber }
    val continueEnabled = continueNode != null && continueNode.status != LevelNodeStatus.LOCKED

    // 深关卡只在目标关卡变化时定位，普通重组不会抢夺用户的滚动位置。
    LaunchedEffect(state.continueLevelNumber, nodes) {
        val index = nodes.indexOfFirst { it.levelNumber == state.continueLevelNumber }
        if (index >= GRID_COLUMN_COUNT * 2) {
            val firstVisibleRow = (index / GRID_COLUMN_COUNT - 1).coerceAtLeast(0)
            gridState.scrollToItem(firstVisibleRow * GRID_COLUMN_COUNT)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = remember(maxWidth) { levelGridMetrics(maxWidth) }
        Image(
            painter = painterResource(R.drawable.parking_home_courtyard),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMN_COUNT),
            state = gridState,
            contentPadding = PaddingValues(
                start = metrics.gridHorizontalPadding,
                top = metrics.gridTopPadding,
                end = metrics.gridHorizontalPadding,
                bottom = metrics.gridBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(metrics.gridGap),
            verticalArrangement = Arrangement.spacedBy(metrics.gridGap),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                ),
        ) {
            items(nodes, key = LevelNodeUiState::levelNumber) { node ->
                LevelGridCard(
                    state = node,
                    onClick = onLevelSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(CARD_ASPECT_RATIO),
                )
            }
        }

        LevelGridTopChrome(
            state = state,
            metrics = metrics,
            onBackRequested = onBackRequested,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        LevelGridBottomChrome(
            levelNumber = state.continueLevelNumber,
            enabled = continueEnabled,
            metrics = metrics,
            onContinueRequested = onContinueRequested,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 宿主可传入任意顺序，页面始终按关卡编号稳定排列。 */
internal fun levelGridNodes(nodes: List<LevelNodeUiState>): List<LevelNodeUiState> =
    nodes.sortedBy(LevelNodeUiState::levelNumber)

@Composable
private fun LevelGridTopChrome(
    state: LevelSelectUiState,
    metrics: LevelGridMetrics,
    onBackRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = metrics.chromeHorizontalPadding, vertical = 5.dp),
    ) {
        ImageButtonAsset(
            drawable = R.drawable.parking_level_back_button,
            description = stringResource(R.string.feature_game_level_select_back),
            onClick = onBackRequested,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(metrics.backButtonSize),
        )
        TitlePanel(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(metrics.titleWidth)
                .height(metrics.titleHeight),
        )
        StarCounter(
            totalStars = state.starProgress.earned,
            maxStars = state.starProgress.maximum,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(metrics.starWidth)
                .height(metrics.starHeight),
        )
    }
}

@Composable
private fun ImageButtonAsset(
    drawable: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    )
}

@Composable
private fun TitlePanel(modifier: Modifier = Modifier) {
    val palette = LocalComposeLevelSelectColors.current
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.parking_level_title_panel),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        AdaptiveSingleLineText(
            text = stringResource(R.string.feature_game_level_select_title),
            minFontSize = 12.sp,
            maxFontSize = 20.sp,
            style = TextStyle(
                color = palette.textPrimary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                // 新素材没有下方地区铭牌，主标题改为在完整面板内垂直居中。
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
        )
    }
}

@Composable
private fun StarCounter(
    totalStars: Int,
    maxStars: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalComposeLevelSelectColors.current
    val label = stringResource(
        R.string.feature_game_level_select_stars_compact_format,
        totalStars,
        maxStars,
    )
    val description = stringResource(
        R.string.feature_game_level_select_stars_format,
        totalStars,
        maxStars,
    )
    val context = LocalContext.current
    AndroidView(
        factory = {
            AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(palette.textPrimary.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.parking_level_star_counter_bg)
                isSingleLine = true
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    10,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            }
        },
        update = { view ->
            view.text = label
            view.contentDescription = description
        },
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun LevelGridCard(
    state: LevelNodeUiState,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.status != LevelNodeStatus.LOCKED
    val description = levelNodeDescription(state)
    val background = when {
        state.status == LevelNodeStatus.CURRENT -> R.drawable.parking_level_card_current
        state.status == LevelNodeStatus.LOCKED -> R.drawable.parking_level_card_locked
        else -> R.drawable.parking_level_card_open
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button) { onClick(state.levelNumber) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                selected = state.status == LevelNodeStatus.CURRENT
                if (!enabled) disabled()
            },
    ) {
        Image(
            painter = painterResource(background),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        when {
            state.status == LevelNodeStatus.CURRENT -> CurrentLevelContent(state)
            state.status == LevelNodeStatus.LOCKED -> LockedLevelContent(state)
            else -> OpenLevelContent(state)
        }
    }
}

@Composable
private fun OpenLevelContent(state: LevelNodeUiState) {
    val palette = LocalComposeLevelSelectColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 7.dp, bottom = 11.dp),
    ) {
        Text(
            text = state.levelNumber.toString(),
            color = palette.textPrimary,
            fontSize = 27.sp,
            fontWeight = FontWeight.Black,
        )
        if (state.status == LevelNodeStatus.COMPLETED && state.stars > 0) {
            StarsRow(state.stars, Modifier.padding(top = 7.dp))
        } else {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CurrentLevelContent(state: LevelNodeUiState) {
    val palette = LocalComposeLevelSelectColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = state.levelNumber.toString(),
            color = palette.cream,
            fontSize = 27.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun LockedLevelContent(state: LevelNodeUiState) {
    val palette = LocalComposeLevelSelectColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 2.dp),
    ) {
        LockIcon(modifier = Modifier.size(width = 26.dp, height = 30.dp))
        Text(
            text = state.levelNumber.toString(),
            color = palette.textPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun StarsRow(count: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = modifier,
    ) {
        repeat(count.coerceIn(0, STARS_PER_LEVEL)) {
            Image(
                painter = painterResource(R.drawable.parking_level_star),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun LockIcon(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.parking_level_lock),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
private fun LevelGridBottomChrome(
    levelNumber: Int,
    enabled: Boolean,
    metrics: LevelGridMetrics,
    onContinueRequested: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.feature_game_level_select_continue_format, levelNumber)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing
                    .union(WindowInsets.mandatorySystemGestures)
                    .union(WindowInsets.tappableElement)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(top = 18.dp, bottom = 7.dp),
    ) {
        NinePatchContinueButton(
            label = label,
            enabled = enabled,
            onClick = { onContinueRequested(levelNumber) },
            modifier = Modifier
                .width(metrics.continueWidth)
                .height(metrics.continueHeight),
        )
    }
}

/** Android View 负责真正解析 .9.png 的拉伸区，避免 Compose 位图缩放破坏圆角和播放图标。 */
@Composable
private fun NinePatchContinueButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.parking_level_continue_button)
                // NinePatch 内含播放图标，文字区必须由布局显式约束；不能沿用图片的
                // content marker，否则英文等长文案会被压缩并裁切。
                val density = resources.displayMetrics.density
                setPadding(
                    (58f * density).roundToInt(),
                    0,
                    (18f * density).roundToInt(),
                    0,
                )
                isSingleLine = true
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    12,
                    18,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            }
        },
        update = { view ->
            view.text = label
            view.contentDescription = label
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.62f
            view.setOnClickListener(if (enabled) android.view.View.OnClickListener { onClick() } else null)
        },
        modifier = modifier.semantics {
            contentDescription = label
            role = Role.Button
            if (!enabled) disabled()
        },
    )
}

@Composable
private fun levelNodeDescription(state: LevelNodeUiState): String {
    val base = when (state.status) {
        LevelNodeStatus.COMPLETED -> stringResource(
            R.string.feature_game_level_select_node_completed,
            state.levelNumber,
            state.stars,
        )
        LevelNodeStatus.CURRENT -> stringResource(
            R.string.feature_game_level_select_node_current,
            state.levelNumber,
        )
        LevelNodeStatus.AVAILABLE -> stringResource(
            R.string.feature_game_level_select_node_available,
            state.levelNumber,
        )
        LevelNodeStatus.LOCKED -> stringResource(
            R.string.feature_game_level_select_node_locked,
            state.levelNumber,
        )
    }
    return buildString {
        append(base)
        if (state.isBoss) append(stringResource(R.string.feature_game_level_select_node_boss_suffix))
    }
}

@Immutable
private data class LevelGridMetrics(
    val gridHorizontalPadding: Dp,
    val gridTopPadding: Dp,
    val gridBottomPadding: Dp,
    val gridGap: Dp,
    val chromeHorizontalPadding: Dp,
    val backButtonSize: Dp,
    val titleWidth: Dp,
    val titleHeight: Dp,
    val starWidth: Dp,
    val starHeight: Dp,
    val continueWidth: Dp,
    val continueHeight: Dp,
)

/** 360dp 是效果图的布局基准；缩放被限制在可读且可点击的安全范围。 */
private fun levelGridMetrics(screenWidth: Dp): LevelGridMetrics {
    val scale = (screenWidth.value / DESIGN_WIDTH_DP).coerceIn(0.90f, 1.12f)
    fun scaled(value: Float): Dp = (value * scale).dp
    // CTA 需要为拉丁语系预留至少 30% 的文本膨胀空间，同时限制平板上的最大宽度。
    val continueWidth = minOf(screenWidth - scaled(32f), scaled(292f))
    return LevelGridMetrics(
        gridHorizontalPadding = scaled(34f),
        gridTopPadding = scaled(143f),
        gridBottomPadding = scaled(112f),
        gridGap = scaled(9f),
        chromeHorizontalPadding = scaled(14f),
        backButtonSize = scaled(51f),
        titleWidth = scaled(164f),
        titleHeight = scaled(84f),
        starWidth = scaled(90f),
        starHeight = scaled(36f),
        continueWidth = continueWidth,
        continueHeight = scaled(61f),
    )
}

/** 页面语义颜色由共享 ParkingGameTheme 统一派生。 */
@Immutable
data class ComposeLevelSelectColors(
    val chromeScrim: Color,
    val surface: Color,
    val outline: Color,
    val cream: Color,
    val route: Color,
    val routeEdge: Color,
    val teal: Color,
    val completed: Color,
    val current: Color,
    val available: Color,
    val locked: Color,
    val lockGlyph: Color,
    val hard: Color,
    val gold: Color,
    val goldDark: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val shadow: Color,
)

object ComposeLevelSelectDefaults {
    val colors = ComposeLevelSelectColors(
        chromeScrim = Color(0xFFF5EEDF),
        surface = Color(0xFFFFFBF2),
        outline = Color(0xFF82B7A5),
        cream = Color(0xFFFFF8E9),
        route = Color(0xFF85CDB4),
        routeEdge = Color(0xFFFFF5E5),
        teal = Color(0xFF2C817A),
        completed = Color(0xFF3D948A),
        current = Color(0xFFFF7E66),
        available = Color(0xFFFFF8EA),
        locked = Color(0xFFCBCDC7),
        lockGlyph = Color(0xFF376C68),
        hard = Color(0xFF906BC2),
        gold = Color(0xFFFFC83D),
        goldDark = Color(0xFFD89B22),
        textPrimary = Color(0xFF24484A),
        textSecondary = Color(0xFF627A77),
        shadow = Color(0x33213F3F),
    )
}

private val LocalComposeLevelSelectColors = staticCompositionLocalOf {
    ComposeLevelSelectDefaults.colors
}

private const val GRID_COLUMN_COUNT = 3
private const val STARS_PER_LEVEL = 3
private const val CARD_ASPECT_RATIO = 300f / 319f
private const val DESIGN_WIDTH_DP = 360f
