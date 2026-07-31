package com.example.lcb.parking.feature.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.graphics.withRotation
import androidx.customview.widget.ExploreByTouchHelper
import com.example.lcb.parking.feature.R
import kotlin.math.abs
import kotlin.math.min

/**
 * 单 Canvas 棋盘。该 View 只绘制、做按格命中并回传 vehicleId，不执行任何规则判断。
 * Paint 与 RectF 均缓存为字段，避免在 onDraw 中创建逐帧对象。
 */
class ParkingBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val boardFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(53, 67, 77)
    }
    private val boardTexturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boardTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(22, 10, 24, 31)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(62, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 158, 100) }
    private val wallHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(92, 255, 245, 218)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val vehicleBitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    private val vehicleFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vehicleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 7, 22, 28)
    }
    private val vehicleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val highlightHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(82, 255, 208, 82)
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 215, 91)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val lockedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(88, 17, 36, 43)
    }
    private val lockBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 245, 218)
    }
    private val lockGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 66, 69)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }

    private val boardBounds = RectF()
    private val cellBounds = RectF()
    private val vehicleBounds = RectF()
    private val vehicleShadowBounds = RectF()
    private val spriteDestinationBounds = RectF()
    private val lockGlyphBounds = RectF()
    private val accessibilityVehicleBounds = RectF()
    private val accessibilityNodeBounds = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val vehicleCornerRadius = 13f * density
    private val vehicleInset = PARKING_VEHICLE_CELL_INSET_DP * density
    private val wallInset = 3.5f * density
    private val artReadyCallback: () -> Unit = ::onArtAssetsReady

    private var boardTextureShader: BitmapShader? = null

    private val exitVisibilityGate = VehicleExitVisibilityGate()
    private var model: BoardRenderModel = BoardRenderModel.EMPTY
    private var boardInputEnabled: Boolean = false
    private var vehicleTapListener: ((String) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    private val animatedFramesByVehicleId = HashMap<String, AnimatedVehicleFrame>()
    private val animatedFrames = ArrayList<AnimatedVehicleFrame>(MAX_CONCURRENT_PRESENTATIONS)
    private val transientHighlightedVehicleIds = HashSet<String>(MAX_CONCURRENT_PRESENTATIONS)
    private var boardVerticalBias: Float = DEFAULT_BOARD_VERTICAL_BIAS
    private val virtualIdByVehicleId = HashMap<String, Int>()
    private var nextVirtualViewId = FIRST_VIRTUAL_VIEW_ID
    private val vehicleAccessibilityHelper = VehicleAccessibilityHelper(this)

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, vehicleAccessibilityHelper)
    }

    fun render(board: BoardRenderModel, acceptsInput: Boolean) {
        exitVisibilityGate.reconcile(board)
        model = board
        boardInputEnabled = acceptsInput
        ensureVirtualIds(board)
        recomputeBoardBounds(width, height)
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setOnVehicleTapListener(listener: ((String) -> Unit)?) {
        vehicleTapListener = listener
    }

    /**
     * 设置棋盘在完整动画画布中的垂直位置。Compose 游戏页使用偏上锚点，既缩短 HUD 与
     * 棋盘的空隙，又保留车辆驶出棋盘后继续绘制所需的屏外空间。
     */
    fun setBoardVerticalBias(bias: Float) {
        val stableBias = bias.coerceIn(0f, 1f)
        if (boardVerticalBias == stableBias) return
        boardVerticalBias = stableBias
        recomputeBoardBounds(width, height)
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    internal fun setAnimatedVehicleOffset(
        vehicleId: String,
        rowOffset: Float,
        columnOffset: Float,
        renderVehicle: VehicleRenderModel? = null,
    ) {
        val frame = animatedFramesByVehicleId[vehicleId] ?: AnimatedVehicleFrame().also {
            animatedFramesByVehicleId[vehicleId] = it
            animatedFrames += it
        }
        frame.rowOffset = rowOffset
        frame.columnOffset = columnOffset
        if (renderVehicle != null) frame.ghost = renderVehicle
        invalidate()
    }

    internal fun clearAnimatedVehicleOffset(vehicleId: String) {
        val removed = animatedFramesByVehicleId.remove(vehicleId) ?: return
        animatedFrames.remove(removed)
        invalidate()
    }

    internal fun clearAllAnimatedVehicleOffsets() {
        if (animatedFrames.isEmpty()) return
        animatedFramesByVehicleId.clear()
        animatedFrames.clear()
        invalidate()
    }

    /**
     * 离场动画已到终点，但业务确认仍在异步队列中；先隐藏源车位避免偏移清零后闪回。
     */
    internal fun hideVehicleAtSourceUntilStateUpdate(vehicleId: String) {
        exitVisibilityGate.suppressUntilSnapshotRemoval(vehicleId)
        clearAnimatedVehicleOffset(vehicleId)
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    /** 新会话绑定时清理所有进程内表现状态，防止相同 vehicleId 跨关卡残留。 */
    internal fun resetPresentationState() {
        exitVisibilityGate.reset()
        animatedFramesByVehicleId.clear()
        animatedFrames.clear()
        transientHighlightedVehicleIds.clear()
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    internal fun setTransientHighlight(vehicleId: String, highlighted: Boolean) {
        val changed = if (highlighted) {
            transientHighlightedVehicleIds.add(vehicleId)
        } else {
            transientHighlightedVehicleIds.remove(vehicleId)
        }
        if (!changed) return
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    internal fun clearAllTransientHighlights() {
        if (transientHighlightedVehicleIds.isEmpty()) return
        transientHighlightedVehicleIds.clear()
        vehicleAccessibilityHelper.invalidateRoot()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 图片解码统一在美术仓库的后台线程完成，onDraw 只消费已就绪的缓存。
        ParkingArtRepository.prepare(resources, artReadyCallback)
    }

    private fun onArtAssetsReady() {
        if (boardTextureShader == null) {
            ParkingArtRepository.boardTextureBitmap()?.let { texture ->
                boardTextureShader = BitmapShader(
                    texture,
                    Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT,
                ).also { shader -> boardTexturePaint.shader = shader }
            }
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        recomputeBoardBounds(width, height)
        vehicleAccessibilityHelper.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val board = model
        if (board.rows <= 0 || board.columns <= 0 || boardBounds.isEmpty) return

        val cellWidth = boardBounds.width() / board.columns
        val cellHeight = boardBounds.height() / board.rows
        drawBoardSurface(canvas)

        var column = 1
        while (column < board.columns) {
            val x = boardBounds.left + column * cellWidth
            canvas.drawLine(x, boardBounds.top, x, boardBounds.bottom, gridPaint)
            column++
        }
        var row = 1
        while (row < board.rows) {
            val y = boardBounds.top + row * cellHeight
            canvas.drawLine(boardBounds.left, y, boardBounds.right, y, gridPaint)
            row++
        }

        drawWalls(canvas, board, cellWidth, cellHeight)
        drawVehicles(canvas, board, cellWidth, cellHeight)
    }

    private fun drawBoardSurface(canvas: Canvas) {
        val surfacePaint = if (boardTextureShader == null) boardFallbackPaint else boardTexturePaint
        // 棋盘即道路本身，不额外绘制容器或圆角边框，最大化复杂关卡的可用面积。
        canvas.drawRect(boardBounds, surfacePaint)
        // 极轻的统一罩色压低纹理对比，保证车辆与网格始终清晰。
        canvas.drawRect(boardBounds, boardTintPaint)
    }

    private fun drawWalls(
        canvas: Canvas,
        board: BoardRenderModel,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        var index = 0
        while (index < board.walls.size) {
            val wall = board.walls[index]
            setCellRect(
                wall.row.toFloat(),
                wall.column.toFloat(),
                wall.widthCells,
                wall.heightCells,
                cellWidth,
                cellHeight,
                cellBounds,
            )
            cellBounds.inset(wallInset, wallInset)
            canvas.drawRoundRect(cellBounds, vehicleCornerRadius, vehicleCornerRadius, wallPaint)
            canvas.drawRoundRect(cellBounds, vehicleCornerRadius, vehicleCornerRadius, wallHighlightPaint)
            index++
        }
    }

    private fun drawVehicles(
        canvas: Canvas,
        board: BoardRenderModel,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        var index = 0
        while (index < board.vehicles.size) {
            val vehicle = board.vehicles[index]
            if (
                !exitVisibilityGate.isSuppressed(vehicle.id) &&
                vehicle.visualState != VehicleVisualState.EXITED &&
                vehicle.visualState != VehicleVisualState.TOWED
            ) {
                val animation = animatedFramesByVehicleId[vehicle.id]
                val rowOffset = animation?.rowOffset ?: 0f
                val columnOffset = animation?.columnOffset ?: 0f
                drawVehicleAt(
                    canvas = canvas,
                    vehicle = vehicle,
                    row = vehicle.row + rowOffset,
                    column = vehicle.column + columnOffset,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    highlighted = vehicle.id == board.highlightedVehicleId ||
                        vehicle.id in transientHighlightedVehicleIds,
                )
            }
            index++
        }

        var frameIndex = 0
        while (frameIndex < animatedFrames.size) {
            val frame = animatedFrames[frameIndex]
            val ghost = frame.ghost
            if (
                ghost != null &&
                !exitVisibilityGate.isSuppressed(ghost.id) &&
                !containsVisibleVehicle(board, ghost.id)
            ) {
                drawVehicleAt(
                    canvas = canvas,
                    vehicle = ghost,
                    row = ghost.row + frame.rowOffset,
                    column = ghost.column + frame.columnOffset,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    highlighted = false,
                )
            }
            frameIndex++
        }
    }

    private fun drawVehicleAt(
        canvas: Canvas,
        vehicle: VehicleRenderModel,
        row: Float,
        column: Float,
        cellWidth: Float,
        cellHeight: Float,
        highlighted: Boolean,
    ) {
        setCellRect(
            row,
            column,
            vehicle.widthCells,
            vehicle.heightCells,
            cellWidth,
            cellHeight,
            vehicleBounds,
        )
        vehicleBounds.inset(vehicleInset, vehicleInset)

        val bitmap = ParkingArtRepository.vehicleBitmap(vehicle)
        if (bitmap != null) {
            // 2 格轿车素材绝不按 3 格占位强行拉长；命中与碰撞仍使用领域占格。
            fitVehicleBoundsToBitmap(bitmap, vehicle.direction)
        }
        vehicleShadowBounds.set(vehicleBounds)
        vehicleShadowBounds.offset(0f, 2.5f * density)
        canvas.drawRoundRect(
            vehicleShadowBounds,
            vehicleCornerRadius,
            vehicleCornerRadius,
            vehicleShadowPaint,
        )
        if (highlighted) {
            canvas.drawRoundRect(
                vehicleBounds,
                vehicleCornerRadius,
                vehicleCornerRadius,
                highlightHaloPaint,
            )
        }

        if (bitmap == null) {
            drawFallbackVehicle(canvas, vehicle)
        } else {
            drawVehicleBitmap(canvas, bitmap, vehicle.direction)
        }

        if (vehicle.visualState == VehicleVisualState.LOCKED) drawLockedOverlay(canvas)
        if (highlighted) {
            canvas.drawRoundRect(
                vehicleBounds,
                vehicleCornerRadius,
                vehicleCornerRadius,
                highlightPaint,
            )
        }
    }

    /** 源素材统一朝上；通过 Canvas 旋转避免缓存四套方向 Bitmap。 */
    private fun drawVehicleBitmap(canvas: Canvas, bitmap: Bitmap, direction: VehicleDirection) {
        val centerX = vehicleBounds.centerX()
        val centerY = vehicleBounds.centerY()
        val shortSide = min(vehicleBounds.width(), vehicleBounds.height())
        val longSide = maxOf(vehicleBounds.width(), vehicleBounds.height())
        spriteDestinationBounds.set(
            centerX - shortSide / 2f,
            centerY - longSide / 2f,
            centerX + shortSide / 2f,
            centerY + longSide / 2f,
        )
        val rotation = when (direction) {
            VehicleDirection.UP -> 0f
            VehicleDirection.RIGHT -> 90f
            VehicleDirection.DOWN -> 180f
            VehicleDirection.LEFT -> -90f
        }
        canvas.withRotation(rotation, centerX, centerY) {
            drawBitmap(bitmap, null, spriteDestinationBounds, vehicleBitmapPaint)
        }
    }

    /** 在当前领域占位内等比 fit，任何车辆长度都不会改变源素材宽高比。 */
    private fun fitVehicleBoundsToBitmap(bitmap: Bitmap, direction: VehicleDirection) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val centerX = vehicleBounds.centerX()
        val centerY = vehicleBounds.centerY()
        val availableShortSide = min(vehicleBounds.width(), vehicleBounds.height())
        val availableLongSide = maxOf(vehicleBounds.width(), vehicleBounds.height())
        val sourceAspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val shortSide = min(availableShortSide, availableLongSide / sourceAspectRatio)
        val longSide = shortSide * sourceAspectRatio
        val visualWidth: Float
        val visualHeight: Float
        if (direction == VehicleDirection.UP || direction == VehicleDirection.DOWN) {
            visualWidth = shortSide
            visualHeight = longSide
        } else {
            visualWidth = longSide
            visualHeight = shortSide
        }
        vehicleBounds.set(
            centerX - visualWidth / 2f,
            centerY - visualHeight / 2f,
            centerX + visualWidth / 2f,
            centerY + visualHeight / 2f,
        )
    }

    /** 美术资源尚未就绪或单图解码失败时，使用无额外图标的轻量降级图形。 */
    private fun drawFallbackVehicle(canvas: Canvas, vehicle: VehicleRenderModel) {
        vehicleFallbackPaint.color = vehicle.color
        canvas.drawRoundRect(
            vehicleBounds,
            vehicleCornerRadius,
            vehicleCornerRadius,
            vehicleFallbackPaint,
        )
        canvas.drawRoundRect(
            vehicleBounds,
            vehicleCornerRadius,
            vehicleCornerRadius,
            vehicleOutlinePaint,
        )
    }

    private fun drawLockedOverlay(canvas: Canvas) {
        canvas.drawRoundRect(
            vehicleBounds,
            vehicleCornerRadius,
            vehicleCornerRadius,
            lockedOverlayPaint,
        )
        val radius = min(vehicleBounds.width(), vehicleBounds.height()) * 0.18f
        val centerX = vehicleBounds.centerX()
        val centerY = vehicleBounds.centerY()
        canvas.drawCircle(centerX, centerY, radius, lockBadgePaint)
        lockGlyphBounds.set(
            centerX - radius * 0.42f,
            centerY - radius * 0.62f,
            centerX + radius * 0.42f,
            centerY + radius * 0.12f,
        )
        canvas.drawArc(lockGlyphBounds, 180f, -180f, false, lockGlyphPaint)
        canvas.drawRect(
            centerX - radius * 0.52f,
            centerY - radius * 0.06f,
            centerX + radius * 0.52f,
            centerY + radius * 0.56f,
            lockGlyphPaint,
        )
    }

    private fun containsVisibleVehicle(board: BoardRenderModel, vehicleId: String): Boolean {
        var index = 0
        while (index < board.vehicles.size) {
            val vehicle = board.vehicles[index]
            if (
                vehicle.id == vehicleId &&
                vehicle.visualState != VehicleVisualState.EXITED &&
                vehicle.visualState != VehicleVisualState.TOWED
            ) {
                return true
            }
            index++
        }
        return false
    }

    private fun setCellRect(
        row: Float,
        column: Float,
        widthCells: Int,
        heightCells: Int,
        cellWidth: Float,
        cellHeight: Float,
        target: RectF,
    ) {
        val left = boardBounds.left + column * cellWidth
        val top = boardBounds.top + row * cellHeight
        target.set(left, top, left + widthCells * cellWidth, top + heightCells * cellHeight)
    }

    /** 为每辆车提供稳定的虚拟无障碍节点，TalkBack 可按车辆逐个聚焦并执行点击。 */
    private inner class VehicleAccessibilityHelper(host: View) : ExploreByTouchHelper(host) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val vehicleId = findTappableVehicleId(x, y) ?: return INVALID_ID
            return virtualIdByVehicleId[vehicleId] ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            val vehicles = model.vehicles
            var index = 0
            while (index < vehicles.size) {
                val vehicle = vehicles[index]
                if (isVehicleVisibleToAccessibility(vehicle)) {
                    virtualIdByVehicleId[vehicle.id]?.let(virtualViewIds::add)
                }
                index++
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val vehicle = vehicleForVirtualId(virtualViewId)
            if (vehicle == null || !isVehicleVisibleToAccessibility(vehicle)) {
                node.contentDescription = context.getString(R.string.feature_game_vehicle_unavailable)
                node.setVirtualBoundsInParent(EMPTY_ACCESSIBILITY_BOUNDS)
                node.isEnabled = false
                return
            }

            node.className = android.widget.Button::class.java.name
            node.contentDescription = vehicleAccessibilityDescription(vehicle)
            node.isFocusable = true
            val enabled = boardInputEnabled && vehicle.isTappable
            node.isClickable = enabled
            node.isEnabled = enabled
            node.isSelected = vehicle.id == model.highlightedVehicleId ||
                vehicle.id in transientHighlightedVehicleIds
            if (enabled) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            setAccessibilityBounds(vehicle, accessibilityNodeBounds)
            node.setVirtualBoundsInParent(accessibilityNodeBounds)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK || !boardInputEnabled) return false
            val vehicle = vehicleForVirtualId(virtualViewId)
                ?.takeIf(::isVehicleVisibleToAccessibility)
                ?: return false
            performClick()
            vehicleTapListener?.invoke(vehicle.id)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return vehicleAccessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    private fun ensureVirtualIds(board: BoardRenderModel) {
        var index = 0
        while (index < board.vehicles.size) {
            val vehicleId = board.vehicles[index].id
            if (vehicleId !in virtualIdByVehicleId) {
                virtualIdByVehicleId[vehicleId] = nextVirtualViewId++
            }
            index++
        }
    }

    private fun vehicleForVirtualId(virtualViewId: Int): VehicleRenderModel? {
        val vehicles = model.vehicles
        var index = 0
        while (index < vehicles.size) {
            val vehicle = vehicles[index]
            if (virtualIdByVehicleId[vehicle.id] == virtualViewId) return vehicle
            index++
        }
        return null
    }

    private fun isVehicleVisibleToAccessibility(vehicle: VehicleRenderModel): Boolean {
        return vehicle.visualState != VehicleVisualState.EXITED &&
            vehicle.visualState != VehicleVisualState.TOWED &&
            !exitVisibilityGate.isSuppressed(vehicle.id)
    }

    private fun vehicleAccessibilityDescription(vehicle: VehicleRenderModel): String {
        val color = context.getString(
            when (vehicle.artVariant) {
                VehicleArtVariant.CORAL -> R.string.feature_game_color_coral
                VehicleArtVariant.BLUE -> R.string.feature_game_color_blue
                VehicleArtVariant.YELLOW -> R.string.feature_game_color_yellow
                VehicleArtVariant.PURPLE -> R.string.feature_game_color_purple
                VehicleArtVariant.MINT -> R.string.feature_game_color_mint
                VehicleArtVariant.RED -> R.string.feature_game_color_red
            },
        )
        val direction = context.getString(
            when (vehicle.direction) {
                VehicleDirection.UP -> R.string.feature_game_vehicle_direction_up
                VehicleDirection.RIGHT -> R.string.feature_game_vehicle_direction_right
                VehicleDirection.DOWN -> R.string.feature_game_vehicle_direction_down
                VehicleDirection.LEFT -> R.string.feature_game_vehicle_direction_left
            },
        )
        val format = if (vehicle.visualState == VehicleVisualState.LOCKED) {
            R.string.feature_game_vehicle_accessibility_locked_format
        } else {
            R.string.feature_game_vehicle_accessibility_format
        }
        return context.getString(format, color, direction)
    }

    private fun setAccessibilityBounds(vehicle: VehicleRenderModel, target: Rect) {
        if (model.rows <= 0 || model.columns <= 0 || boardBounds.isEmpty) {
            target.set(EMPTY_ACCESSIBILITY_BOUNDS)
            return
        }
        val cellWidth = boardBounds.width() / model.columns
        val cellHeight = boardBounds.height() / model.rows
        setCellRect(
            row = vehicle.row.toFloat(),
            column = vehicle.column.toFloat(),
            widthCells = vehicle.widthCells,
            heightCells = vehicle.heightCells,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            target = accessibilityVehicleBounds,
        )
        accessibilityVehicleBounds.inset(vehicleInset, vehicleInset)
        accessibilityVehicleBounds.roundOut(target)
        if (!target.intersect(0, 0, width, height)) target.set(EMPTY_ACCESSIBILITY_BOUNDS)
    }

    /** AndroidX 1.1 仍通过该 API 设置虚拟节点边界；局部封装便于依赖升级后统一替换。 */
    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfoCompat.setVirtualBoundsInParent(bounds: Rect) {
        setBoundsInParent(bounds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!boardInputEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    val vehicleId = findTappableVehicleId(event.x, event.y)
                    if (vehicleId != null) {
                        performClick()
                        vehicleTapListener?.invoke(vehicleId)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findTappableVehicleId(x: Float, y: Float): String? {
        val board = model
        if (!boardBounds.contains(x, y) || board.rows <= 0 || board.columns <= 0) return null
        val column = ((x - boardBounds.left) / boardBounds.width() * board.columns).toInt()
        val row = ((y - boardBounds.top) / boardBounds.height() * board.rows).toInt()
        var index = board.vehicles.size - 1
        while (index >= 0) {
            val vehicle = board.vehicles[index]
            if (
                vehicle.isTappable &&
                !exitVisibilityGate.isSuppressed(vehicle.id) &&
                row >= vehicle.row && row < vehicle.row + vehicle.heightCells &&
                column >= vehicle.column && column < vehicle.column + vehicle.widthCells
            ) {
                return vehicle.id
            }
            index--
        }
        return null
    }

    private fun recomputeBoardBounds(viewWidth: Int, viewHeight: Int) {
        val board = model
        if (viewWidth <= 0 || viewHeight <= 0 || board.rows <= 0 || board.columns <= 0) {
            boardBounds.setEmpty()
            return
        }
        val availableWidth = (viewWidth - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val availableHeight = (viewHeight - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        val boardAspect = board.columns.toFloat() / board.rows
        var boardWidth = availableWidth
        var boardHeight = boardWidth / boardAspect
        if (boardHeight > availableHeight) {
            boardHeight = availableHeight
            boardWidth = boardHeight * boardAspect
        }
        val left = paddingLeft + (availableWidth - boardWidth) / 2f
        val top = paddingTop + (availableHeight - boardHeight) * boardVerticalBias
        boardBounds.set(left, top, left + boardWidth, top + boardHeight)
    }

    /** Mutable frame objects are reused by ValueAnimator updates to avoid per-frame allocations. */
    private class AnimatedVehicleFrame {
        var ghost: VehicleRenderModel? = null
        var rowOffset: Float = 0f
        var columnOffset: Float = 0f
    }

    private companion object {
        const val DEFAULT_BOARD_VERTICAL_BIAS = 0.5f
        const val FIRST_VIRTUAL_VIEW_ID = 1
        const val MAX_CONCURRENT_PRESENTATIONS = 8
        val EMPTY_ACCESSIBILITY_BOUNDS = Rect(0, 0, 1, 1)
    }
}
