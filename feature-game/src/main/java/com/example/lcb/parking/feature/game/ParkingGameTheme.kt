package com.example.lcb.parking.feature.game

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ImageGen 视觉方向对应的集中颜色令牌，三个页面不得各自复制色值。 */
@Immutable
data class ParkingGamePalette(
    val ink: Color = Color(0xFF26484A),
    // 深色交互令牌与奶油底均保持 WCAG AA 普通文字对比度。
    val inkSecondary: Color = Color(0xFF526A64),
    val cream: Color = Color(0xFFFFF8EC),
    val panel: Color = Color(0xFFFFFCF4),
    val mint: Color = Color(0xFF86DDBD),
    val mintDeep: Color = Color(0xFF337E69),
    val mintSoft: Color = Color(0xFFDFF5E9),
    val coral: Color = Color(0xFFBF493D),
    val sun: Color = Color(0xFFFFD65C),
    val sky: Color = Color(0xFF83CCE4),
    val locked: Color = Color(0xFFCAD4CF),
    val scrim: Color = Color(0x99213C3D),
)

val LocalParkingGamePalette = staticCompositionLocalOf { ParkingGamePalette() }

/**
 * 三个 Compose 页面共享的轻量主题。资源图只承担场景纹理，文字、状态和交互均原生绘制。
 */
@Composable
fun ParkingGameTheme(
    palette: ParkingGamePalette = ParkingGamePalette(),
    content: @Composable () -> Unit,
) {
    val colors = lightColorScheme(
        primary = palette.mintDeep,
        onPrimary = palette.cream,
        secondary = palette.coral,
        onSecondary = palette.cream,
        tertiary = palette.sun,
        onTertiary = palette.ink,
        tertiaryContainer = palette.mintSoft,
        onTertiaryContainer = palette.ink,
        background = Color.Transparent,
        onBackground = palette.ink,
        surface = palette.panel,
        onSurface = palette.ink,
        surfaceVariant = palette.mintSoft,
        onSurfaceVariant = palette.inkSecondary,
        outline = palette.mintDeep.copy(alpha = 0.24f),
        scrim = palette.scrim,
    )
    val typography = Typography(
        headlineLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 30.sp,
            lineHeight = 34.sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        ),
    )
    CompositionLocalProvider(LocalParkingGamePalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            ),
            content = content,
        )
    }
}
