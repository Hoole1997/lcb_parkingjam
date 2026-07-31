package com.example.lcb.parking.feature.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.lcb.parking.feature.R
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 关卡节点的稳定展示状态，点击资格只由该状态决定。 */
enum class LevelNodeStatus {
    COMPLETED,
    CURRENT,
    AVAILABLE,
    LOCKED,
}

/**
 * 单个地图节点的数据。Boss 与 Hard 是正交视觉语义，不改变节点的解锁状态。
 */
data class LevelNodeUiState(
    val levelNumber: Int,
    val stars: Int,
    val status: LevelNodeStatus,
    val isBoss: Boolean,
    val isHardPreview: Boolean,
) {
    init {
        require(levelNumber > 0) { "Level number must be positive" }
        require(stars in 0..MAX_STARS) { "Stars must be between 0 and $MAX_STARS" }
    }

    private companion object {
        const val MAX_STARS = 3
    }
}

/** 关卡选择页一次 render 所需的完整不可变快照。 */
data class LevelSelectUiState(
    val starProgress: StarProgressUiState,
    val continueLevelNumber: Int,
    val nodes: List<LevelNodeUiState>,
) {
    val totalStars: Int
        get() = starProgress.earned

    init {
        require(continueLevelNumber > 0) { "Continue level number must be positive" }
        require(nodes.map(LevelNodeUiState::levelNumber).distinct().size == nodes.size) {
            "Level numbers must be unique"
        }
    }
}

/**
 * 可由任意宿主组合的关卡选择页。
 *
 * 页面只负责呈现与语义点击，不读取存档、不启动 Activity，也不依赖 Launcher/广告 SDK。
 */
class LevelSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface HostCallbacks {
        fun onBackRequested()
        fun onLevelSelected(levelNumber: Int)
        fun onContinueRequested(levelNumber: Int)
    }

    private val scrollView: ScrollView
    private val mapView: LevelSelectMapView
    private val header: View
    private val bottomBar: View
    private val backButton: ImageButton
    private val starsLabel: TextView
    private val continueButton: AppCompatButton
    private val chromeGap = resources.getDimensionPixelSize(
        R.dimen.feature_game_level_select_scroll_chrome_gap,
    )

    private val headerBasePaddingStart: Int
    private val headerBasePaddingTop: Int
    private val headerBasePaddingEnd: Int
    private val headerBasePaddingBottom: Int
    private val bottomBasePaddingStart: Int
    private val bottomBasePaddingTop: Int
    private val bottomBasePaddingEnd: Int
    private val bottomBasePaddingBottom: Int

    private var hostCallbacks: HostCallbacks? = null
    private var renderedState: LevelSelectUiState? = null
    private var displayCutoutTopInset = 0
    private var bottomGestureInset = 0
    private var lastAutoScrolledLevel: Int? = null

    init {
        setBackgroundResource(R.drawable.parking_courtyard_background)
        LayoutInflater.from(context).inflate(R.layout.feature_game_level_select, this, true)

        scrollView = findViewById(R.id.feature_game_level_select_scroll)
        header = findViewById(R.id.feature_game_level_select_header)
        bottomBar = findViewById(R.id.feature_game_level_select_bottom)
        backButton = findViewById(R.id.feature_game_level_select_back)
        starsLabel = findViewById(R.id.feature_game_level_select_stars)
        continueButton = findViewById(R.id.feature_game_level_select_continue)

        val mapHost = findViewById<FrameLayout>(R.id.feature_game_level_select_map_host)
        mapView = LevelSelectMapView(context)
        mapHost.addView(
            mapView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        headerBasePaddingStart = header.paddingStart
        headerBasePaddingTop = header.paddingTop
        headerBasePaddingEnd = header.paddingEnd
        headerBasePaddingBottom = header.paddingBottom
        bottomBasePaddingStart = bottomBar.paddingStart
        bottomBasePaddingTop = bottomBar.paddingTop
        bottomBasePaddingEnd = bottomBar.paddingEnd
        bottomBasePaddingBottom = bottomBar.paddingBottom

        backButton.setOnClickListener { hostCallbacks?.onBackRequested() }
        continueButton.setOnClickListener {
            renderedState?.continueLevelNumber?.let { levelNumber ->
                hostCallbacks?.onContinueRequested(levelNumber)
            }
        }
        continueButton.isEnabled = false
        continueButton.alpha = DISABLED_ALPHA

        // 顶部与底部浮层尺寸改变时同步滚动内容安全区，不对全屏背景施加 Insets。
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollChromePadding() }
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollChromePadding() }
        installWindowInsetsHandling()
    }

    fun setHostCallbacks(callbacks: HostCallbacks?) {
        hostCallbacks = callbacks
    }

    /** 只接收稳定状态；所有节点按关卡号排序后呈现，调用方列表不会被修改。 */
    fun render(state: LevelSelectUiState) {
        val stableState = state.copy(nodes = state.nodes.sortedBy(LevelNodeUiState::levelNumber))
        renderedState = stableState

        val possibleStars = stableState.starProgress.maximum
        starsLabel.text = context.getString(
            R.string.feature_game_level_select_stars_format,
            stableState.totalStars,
            possibleStars,
        )
        continueButton.text = context.getString(
            R.string.feature_game_level_select_continue_format,
            stableState.continueLevelNumber,
        )

        val continueNode = stableState.nodes.firstOrNull {
            it.levelNumber == stableState.continueLevelNumber
        }
        val continueEnabled = continueNode != null && continueNode.status != LevelNodeStatus.LOCKED
        continueButton.isEnabled = continueEnabled
        continueButton.alpha = if (continueEnabled) 1f else DISABLED_ALPHA

        mapView.render(stableState.nodes) { selectedLevel ->
            hostCallbacks?.onLevelSelected(selectedLevel)
        }

        val focusLevel = stableState.nodes.firstOrNull {
            it.status == LevelNodeStatus.CURRENT
        }?.levelNumber ?: stableState.continueLevelNumber
        scheduleInitialScroll(focusLevel)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun installWindowInsetsHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val mandatoryGestures = insets.getInsets(
                WindowInsetsCompat.Type.mandatorySystemGestures(),
            )
            val tappableElement = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val nextTop = cutout.top
            val nextBottom = max(mandatoryGestures.bottom, tappableElement.bottom)
            if (nextTop != displayCutoutTopInset || nextBottom != bottomGestureInset) {
                displayCutoutTopInset = nextTop
                bottomGestureInset = nextBottom
                applyChromeInsets()
            }
            insets
        }
    }

    private fun applyChromeInsets() {
        header.setPaddingRelative(
            headerBasePaddingStart,
            headerBasePaddingTop + displayCutoutTopInset,
            headerBasePaddingEnd,
            headerBasePaddingBottom,
        )
        bottomBar.setPaddingRelative(
            bottomBasePaddingStart,
            bottomBasePaddingTop,
            bottomBasePaddingEnd,
            bottomBasePaddingBottom + bottomGestureInset,
        )
        updateScrollChromePadding()
    }

    private fun updateScrollChromePadding() {
        val safeTop = header.height + chromeGap
        val safeBottom = bottomBar.height + chromeGap
        if (scrollView.paddingTop == safeTop && scrollView.paddingBottom == safeBottom) return
        scrollView.setPadding(0, safeTop, 0, safeBottom)
    }

    /** 仅在首次进入或当前关发生变化时定位，避免 render 刷新打断玩家主动滚动。 */
    private fun scheduleInitialScroll(levelNumber: Int) {
        if (lastAutoScrolledLevel == levelNumber) return
        lastAutoScrolledLevel = levelNumber
        scrollView.post {
            val nodeCenterY = mapView.centerYForLevel(levelNumber) ?: return@post
            val visibleCenter = (
                scrollView.paddingTop + scrollView.height - scrollView.paddingBottom
                ) / 2
            val targetY = nodeCenterY + scrollView.paddingTop - visibleCenter
            val maxScroll = max(
                0,
                mapView.height + scrollView.paddingTop + scrollView.paddingBottom - scrollView.height,
            )
            scrollView.scrollTo(0, targetY.roundToInt().coerceIn(0, maxScroll))
        }
    }

    private companion object {
        const val STARS_PER_LEVEL = 3
        const val DISABLED_ALPHA = 0.55f
    }
}

/** 纯数值布局结果，测试无需 Android 测量环境。 */
internal data class LevelNodePlacement(
    val levelNumber: Int,
    val centerX: Float,
    val centerY: Float,
    val isBranch: Boolean,
)

/**
 * 将普通节点排成纵向蛇形路线，Hard Preview 从前一主线节点分叉，不占用主线路径步长。
 */
internal object LevelSelectRoutePlanner {

    fun plan(
        nodes: List<LevelNodeUiState>,
        contentWidth: Float,
        horizontalInset: Float,
        maxTrackWidth: Float,
        topOffset: Float,
        verticalStep: Float,
    ): List<LevelNodePlacement> {
        if (nodes.isEmpty() || contentWidth <= 0f) return emptyList()
        require(horizontalInset >= 0f) { "Horizontal inset cannot be negative" }
        require(maxTrackWidth > 0f) { "Max track width must be positive" }
        require(verticalStep > 0f) { "Vertical step must be positive" }

        val sorted = nodes.sortedBy(LevelNodeUiState::levelNumber)
        val mainNodes = sorted.filterNot(LevelNodeUiState::isHardPreview)
        val availableWidth = (contentWidth - horizontalInset * 2f).coerceAtLeast(0f)
        val trackWidth = min(availableWidth, maxTrackWidth)
        val trackLeft = (contentWidth - trackWidth) / 2f
        val placements = ArrayList<LevelNodePlacement>(nodes.size)

        mainNodes.forEachIndexed { index, node ->
            val fraction = X_FRACTIONS[index % X_FRACTIONS.size]
            placements += LevelNodePlacement(
                levelNumber = node.levelNumber,
                centerX = trackLeft + trackWidth * fraction,
                centerY = topOffset + index * verticalStep,
                isBranch = false,
            )
        }

        sorted.filter(LevelNodeUiState::isHardPreview).forEach { hardNode ->
            val previous = placements.lastOrNull { it.levelNumber < hardNode.levelNumber }
            val next = placements.firstOrNull { it.levelNumber > hardNode.levelNumber }
            val anchorY = when {
                previous != null && next != null -> (previous.centerY + next.centerY) / 2f
                previous != null -> previous.centerY + verticalStep * BRANCH_VERTICAL_OFFSET
                next != null -> next.centerY - verticalStep * BRANCH_VERTICAL_OFFSET
                else -> topOffset
            }
            val center = contentWidth / 2f
            val anchorX = previous?.centerX ?: next?.centerX ?: center
            val branchFraction = if (anchorX <= center) BRANCH_RIGHT_FRACTION else BRANCH_LEFT_FRACTION
            placements += LevelNodePlacement(
                levelNumber = hardNode.levelNumber,
                centerX = trackLeft + trackWidth * branchFraction,
                centerY = anchorY,
                isBranch = true,
            )
        }

        return placements.sortedBy(LevelNodePlacement::levelNumber)
    }

    private val X_FRACTIONS = floatArrayOf(
        0.16f,
        0.34f,
        0.56f,
        0.76f,
        0.84f,
        0.74f,
        0.56f,
        0.34f,
        0.18f,
        0.24f,
        0.42f,
        0.64f,
        0.82f,
        0.70f,
        0.48f,
        0.26f,
    )
    private const val BRANCH_LEFT_FRACTION = 0.08f
    private const val BRANCH_RIGHT_FRACTION = 0.92f
    private const val BRANCH_VERTICAL_OFFSET = 0.55f
}

/** Canvas 只画路线，节点保持为原生子 View，以获得可靠触摸、键盘与无障碍行为。 */
private class LevelSelectMapView(context: Context) : ViewGroup(context) {

    private val density = resources.displayMetrics.density
    private val routeEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.feature_game_level_select_route_edge)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.getDimension(R.dimen.feature_game_level_select_route_edge_width)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.feature_game_level_select_route)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.getDimension(R.dimen.feature_game_level_select_route_width)
    }
    private val hardRoutePaint = Paint(routePaint).apply {
        color = ContextCompat.getColor(context, R.color.feature_game_level_select_hard)
        pathEffect = DashPathEffect(floatArrayOf(18f * density, 10f * density), 0f)
    }
    private val routePath = Path()
    private val branchPath = Path()
    private val normalNodeSize = resources.getDimensionPixelSize(
        R.dimen.feature_game_level_select_node_size,
    )
    private val bossNodeSize = resources.getDimensionPixelSize(
        R.dimen.feature_game_level_select_boss_node_size,
    )
    private val hardNodeSize = resources.getDimensionPixelSize(
        R.dimen.feature_game_level_select_hard_node_size,
    )
    private val routeStep = resources.getDimension(R.dimen.feature_game_level_select_route_step)
    private val routeTopSpace = resources.getDimension(
        R.dimen.feature_game_level_select_route_top_space,
    )
    private val routeBottomSpace = resources.getDimension(
        R.dimen.feature_game_level_select_route_bottom_space,
    )
    private val horizontalInset = resources.getDimension(
        R.dimen.feature_game_level_select_route_horizontal_inset,
    )
    private val maxTrackWidth = resources.getDimension(
        R.dimen.feature_game_level_select_route_max_width,
    )

    private var nodes: List<LevelNodeUiState> = emptyList()
    private var placements: List<LevelNodePlacement> = emptyList()
    private var mainPlacements: List<LevelNodePlacement> = emptyList()
    private var branchPlacements: List<LevelNodePlacement> = emptyList()
    private var placementByLevel: Map<Int, LevelNodePlacement> = emptyMap()
    private var onLevelSelected: ((Int) -> Unit)? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun render(nodes: List<LevelNodeUiState>, onLevelSelected: (Int) -> Unit) {
        this.onLevelSelected = onLevelSelected
        val sortedNodes = nodes.sortedBy(LevelNodeUiState::levelNumber)
        if (this.nodes.map(LevelNodeUiState::levelNumber) ==
            sortedNodes.map(LevelNodeUiState::levelNumber)
        ) {
            this.nodes = sortedNodes
            sortedNodes.forEachIndexed { index, node ->
                (getChildAt(index) as LevelNodeView).render(node)
            }
        } else {
            this.nodes = sortedNodes
            removeAllViews()
            sortedNodes.forEach { node ->
                addView(LevelNodeView(context).apply { render(node) })
            }
        }
        requestLayout()
        invalidate()
    }

    fun centerYForLevel(levelNumber: Int): Float? = placements.firstOrNull {
        it.levelNumber == levelNumber
    }?.centerY

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        placements = LevelSelectRoutePlanner.plan(
            nodes = nodes,
            contentWidth = measuredWidth.toFloat(),
            horizontalInset = horizontalInset,
            maxTrackWidth = maxTrackWidth,
            topOffset = routeTopSpace,
            verticalStep = routeStep,
        )
        mainPlacements = placements.filterNot(LevelNodePlacement::isBranch)
        branchPlacements = placements.filter(LevelNodePlacement::isBranch)
        placementByLevel = placements.associateBy(LevelNodePlacement::levelNumber)
        rebuildRoutePaths()

        nodes.forEachIndexed { index, node ->
            val childSize = nodeSize(node)
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(childSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childSize, MeasureSpec.EXACTLY),
            )
        }

        val lastCenter = placements.maxOfOrNull(LevelNodePlacement::centerY) ?: routeTopSpace
        val desiredHeight = ceil(lastCenter + routeBottomSpace).toInt()
        setMeasuredDimension(
            measuredWidth,
            resolveSize(desiredHeight.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        nodes.forEachIndexed { index, node ->
            val placement = placementByLevel[node.levelNumber] ?: return@forEachIndexed
            val child = getChildAt(index)
            val childLeft = (placement.centerX - child.measuredWidth / 2f).roundToInt()
            val childTop = (placement.centerY - child.measuredHeight / 2f).roundToInt()
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(routePath, routeEdgePaint)
        canvas.drawPath(routePath, routePaint)
        if (!branchPath.isEmpty) {
            canvas.drawPath(branchPath, routeEdgePaint)
            canvas.drawPath(branchPath, hardRoutePaint)
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        child.setOnClickListener { clicked ->
            val state = (clicked as LevelNodeView).nodeState
            if (state.status != LevelNodeStatus.LOCKED) {
                onLevelSelected?.invoke(state.levelNumber)
            }
        }
    }

    private fun buildPath(path: Path, points: List<LevelNodePlacement>) {
        path.reset()
        val first = points.firstOrNull() ?: return
        path.moveTo(first.centerX, first.centerY)
        var index = 1
        while (index < points.size) {
            appendCurve(path, points[index - 1], points[index])
            index++
        }
    }

    /** 测量状态改变时一次性缓存路径，滚动重绘不创建临时集合或 Path。 */
    private fun rebuildRoutePaths() {
        buildPath(routePath, mainPlacements)
        branchPath.reset()
        branchPlacements.forEach { branch ->
            val previous = mainPlacements.lastOrNull { it.levelNumber < branch.levelNumber }
                ?: return@forEach
            branchPath.moveTo(previous.centerX, previous.centerY)
            appendCurve(branchPath, previous, branch)
        }
    }

    private fun appendCurve(path: Path, from: LevelNodePlacement, to: LevelNodePlacement) {
        val middleY = (from.centerY + to.centerY) / 2f
        path.cubicTo(
            from.centerX,
            middleY,
            to.centerX,
            middleY,
            to.centerX,
            to.centerY,
        )
    }

    private fun nodeSize(node: LevelNodeUiState): Int = when {
        node.isBoss -> bossNodeSize
        node.isHardPreview -> hardNodeSize
        else -> normalNodeSize
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
    )

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(
        context,
        attrs,
    )

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params != null
}

/** 单节点自绘 View；Paint/Path/RectF 全部复用，重绘路径无临时 Bitmap。 */
private class LevelNodeView(context: Context) : View(context) {

    lateinit var nodeState: LevelNodeUiState
        private set

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        1f,
        resources.displayMetrics,
    )
    private val shadowOffset = resources.getDimension(
        R.dimen.feature_game_level_select_node_shadow_offset,
    )
    private val shadowExtra = resources.getDimension(
        R.dimen.feature_game_level_select_node_shadow_extra,
    )
    private val ringWidth = resources.getDimension(R.dimen.feature_game_level_select_node_ring)
    private val haloWidth = resources.getDimension(
        R.dimen.feature_game_level_select_current_halo_width,
    )

    private val creamColor = color(R.color.feature_game_level_select_cream)
    private val primaryTextColor = color(R.color.feature_game_level_select_text_primary)
    private val goldColor = color(R.color.feature_game_level_select_gold)
    private val mintColor = color(R.color.feature_game_level_select_mint_deep)
    private val hardColor = color(R.color.feature_game_level_select_hard)
    private val lockGlyphColor = color(R.color.feature_game_level_select_lock_glyph)
    private val completedColor = color(R.color.feature_game_level_select_completed)
    private val currentColor = color(R.color.feature_game_level_select_current)
    private val availableColor = color(R.color.feature_game_level_select_available)
    private val lockedColor = color(R.color.feature_game_level_select_locked)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.feature_game_level_select_shadow)
    }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = creamColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = mintColor
    }
    private val currentHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = haloWidth
        color = goldColor
    }
    private val hardOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = hardColor
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0)
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * scaledDensity
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * scaledDensity
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.feature_game_level_select_lock_glyph)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.3f * density
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = goldColor }
    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = goldColor }
    private val shapePath = Path()
    private val glyphBounds = RectF()
    private var levelText = ""
    private var badgeLabel: String? = null

    init {
        minimumWidth = resources.getDimensionPixelSize(
            R.dimen.feature_game_level_select_node_touch_min,
        )
        minimumHeight = minimumWidth
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render(state: LevelNodeUiState) {
        nodeState = state
        levelText = state.levelNumber.toString()
        badgeLabel = when {
            state.isHardPreview -> context.getString(R.string.feature_game_level_select_hard_badge)
            state.isBoss -> context.getString(R.string.feature_game_level_select_boss_badge)
            else -> null
        }
        fillPaint.color = fillColor(state.status)
        numberPaint.color = when (state.status) {
            LevelNodeStatus.AVAILABLE -> primaryTextColor
            LevelNodeStatus.LOCKED -> lockGlyphColor
            else -> creamColor
        }
        badgePaint.color = if (state.isHardPreview) hardColor else primaryTextColor
        badgeBackgroundPaint.color = if (state.isHardPreview) creamColor else goldColor
        isClickable = state.status != LevelNodeStatus.LOCKED
        isSelected = state.status == LevelNodeStatus.CURRENT
        contentDescription = buildContentDescription(state)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::nodeState.isInitialized) return

        val centerX = width / 2f
        val centerY = (height - shadowOffset) / 2f
        val outerRadius = min(width.toFloat(), height - shadowOffset) / 2f - shadowExtra
        val innerRadius = (outerRadius - ringWidth).coerceAtLeast(0f)

        canvas.drawCircle(centerX, centerY + shadowOffset, outerRadius + shadowExtra, shadowPaint)
        canvas.drawCircle(centerX, centerY, outerRadius, outerPaint)
        canvas.drawCircle(centerX, centerY, innerRadius, fillPaint)

        if (nodeState.status == LevelNodeStatus.AVAILABLE) {
            canvas.drawCircle(centerX, centerY, innerRadius, outlinePaint)
        }
        if (nodeState.status == LevelNodeStatus.CURRENT || isFocused) {
            canvas.drawCircle(centerX, centerY, outerRadius - haloWidth / 2f, currentHaloPaint)
        }
        if (nodeState.isHardPreview) {
            canvas.drawCircle(centerX, centerY, outerRadius - 1.5f * density, hardOutlinePaint)
        }

        when (nodeState.status) {
            LevelNodeStatus.LOCKED -> drawLockedNode(canvas, centerX, centerY, innerRadius)
            else -> drawOpenNode(canvas, centerX, centerY, innerRadius)
        }

        if (isPressed && isClickable) {
            canvas.drawCircle(centerX, centerY, innerRadius, pressedPaint)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // 让自绘节点向辅助服务呈现为标准按钮，而不是无语义的自定义画布。
        info.className = Button::class.java.name
        info.isClickable = isClickable
    }

    private fun drawOpenNode(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val hasBadge = nodeState.isBoss || nodeState.isHardPreview
        val label = badgeLabel

        if (label != null) {
            val badgeWidth = max(30f * density, badgePaint.measureText(label) + 10f * density)
            glyphBounds.set(
                centerX - badgeWidth / 2f,
                centerY - radius * 0.72f,
                centerX + badgeWidth / 2f,
                centerY - radius * 0.22f,
            )
            canvas.drawRoundRect(glyphBounds, 7f * density, 7f * density, badgeBackgroundPaint)
            val baseline = glyphBounds.centerY() - (badgePaint.ascent() + badgePaint.descent()) / 2f
            canvas.drawText(label, centerX, baseline, badgePaint)
        }

        val numberCenterY = centerY + if (hasBadge) radius * 0.23f else radius * 0.05f
        val numberBaseline = numberCenterY - (numberPaint.ascent() + numberPaint.descent()) / 2f
        canvas.drawText(levelText, centerX, numberBaseline, numberPaint)

        if (nodeState.status == LevelNodeStatus.COMPLETED && nodeState.stars > 0) {
            drawEarnedStars(
                canvas = canvas,
                centerX = centerX,
                centerY = centerY + radius * if (hasBadge) 0.67f else 0.58f,
                count = nodeState.stars,
            )
        } else if (nodeState.status == LevelNodeStatus.CURRENT) {
            drawPlayMarker(canvas, centerX + radius * 0.58f, centerY + radius * 0.58f)
        }
    }

    private fun drawLockedNode(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val lockCenterY = centerY - radius * 0.13f
        val lockWidth = radius * 0.62f
        val lockHeight = radius * 0.52f
        glyphBounds.set(
            centerX - lockWidth / 2f,
            lockCenterY - lockHeight * 0.05f,
            centerX + lockWidth / 2f,
            lockCenterY + lockHeight,
        )
        canvas.drawRoundRect(glyphBounds, 3f * density, 3f * density, glyphPaint)
        glyphBounds.set(
            centerX - lockWidth * 0.31f,
            lockCenterY - lockHeight * 0.62f,
            centerX + lockWidth * 0.31f,
            lockCenterY + lockHeight * 0.24f,
        )
        canvas.drawArc(glyphBounds, 180f, -180f, false, glyphPaint)

        numberPaint.textSize = 12f * scaledDensity
        val baselineY = centerY + radius * 0.66f -
            (numberPaint.ascent() + numberPaint.descent()) / 2f
        canvas.drawText(levelText, centerX, baselineY, numberPaint)
        numberPaint.textSize = 20f * scaledDensity
    }

    private fun drawEarnedStars(canvas: Canvas, centerX: Float, centerY: Float, count: Int) {
        val starRadius = 3.7f * density
        val gap = 1.8f * density
        val totalWidth = count * starRadius * 2f + (count - 1) * gap
        var starCenterX = centerX - totalWidth / 2f + starRadius
        repeat(count) {
            buildStarPath(shapePath, starCenterX, centerY, starRadius)
            canvas.drawPath(shapePath, starPaint)
            starCenterX += starRadius * 2f + gap
        }
    }

    private fun drawPlayMarker(canvas: Canvas, centerX: Float, centerY: Float) {
        val markerRadius = 7.5f * density
        canvas.drawCircle(centerX, centerY, markerRadius, outerPaint)
        shapePath.reset()
        shapePath.moveTo(centerX - markerRadius * 0.25f, centerY - markerRadius * 0.48f)
        shapePath.lineTo(centerX + markerRadius * 0.48f, centerY)
        shapePath.lineTo(centerX - markerRadius * 0.25f, centerY + markerRadius * 0.48f)
        shapePath.close()
        starPaint.color = primaryTextColor
        canvas.drawPath(shapePath, starPaint)
        starPaint.color = goldColor
    }

    private fun buildStarPath(path: Path, centerX: Float, centerY: Float, radius: Float) {
        path.reset()
        var point = 0
        while (point < STAR_POINT_COUNT) {
            val angle = -Math.PI / 2.0 + point * Math.PI / STAR_POINT_COUNT
            val pointRadius = if (point % 2 == 0) radius else radius * STAR_INNER_RATIO
            val x = centerX + cos(angle).toFloat() * pointRadius
            val y = centerY + sin(angle).toFloat() * pointRadius
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            point++
        }
        path.close()
    }

    private fun fillColor(status: LevelNodeStatus): Int = when (status) {
        LevelNodeStatus.COMPLETED -> completedColor
        LevelNodeStatus.CURRENT -> currentColor
        LevelNodeStatus.AVAILABLE -> availableColor
        LevelNodeStatus.LOCKED -> lockedColor
    }

    private fun buildContentDescription(state: LevelNodeUiState): String {
        val base = context.getString(
            when (state.status) {
                LevelNodeStatus.COMPLETED -> R.string.feature_game_level_select_node_completed
                LevelNodeStatus.CURRENT -> R.string.feature_game_level_select_node_current
                LevelNodeStatus.AVAILABLE -> R.string.feature_game_level_select_node_available
                LevelNodeStatus.LOCKED -> R.string.feature_game_level_select_node_locked
            },
            *when (state.status) {
                LevelNodeStatus.COMPLETED -> arrayOf(state.levelNumber, state.stars)
                else -> arrayOf(state.levelNumber)
            },
        )
        return buildString {
            append(base)
            if (state.isBoss) {
                append(context.getString(R.string.feature_game_level_select_node_boss_suffix))
            }
            if (state.isHardPreview) {
                append(context.getString(R.string.feature_game_level_select_node_hard_suffix))
            }
        }
    }

    private fun color(resourceId: Int): Int = ContextCompat.getColor(context, resourceId)

    private companion object {
        const val STAR_POINT_COUNT = 10
        const val STAR_INNER_RATIO = 0.46f
    }
}
