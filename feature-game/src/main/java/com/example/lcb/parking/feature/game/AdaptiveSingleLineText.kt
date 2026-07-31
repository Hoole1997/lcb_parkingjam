package com.example.lcb.parking.feature.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 单行国际化文本：优先在设计字号区间内自动缩放，极端长文案才使用省略号兜底。
 *
 * 首页和关卡页共用同一套规则，避免各页面分别维护语言长度的特殊判断。
 */
@Composable
internal fun AdaptiveSingleLineText(
    text: String,
    minFontSize: TextUnit,
    maxFontSize: TextUnit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = style.copy(fontSize = maxFontSize),
            autoSize = TextAutoSize.StepBased(
                minFontSize = minFontSize,
                maxFontSize = maxFontSize,
                stepSize = 0.5.sp,
            ),
        )
    }
}
