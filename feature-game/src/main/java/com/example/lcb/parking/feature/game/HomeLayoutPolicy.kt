package com.example.lcb.parking.feature.game

import kotlin.math.min

/**
 * 首页效果图的虚拟画布。所有坐标都来自 941 x 1672 的 V4 定稿，避免在各 Composable
 * 中散落依赖具体机型的 magic number。
 */
internal object HomeDesignGrid {
    const val WIDTH = 941f
    const val HEIGHT = 1672f
    const val MAX_CONTENT_WIDTH_DP = 600f

    val header = HomeDesignRect(x = 0f, y = 107f, width = 941f, height = 224f)
    // 透明素材四周保留了安全像素；该外框映射后，其非透明主体恰好落在 V4 的
    // (84, 365, 857, 736) 可见范围内。
    val hero = HomeDesignRect(x = 60f, y = 342f, width = 905f, height = 784f)
    val primary = HomeDesignRect(x = 194f, y = 1106f, width = 554f, height = 173f)
    val progress = HomeDesignRect(x = 121f, y = 1281f, width = 697f, height = 121f)
    val levelSelect = HomeDesignRect(x = 78f, y = 1440f, width = 788f, height = 207f)
}

internal data class HomeDesignRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * 将定稿坐标映射到当前安全绘制区域。
 *
 * 元素尺寸始终按同一个 [contentScale] 等比缩放；长屏只拉开元素的纵向锚点，绝不纵向
 * 拉伸停车场或按钮。短屏则整体等比缩小，保证最后一个按钮仍在可见区域内。
 */
internal data class HomeLayoutPolicy(
    val viewportWidthDp: Float,
    val viewportHeightDp: Float,
    val contentScale: Float,
    val verticalAnchorScale: Float,
    val contentLeftDp: Float,
) {
    fun x(designX: Float): Float = contentLeftDp + designX * contentScale

    fun y(designY: Float): Float = designY * verticalAnchorScale

    fun size(designSize: Float): Float = designSize * contentScale
}

internal fun calculateHomeLayoutPolicy(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
): HomeLayoutPolicy {
    require(viewportWidthDp > 0f) { "Viewport width must be positive" }
    require(viewportHeightDp > 0f) { "Viewport height must be positive" }

    val cappedContentWidth = min(viewportWidthDp, HomeDesignGrid.MAX_CONTENT_WIDTH_DP)
    val horizontalScale = cappedContentWidth / HomeDesignGrid.WIDTH
    val heightScale = viewportHeightDp / HomeDesignGrid.HEIGHT
    val contentScale = min(horizontalScale, heightScale)
    val renderedWidth = HomeDesignGrid.WIDTH * contentScale

    return HomeLayoutPolicy(
        viewportWidthDp = viewportWidthDp,
        viewportHeightDp = viewportHeightDp,
        contentScale = contentScale,
        // 高屏把额外空间分配给纵向锚点；元素本身仍使用 contentScale。
        verticalAnchorScale = maxOf(contentScale, heightScale),
        contentLeftDp = (viewportWidthDp - renderedWidth) / 2f,
    )
}
