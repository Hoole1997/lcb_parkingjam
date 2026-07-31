package com.example.lcb.parking.feature.game

import kotlin.math.ceil
import kotlin.math.min

/** 游戏页只依据内容约束选型，避免把某一台手机的像素坐标写进 Compose。 */
enum class GamePlayLayoutMode {
    COMPACT,
    STANDARD,
    TABLET,
}

/**
 * 可由 JVM 测试覆盖的布局结果。数值单位统一为 dp，Compose 只负责转换为 Dp。
 *
 * 主棋盘没有装饰性 bleed 或外框，viewport 与真实棋盘尺寸一致；订单图标和临停车位
 * 分别占用固定的轻量区域，避免复杂关卡被底部信息卡挤压。
 */
data class GamePlayLayoutSpec(
    val mode: GamePlayLayoutMode,
    val contentWidthDp: Float,
    val hudHeightDp: Float,
    val orderIndicatorHeightDp: Float,
    val boardViewportWidthDp: Float,
    val boardViewportHeightDp: Float,
    val boardWidthDp: Float,
    val boardHeightDp: Float,
    val cellSizeDp: Float,
    val parkingSlotsHeightDp: Float,
    /** 实际车槽组宽度，不含页面级空白；Compose 使用它让车槽明确居中而非占满内容宽。 */
    val parkingContentWidthDp: Float,
    val verticalGapDp: Float,
    val slotColumns: Int,
    val slotRows: Int,
    val slotWidthDp: Float,
    val slotHeightDp: Float,
    val parkingSlotGapDp: Float,
) {
    val occupiedHeightDp: Float
        get() {
            val sectionHeights = listOf(
                hudHeightDp,
                orderIndicatorHeightDp,
                boardViewportHeightDp,
                parkingSlotsHeightDp,
            ).filter { it > 0f }
            return sectionHeights.sum() +
                verticalGapDp * (sectionHeights.size - 1).coerceAtLeast(0)
        }
}

object GamePlayLayoutPolicy {

    /**
     * 棋盘宽高由同一个 cellSize 推导，格子永远保持 1:1。
     * 当前容量 2–5 时车位保持单行；未来容量增加时最多五列并自然换行。
     */
    fun calculate(
        availableWidthDp: Float,
        availableHeightDp: Float,
        boardRows: Int,
        boardColumns: Int,
        parkingCapacity: Int,
        fontScale: Float,
    ): GamePlayLayoutSpec {
        require(availableWidthDp > 0f && availableHeightDp > 0f) {
            "Game play constraints must be positive"
        }
        require(boardRows >= 0 && boardColumns >= 0) { "Board dimensions cannot be negative" }
        require(parkingCapacity >= 0) { "Parking capacity cannot be negative" }

        val mode = when {
            availableWidthDp >= TABLET_MIN_WIDTH_DP -> GamePlayLayoutMode.TABLET
            availableWidthDp < COMPACT_MAX_WIDTH_DP ||
                availableHeightDp <= COMPACT_MAX_HEIGHT_DP ||
                fontScale >= COMPACT_FONT_SCALE -> GamePlayLayoutMode.COMPACT
            else -> GamePlayLayoutMode.STANDARD
        }
        val sideMargin = when (mode) {
            GamePlayLayoutMode.COMPACT -> COMPACT_SIDE_MARGIN_DP
            GamePlayLayoutMode.STANDARD -> STANDARD_SIDE_MARGIN_DP
            GamePlayLayoutMode.TABLET -> TABLET_SIDE_MARGIN_DP
        }
        val contentWidthLimit = if (mode == GamePlayLayoutMode.TABLET) {
            TABLET_MAX_CONTENT_WIDTH_DP
        } else {
            PHONE_MAX_CONTENT_WIDTH_DP
        }
        val contentWidth = min(
            availableWidthDp - sideMargin * 2f,
            contentWidthLimit,
        ).coerceAtLeast(MIN_CONTENT_WIDTH_DP)
        val gap = when (mode) {
            GamePlayLayoutMode.COMPACT -> COMPACT_GAP_DP
            GamePlayLayoutMode.STANDARD -> STANDARD_GAP_DP
            GamePlayLayoutMode.TABLET -> TABLET_GAP_DP
        }
        val hudHeight = if (mode == GamePlayLayoutMode.TABLET) TABLET_HUD_HEIGHT_DP else HUD_HEIGHT_DP
        val orderIndicatorHeight = if (parkingCapacity == 0) {
            0f
        } else {
            when (mode) {
                GamePlayLayoutMode.COMPACT -> COMPACT_ORDER_INDICATOR_HEIGHT_DP
                GamePlayLayoutMode.STANDARD -> STANDARD_ORDER_INDICATOR_HEIGHT_DP
                GamePlayLayoutMode.TABLET -> TABLET_ORDER_INDICATOR_HEIGHT_DP
            }
        }

        val slotGap = when (mode) {
            GamePlayLayoutMode.COMPACT -> COMPACT_SLOT_GAP_DP
            GamePlayLayoutMode.STANDARD -> STANDARD_SLOT_GAP_DP
            GamePlayLayoutMode.TABLET -> TABLET_SLOT_GAP_DP
        }
        val desiredSlotWidth = when (mode) {
            GamePlayLayoutMode.COMPACT -> COMPACT_SLOT_WIDTH_DP
            GamePlayLayoutMode.STANDARD -> STANDARD_SLOT_WIDTH_DP
            GamePlayLayoutMode.TABLET -> TABLET_SLOT_WIDTH_DP
        }
        val slotColumns = if (parkingCapacity == 0) {
            0
        } else {
            min(parkingCapacity, MAX_SLOT_COLUMNS)
        }
        val slotRows = if (slotColumns == 0) {
            0
        } else {
            ceil(parkingCapacity.toDouble() / slotColumns.toDouble()).toInt()
        }
        val slotWidth = if (slotColumns == 0) {
            0f
        } else {
            min(
                desiredSlotWidth,
                (contentWidth - slotGap * (slotColumns - 1)) / slotColumns,
            )
        }
        val slotHeight = slotWidth * SLOT_HEIGHT_RATIO
        val parkingSlotsHeight = if (slotRows == 0) {
            0f
        } else {
            slotRows * slotHeight + (slotRows - 1) * slotGap
        }
        val parkingContentWidth = if (slotColumns == 0) {
            0f
        } else {
            slotColumns * slotWidth + (slotColumns - 1) * slotGap
        }

        val nonBoardSections = listOf(
            hudHeight,
            orderIndicatorHeight,
            parkingSlotsHeight,
        ).filter { it > 0f }
        val totalSectionCount = nonBoardSections.size + 1 // board
        val reservedHeight = nonBoardSections.sum() + gap * (totalSectionCount - 1)
        val boardAvailableHeight =
            (availableHeightDp - reservedHeight).coerceAtLeast(MIN_BOARD_VIEWPORT_DP)
        val stableRows = boardRows.coerceAtLeast(1)
        val stableColumns = boardColumns.coerceAtLeast(1)
        val maxCellSize = if (mode == GamePlayLayoutMode.TABLET) TABLET_MAX_CELL_SIZE_DP else Float.MAX_VALUE
        val cellSize = min(
            min(contentWidth / stableColumns, boardAvailableHeight / stableRows),
            maxCellSize,
        ).coerceAtLeast(MIN_CELL_SIZE_DP)
        val boardWidth = cellSize * stableColumns
        val boardHeight = cellSize * stableRows

        return GamePlayLayoutSpec(
            mode = mode,
            contentWidthDp = contentWidth,
            hudHeightDp = hudHeight,
            orderIndicatorHeightDp = orderIndicatorHeight,
            boardViewportWidthDp = boardWidth,
            boardViewportHeightDp = boardHeight,
            boardWidthDp = boardWidth,
            boardHeightDp = boardHeight,
            cellSizeDp = cellSize,
            parkingSlotsHeightDp = parkingSlotsHeight,
            parkingContentWidthDp = parkingContentWidth,
            verticalGapDp = gap,
            slotColumns = slotColumns,
            slotRows = slotRows,
            slotWidthDp = slotWidth,
            slotHeightDp = slotHeight,
            parkingSlotGapDp = slotGap,
        )
    }

    private const val COMPACT_MAX_WIDTH_DP = 360f
    private const val COMPACT_MAX_HEIGHT_DP = 640f
    private const val COMPACT_FONT_SCALE = 1.30f
    private const val TABLET_MIN_WIDTH_DP = 600f
    private const val PHONE_MAX_CONTENT_WIDTH_DP = 600f
    private const val TABLET_MAX_CONTENT_WIDTH_DP = 600f
    private const val MIN_CONTENT_WIDTH_DP = 280f
    private const val COMPACT_SIDE_MARGIN_DP = 4f
    private const val STANDARD_SIDE_MARGIN_DP = 6f
    private const val TABLET_SIDE_MARGIN_DP = 16f
    private const val HUD_HEIGHT_DP = 48f
    private const val TABLET_HUD_HEIGHT_DP = 56f
    private const val COMPACT_GAP_DP = 5f
    private const val STANDARD_GAP_DP = 7f
    private const val TABLET_GAP_DP = 10f
    private const val COMPACT_ORDER_INDICATOR_HEIGHT_DP = 24f
    private const val STANDARD_ORDER_INDICATOR_HEIGHT_DP = 28f
    private const val TABLET_ORDER_INDICATOR_HEIGHT_DP = 34f
    private const val COMPACT_SLOT_WIDTH_DP = 44f
    private const val STANDARD_SLOT_WIDTH_DP = 48f
    private const val TABLET_SLOT_WIDTH_DP = 58f
    private const val COMPACT_SLOT_GAP_DP = 6f
    private const val STANDARD_SLOT_GAP_DP = 8f
    private const val TABLET_SLOT_GAP_DP = 10f
    private const val SLOT_HEIGHT_RATIO = 1.22f
    private const val MAX_SLOT_COLUMNS = 5
    private const val TABLET_MAX_CELL_SIZE_DP = 75f
    private const val MIN_BOARD_VIEWPORT_DP = 120f
    private const val MIN_CELL_SIZE_DP = 1f
}
