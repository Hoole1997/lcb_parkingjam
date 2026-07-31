package com.example.lcb.parking.feature.game

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lcb.parking.feature.R
import kotlin.math.min

/**
 * ImageGen 定稿的一比一设置页。
 *
 * 位图只承载庭院纹理、立体边框和图标；所有可变文字由 Compose 绘制，避免国际化文案
 * 被烘焙进素材或因 NinePatch 拉伸而变形。
 */
@Composable
fun ComposeGameSettingsScreen(
    state: GameSettingsUiState,
    onBackRequested: () -> Unit,
    onLanguageRequested: () -> Unit,
    onFeedbackRequested: () -> Unit,
    onPrivacyPolicyRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.parking_settings_courtyard),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        val scale = min(
            maxWidth.value / SETTINGS_DESIGN_WIDTH,
            maxHeight.value / SETTINGS_DESIGN_HEIGHT,
        )
        Box(
            modifier = Modifier
                .size(
                    width = settingsDp(SETTINGS_DESIGN_WIDTH, scale),
                    height = settingsDp(SETTINGS_DESIGN_HEIGHT, scale),
                )
                .align(Alignment.TopCenter),
        ) {
            SettingsBackButton(
                scale = scale,
                onClick = onBackRequested,
                modifier = Modifier.offset(
                    x = settingsDp(55f, scale),
                    y = settingsDp(116f, scale),
                ),
            )
            SettingsTitle(scale = scale)

            SettingsMenuRow(
                backgroundRes = R.drawable.parking_settings_row_language,
                title = stringResource(R.string.feature_game_settings_language),
                value = state.languageDisplayName,
                scale = scale,
                y = 512f,
                contentDescription = stringResource(
                    R.string.feature_game_settings_language_accessibility,
                    state.languageDisplayName,
                ),
                onClick = onLanguageRequested,
            )
            SettingsMenuRow(
                backgroundRes = R.drawable.parking_settings_row_feedback,
                title = stringResource(R.string.feature_game_settings_feedback),
                subtitle = stringResource(R.string.feature_game_settings_feedback_subtitle),
                scale = scale,
                y = 738f,
                contentDescription = stringResource(R.string.feature_game_settings_feedback),
                onClick = onFeedbackRequested,
            )
            SettingsMenuRow(
                backgroundRes = R.drawable.parking_settings_row_privacy,
                title = stringResource(R.string.feature_game_settings_privacy),
                subtitle = stringResource(R.string.feature_game_settings_privacy_subtitle),
                scale = scale,
                y = 964f,
                contentDescription = stringResource(R.string.feature_game_settings_privacy),
                onClick = onPrivacyPolicyRequested,
            )
            SettingsMenuRow(
                backgroundRes = R.drawable.parking_settings_row_version,
                title = stringResource(R.string.feature_game_settings_version),
                value = state.versionDisplayName,
                scale = scale,
                y = 1190f,
                contentDescription = stringResource(
                    R.string.feature_game_settings_version_accessibility,
                    state.versionDisplayName,
                ),
                onClick = null,
            )
        }
    }
}

@Composable
private fun SettingsBackButton(
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visualSize = settingsDp(112f, scale)
    val touchSize = maxOf(48.dp, visualSize)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(touchSize)
            .clickable(
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.parking_settings_back_button),
            contentDescription = stringResource(R.string.feature_game_settings_back),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(visualSize),
        )
    }
}

@Composable
private fun SettingsTitle(scale: Float) {
    val width = settingsDp(510f, scale)
    val height = width * (188f / 773f)
    Box(
        modifier = Modifier
            .offset(
                x = settingsDp(216f, scale),
                y = settingsDp(264f, scale),
            )
            .size(width = width, height = height),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.parking_settings_title_plaque),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsFittedText(
            text = stringResource(R.string.feature_game_settings_title),
            minFontSize = settingsSp(29f, scale),
            maxFontSize = settingsSp(53f, scale),
            style = settingsTextStyle(FontWeight.ExtraBold).copy(textAlign = TextAlign.Center),
            modifier = Modifier.size(
                width = settingsDp(370f, scale),
                height = settingsDp(82f, scale),
            ),
        )
    }
}

@Composable
private fun SettingsMenuRow(
    @DrawableRes backgroundRes: Int,
    title: String,
    scale: Float,
    y: Float,
    contentDescription: String,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    value: String? = null,
) {
    val width = settingsDp(716f, scale)
    val height = width * (184f / 801f)
    val interactionModifier = if (onClick == null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
    }

    Box(
        modifier = Modifier
            .offset(x = settingsDp(113f, scale), y = settingsDp(y, scale))
            .size(width = width, height = height)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(settingsDp(31f, scale)))
            .then(interactionModifier),
    ) {
        Image(
            painter = painterResource(backgroundRes),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        val hasSubtitle = !subtitle.isNullOrBlank()
        SettingsFittedText(
            text = title,
            minFontSize = settingsSp(25f, scale),
            maxFontSize = settingsSp(43f, scale),
            style = settingsTextStyle(FontWeight.Bold),
            modifier = Modifier
                .offset(
                    x = settingsDp(181f, scale),
                    y = settingsDp(if (hasSubtitle) 30f else 43f, scale),
                )
                .size(
                    width = settingsDp(295f, scale),
                    height = settingsDp(if (hasSubtitle) 55f else 76f, scale),
                ),
        )

        if (hasSubtitle) {
            SettingsFittedText(
                text = subtitle.orEmpty(),
                minFontSize = settingsSp(20f, scale),
                maxFontSize = settingsSp(28f, scale),
                style = settingsTextStyle(FontWeight.Medium, secondary = true),
                modifier = Modifier
                    .offset(x = settingsDp(181f, scale), y = settingsDp(86f, scale))
                    .size(width = settingsDp(385f, scale), height = settingsDp(48f, scale)),
            )
        }

        if (!value.isNullOrBlank()) {
            SettingsFittedText(
                text = value,
                minFontSize = settingsSp(20f, scale),
                maxFontSize = settingsSp(31f, scale),
                style = settingsTextStyle(FontWeight.Bold, secondary = true),
                modifier = Modifier
                    .offset(x = settingsDp(487f, scale), y = settingsDp(45f, scale))
                    .size(width = settingsDp(165f, scale), height = settingsDp(68f, scale)),
            )
        }
    }
}

@Composable
private fun SettingsFittedText(
    text: String,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = style.copy(fontSize = maxFontSize),
            autoSize = TextAutoSize.StepBased(
                minFontSize = minFontSize,
                maxFontSize = maxFontSize,
                stepSize = 0.5.sp,
            ),
        )
    }
}

@Composable
private fun settingsTextStyle(
    weight: FontWeight,
    secondary: Boolean = false,
): TextStyle {
    val palette = LocalParkingGamePalette.current
    return TextStyle(
        color = if (secondary) palette.mintDeep else palette.ink,
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        textAlign = TextAlign.Start,
    )
}

private fun settingsDp(designPixels: Float, scale: Float): Dp = (designPixels * scale).dp

private fun settingsSp(designPixels: Float, scale: Float) = (designPixels * scale).sp

private const val SETTINGS_DESIGN_WIDTH = 941f
private const val SETTINGS_DESIGN_HEIGHT = 1672f
