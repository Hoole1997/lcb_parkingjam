package com.example.lcb.parking.feature.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lcb.parking.feature.R

@Composable
internal fun HomeHeader(
    scale: Float,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsVisualWidth = scaledDp(127f, scale)
    val settingsVisualHeight = scaledDp(128f, scale)
    val settingsTouchWidth = maxOf(48.dp, settingsVisualWidth)
    val settingsTouchHeight = maxOf(48.dp, settingsVisualHeight)
    Box(modifier = modifier) {
        HomeTitlePlaque(
            scale = scale,
            modifier = Modifier
                .offset(x = scaledDp(257f, scale))
                .size(width = scaledDp(429f, scale), height = scaledDp(224f, scale)),
        )
        SettingsButton(
            scale = scale,
            onClick = onSettings,
            modifier = Modifier
                .offset(
                    x = scaledDp(777f, scale) - (settingsTouchWidth - settingsVisualWidth) / 2f,
                    y = scaledDp(9f, scale) - (settingsTouchHeight - settingsVisualHeight) / 2f,
                )
                .size(width = settingsTouchWidth, height = settingsTouchHeight),
        )
    }
}

@Composable
private fun HomeTitlePlaque(
    scale: Float,
    modifier: Modifier = Modifier,
) {
    TactilePanel(
        modifier = modifier.semantics { heading() },
        shape = RoundedCornerShape(scaledDp(57f, scale)),
        depth = scaledDp(18f, scale),
        edgeColor = HomeColors.warmEdge,
        faceColors = listOf(HomeColors.creamHighlight, HomeColors.creamFace),
        borderColor = HomeColors.creamStroke,
        borderWidth = scaledDp(5f, scale),
    ) {
        FittedText(
            text = stringResource(R.string.feature_game_home_title),
            // 葡语等语言的游戏名明显长于中英文，允许在同一铭牌内继续缩放而不是截断。
            minFontSize = scaledSp(30f, scale),
            maxFontSize = scaledSp(61f, scale),
            modifier = Modifier
                .offset(x = scaledDp(28f, scale), y = scaledDp(22f, scale))
                .size(width = scaledDp(373f, scale), height = scaledDp(91f, scale)),
            style = HomeTextStyles.black.copy(
                color = HomeColors.ink,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = Color.White.copy(alpha = 0.72f),
                    offset = Offset(0f, scaledDp(3f, scale).value),
                ),
            ),
        )

        TactilePanel(
            modifier = Modifier
                .offset(x = scaledDp(92f, scale), y = scaledDp(129f, scale))
                .size(width = scaledDp(245f, scale), height = scaledDp(61f, scale)),
            shape = RoundedCornerShape(scaledDp(27f, scale)),
            depth = scaledDp(7f, scale),
            edgeColor = HomeColors.mintEdge,
            faceColors = listOf(HomeColors.mintLight, HomeColors.mint),
            borderColor = HomeColors.mintStroke,
            borderWidth = scaledDp(3f, scale),
        ) {
            FittedText(
                text = stringResource(R.string.feature_game_home_chapter_short),
                minFontSize = scaledSp(17f, scale),
                maxFontSize = scaledSp(34f, scale),
                modifier = Modifier
                    .matchParentSize()
                    .padding(
                        start = scaledDp(15f, scale),
                        end = scaledDp(15f, scale),
                        bottom = scaledDp(7f, scale),
                    ),
                style = HomeTextStyles.bold.copy(
                    color = HomeColors.creamHighlight,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = HomeColors.mintEdge,
                        offset = Offset(0f, scaledDp(3f, scale).value),
                    ),
                ),
            )
        }

        FlowerCluster(
            coral = false,
            modifier = Modifier
                .offset(x = scaledDp(33f, scale), y = scaledDp(131f, scale))
                .size(width = scaledDp(70f, scale), height = scaledDp(64f, scale)),
        )
        FlowerCluster(
            coral = true,
            modifier = Modifier
                .offset(x = scaledDp(328f, scale), y = scaledDp(130f, scale))
                .size(width = scaledDp(70f, scale), height = scaledDp(64f, scale)),
        )
    }
}

@Composable
private fun FlowerCluster(coral: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val leaf = HomeColors.leaf
        rotate(-35f, pivot = Offset(size.width * 0.34f, size.height * 0.60f)) {
            drawOval(
                color = leaf,
                topLeft = Offset(size.width * 0.04f, size.height * 0.47f),
                size = Size(size.width * 0.44f, size.height * 0.28f),
            )
        }
        rotate(35f, pivot = Offset(size.width * 0.68f, size.height * 0.62f)) {
            drawOval(
                color = leaf,
                topLeft = Offset(size.width * 0.53f, size.height * 0.47f),
                size = Size(size.width * 0.43f, size.height * 0.28f),
            )
        }
        val flowerCenter = Offset(size.width * 0.5f, size.height * 0.43f)
        val petalColor = if (coral) HomeColors.coralLight else Color(0xFFFFFBF2)
        repeat(5) { index ->
            val angle = Math.toRadians((index * 72.0) - 90.0)
            drawCircle(
                color = petalColor,
                radius = size.minDimension * 0.16f,
                center = Offset(
                    x = flowerCenter.x + kotlin.math.cos(angle).toFloat() * size.width * 0.18f,
                    y = flowerCenter.y + kotlin.math.sin(angle).toFloat() * size.height * 0.18f,
                ),
            )
        }
        drawCircle(
            color = HomeColors.starGold,
            radius = size.minDimension * 0.11f,
            center = flowerCenter,
        )
    }
}

@Composable
private fun SettingsButton(
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.feature_game_home_settings)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(width = scaledDp(127f, scale), height = scaledDp(128f, scale)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .offset(y = scaledDp(10f, scale))
                    .matchParentSize()
                    .background(HomeColors.warmEdge, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(scaledDp(116f, scale))
                    .shadow(scaledDp(8f, scale), CircleShape, clip = false)
                    .background(HomeColors.creamHighlight, CircleShape)
                    .border(scaledDp(4f, scale), HomeColors.creamStroke, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(scaledDp(78f, scale))
                        .background(HomeColors.mint, CircleShape)
                        .border(scaledDp(3f, scale), HomeColors.mintEdge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_feature_game_settings),
                        contentDescription = null,
                        tint = HomeColors.creamHighlight,
                        modifier = Modifier.size(scaledDp(47f, scale)),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PrimaryLevelButton(
    state: GameHomeUiState,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.primaryAction != GameHomePrimaryAction.NONE
    val interactionSource = remember { MutableInteractionSource() }
    TactilePanel(
        modifier = modifier
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(scaledDp(87f, scale)),
        depth = scaledDp(21f, scale),
        edgeColor = HomeColors.warmEdge,
        faceColors = if (enabled) {
            listOf(HomeColors.coralLight, HomeColors.coral)
        } else {
            listOf(HomeColors.disabledLight, HomeColors.disabled)
        },
        borderColor = HomeColors.creamStroke,
        borderWidth = scaledDp(9f, scale),
    ) {
        PlayGlyph(
            enabled = enabled,
            modifier = Modifier
                .offset(x = scaledDp(61f, scale), y = scaledDp(35f, scale))
                .size(width = scaledDp(79f, scale), height = scaledDp(83f, scale)),
        )
        FittedText(
            text = primaryButtonLabel(state),
            minFontSize = scaledSp(29f, scale),
            maxFontSize = scaledSp(46f, scale),
            modifier = Modifier
                .offset(x = scaledDp(145f, scale), y = scaledDp(29f, scale))
                .size(width = scaledDp(351f, scale), height = scaledDp(94f, scale)),
            style = HomeTextStyles.black.copy(
                color = HomeColors.creamHighlight,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = HomeColors.coralEdge,
                    offset = Offset(0f, scaledDp(5f, scale).value),
                    blurRadius = scaledDp(1.5f, scale).value,
                ),
            ),
        )
    }
}

@Composable
private fun PlayGlyph(enabled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val shadowPath = playPath(size, yOffset = size.height * 0.08f)
        drawPath(shadowPath, color = if (enabled) HomeColors.playShadow else HomeColors.disabled)
        drawPath(playPath(size), color = HomeColors.creamHighlight)
        drawPath(
            path = playPath(size),
            color = HomeColors.creamStroke,
            style = Stroke(width = size.minDimension * 0.04f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

private fun playPath(size: Size, yOffset: Float = 0f): Path = Path().apply {
    moveTo(size.width * 0.18f, size.height * 0.08f + yOffset)
    lineTo(size.width * 0.86f, size.height * 0.50f + yOffset)
    lineTo(size.width * 0.18f, size.height * 0.92f + yOffset)
    close()
}

@Composable
internal fun HomeProgressPanel(
    state: GameHomeUiState,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val progress = state.starProgress.earned.toFloat() / state.starProgress.maximum.toFloat()
    val description = stringResource(
        R.string.feature_game_home_star_progress_accessibility,
        state.starProgress.earned,
        state.starProgress.maximum,
    )
    TactilePanel(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        },
        shape = RoundedCornerShape(scaledDp(48f, scale)),
        depth = scaledDp(14f, scale),
        edgeColor = HomeColors.warmEdge,
        faceColors = listOf(HomeColors.creamHighlight, HomeColors.creamFace),
        borderColor = HomeColors.mintStroke,
        borderWidth = scaledDp(4f, scale),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_feature_game_star),
            contentDescription = null,
            modifier = Modifier
                .offset(x = scaledDp(39f, scale), y = scaledDp(23f, scale))
                .size(scaledDp(55f, scale)),
        )
        FittedText(
            text = stringResource(
                R.string.feature_game_home_star_progress_short_format,
                state.starProgress.earned,
                state.starProgress.maximum,
            ),
            minFontSize = scaledSp(23f, scale),
            maxFontSize = scaledSp(35f, scale),
            modifier = Modifier
                .offset(x = scaledDp(105f, scale), y = scaledDp(18f, scale))
                .size(width = scaledDp(150f, scale), height = scaledDp(64f, scale)),
            style = HomeTextStyles.bold.copy(
                color = HomeColors.ink,
                textAlign = TextAlign.Center,
            ),
        )
        HomeProgressTrack(
            progress = progress,
            modifier = Modifier
                .offset(x = scaledDp(276f, scale), y = scaledDp(39f, scale))
                .size(width = scaledDp(365f, scale), height = scaledDp(31f, scale)),
        )
    }
}

@Composable
private fun HomeProgressTrack(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val trackHeight = size.height * 0.58f
        val trackTop = (size.height - trackHeight) / 2f
        val radius = trackHeight / 2f
        drawRoundRect(
            color = HomeColors.progressGrooveShadow,
            topLeft = Offset(0f, trackTop + size.height * 0.08f),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius),
        )
        drawRoundRect(
            color = HomeColors.progressGroove,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius),
        )
        val clamped = progress.coerceIn(0f, 1f)
        val knobX = radius + (size.width - trackHeight) * clamped
        if (clamped > 0f) {
            drawRoundRect(
                color = HomeColors.mint.copy(alpha = 0.58f),
                topLeft = Offset(0f, trackTop),
                size = Size(knobX, trackHeight),
                cornerRadius = CornerRadius(radius),
            )
        }
        drawCircle(
            color = HomeColors.mintEdge,
            radius = radius,
            center = Offset(knobX, size.height / 2f + size.height * 0.05f),
        )
        drawCircle(
            color = HomeColors.mintLight,
            radius = radius * 0.86f,
            center = Offset(knobX, size.height / 2f),
        )
    }
}

@Composable
internal fun LevelSelectButton(
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TactilePanel(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(scaledDp(96f, scale)),
        depth = scaledDp(21f, scale),
        edgeColor = HomeColors.warmEdge,
        faceColors = listOf(HomeColors.creamHighlight, HomeColors.creamFace),
        borderColor = HomeColors.mintStroke,
        borderWidth = scaledDp(5f, scale),
        innerBorder = true,
    ) {
        LocationGlyph(
            modifier = Modifier
                .offset(x = scaledDp(83f, scale), y = scaledDp(35f, scale))
                .size(width = scaledDp(116f, scale), height = scaledDp(111f, scale)),
        )
        FittedText(
            text = stringResource(R.string.feature_game_home_select_levels),
            minFontSize = scaledSp(34f, scale),
            maxFontSize = scaledSp(55f, scale),
            modifier = Modifier
                .offset(x = scaledDp(224f, scale), y = scaledDp(36f, scale))
                .size(width = scaledDp(356f, scale), height = scaledDp(103f, scale)),
            style = HomeTextStyles.black.copy(
                color = HomeColors.ink,
                textAlign = TextAlign.Center,
            ),
        )
        ChevronGlyph(
            modifier = Modifier
                .offset(x = scaledDp(645f, scale), y = scaledDp(65f, scale))
                .size(width = scaledDp(52f, scale), height = scaledDp(69f, scale)),
        )
    }
}

@Composable
private fun LocationGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val baseTop = size.height * 0.70f
        drawRoundRect(
            color = HomeColors.mintEdge,
            topLeft = Offset(size.width * 0.08f, baseTop + size.height * 0.06f),
            size = Size(size.width * 0.84f, size.height * 0.22f),
            cornerRadius = CornerRadius(size.height * 0.08f),
        )
        drawRoundRect(
            color = HomeColors.mintLight,
            topLeft = Offset(size.width * 0.08f, baseTop),
            size = Size(size.width * 0.84f, size.height * 0.22f),
            cornerRadius = CornerRadius(size.height * 0.08f),
        )
        val pin = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.84f)
            cubicTo(
                size.width * 0.37f,
                size.height * 0.61f,
                size.width * 0.23f,
                size.height * 0.45f,
                size.width * 0.23f,
                size.height * 0.29f,
            )
            cubicTo(
                size.width * 0.23f,
                size.height * 0.05f,
                size.width * 0.77f,
                size.height * 0.05f,
                size.width * 0.77f,
                size.height * 0.29f,
            )
            cubicTo(
                size.width * 0.77f,
                size.height * 0.45f,
                size.width * 0.63f,
                size.height * 0.61f,
                size.width * 0.50f,
                size.height * 0.84f,
            )
            close()
        }
        drawPath(pin, HomeColors.mintEdge)
        drawCircle(
            color = HomeColors.creamHighlight,
            radius = size.minDimension * 0.105f,
            center = Offset(size.width * 0.50f, size.height * 0.29f),
        )
    }
}

@Composable
private fun ChevronGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.08f)
            lineTo(size.width * 0.82f, size.height * 0.50f)
            lineTo(size.width * 0.18f, size.height * 0.92f)
        }
        drawPath(
            path = path,
            color = HomeColors.mintEdge,
            style = Stroke(width = size.width * 0.27f, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            color = HomeColors.mintLight,
            style = Stroke(width = size.width * 0.17f, cap = StrokeCap.Round),
        )
    }
}

/** 统一的软陶双层面板，阴影、边框与厚度只维护一份。 */
@Composable
private fun TactilePanel(
    modifier: Modifier,
    shape: RoundedCornerShape,
    depth: Dp,
    edgeColor: Color,
    faceColors: List<Color>,
    borderColor: Color,
    borderWidth: Dp,
    innerBorder: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = depth * 0.42f)
                .background(edgeColor, shape),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = depth)
                .shadow(depth * 0.35f, shape, clip = false)
                .background(Brush.verticalGradient(faceColors), shape)
                .border(borderWidth, borderColor, shape),
        )
        if (innerBorder) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(
                        start = borderWidth * 2.2f,
                        top = borderWidth * 2.2f,
                        end = borderWidth * 2.2f,
                        bottom = depth + borderWidth * 2.2f,
                    )
                    .border(borderWidth * 0.72f, HomeColors.mintLight, shape),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = depth),
            content = content,
        )
    }
}

@Composable
private fun FittedText(
    text: String,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    AdaptiveSingleLineText(
        text = text,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        style = style,
        modifier = modifier,
    )
}

@Composable
private fun primaryButtonLabel(state: GameHomeUiState): String = when (state.primaryAction) {
    GameHomePrimaryAction.OPEN_CURRENT_LEVEL -> stringResource(
        R.string.feature_game_home_level_format,
        state.targetLevelNumber,
    )
    GameHomePrimaryAction.OPEN_NEXT_LEVEL -> stringResource(
        R.string.feature_game_home_level_format,
        state.targetLevelNumber,
    )
    GameHomePrimaryAction.RESTART_CURRENT_LEVEL -> stringResource(
        R.string.feature_game_home_level_format,
        state.targetLevelNumber,
    )
    GameHomePrimaryAction.RETRY_LOAD -> stringResource(R.string.feature_game_home_retry)
    GameHomePrimaryAction.NONE -> stringResource(R.string.feature_game_home_all_complete)
}

private fun scaledDp(designPixels: Float, scale: Float): Dp = (designPixels * scale).dp

private fun scaledSp(designPixels: Float, scale: Float) = (designPixels * scale).sp

private object HomeTextStyles {
    val bold = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
    )
    val black = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
    )
}

/** 首页专属视觉令牌集中于此，不影响棋盘和选关页的共享主题。 */
private object HomeColors {
    val ink = Color(0xFF246B69)
    val creamHighlight = Color(0xFFFFFCF3)
    val creamFace = Color(0xFFF8EDDA)
    val creamStroke = Color(0xFFE8D8BC)
    val warmEdge = Color(0xFFD7B98E)
    val mintLight = Color(0xFF8DD5C1)
    val mint = Color(0xFF70C1AD)
    val mintStroke = Color(0xFF62A997)
    val mintEdge = Color(0xFF3D8C7B)
    val coralLight = Color(0xFFFF9278)
    val coral = Color(0xFFF2765F)
    val coralEdge = Color(0xFFC45747)
    val starGold = Color(0xFFF6B92E)
    val leaf = Color(0xFF84A95B)
    val progressGroove = Color(0xFFE5D8C4)
    val progressGrooveShadow = Color(0xFFCDBCA2)
    val playShadow = Color(0xFFD6C4A7)
    val disabledLight = Color(0xFFD9DED9)
    val disabled = Color(0xFFB9C3BE)
}
