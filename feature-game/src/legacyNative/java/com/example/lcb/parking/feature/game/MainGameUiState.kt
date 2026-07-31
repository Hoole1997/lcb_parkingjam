package com.example.lcb.parking.feature.game

import androidx.annotation.ColorInt

/** Android 展示层的稳定状态；不包含规则计算，也不持有 View 或 Context。 */
data class MainGameUiState(
    val phase: GameScreenPhase = GameScreenPhase.LOADING,
    val levelNumber: Int = 1,
    val board: BoardRenderModel = BoardRenderModel.EMPTY,
    /** 停车场、订单与容量均由领域快照投影，Compose 只负责展示。 */
    val parkingLot: ParkingLotUiState = ParkingLotUiState.EMPTY,
    /** 首页和关卡地图只读取该轻量投影，不从当前关卡状态反推长期进度。 */
    val progress: GameProgressUiState = GameProgressUiState(),
    val tutorialMessage: String? = null,
    val result: GameResultUiState? = null,
    val failure: GameFailureUiState? = null,
    val errorMessage: String? = null,
) {
    val acceptsBoardInput: Boolean
        get() = phase == GameScreenPhase.PLAYING
}

enum class GameScreenPhase {
    LOADING,
    PLAYING,
    COMPLETING,
    /** 停车场溢出等失败业务已经提交，等待对应表现完成。 */
    FAILING,
    /** 失败表现已经就绪，棋盘保持禁用并展示失败操作。 */
    FAILURE,
    PAUSED,
    RESULT,
    QUIT,
    ERROR,
}

/**
 * 游戏页稳定的停车场投影。
 *
 * [slots] 永远按车位下标排列并完整包含 [capacity] 个元素；页面不得自行配对颜色、推进订单
 * 或判定溢出。这样旋转、重组和进程恢复都只重放权威快照，不会产生第二套规则。
 */
data class ParkingLotUiState(
    val capacity: Int,
    val slots: List<ParkingSlotUiState>,
    val currentOrder: ParkingColorOrderUiState?,
) {
    init {
        require(capacity >= 0) { "Parking capacity cannot be negative" }
        require(slots.size == capacity) { "Parking projection must expose every slot" }
    }

    val occupiedCount: Int
        get() = slots.count(ParkingSlotUiState::isOccupied)

    companion object {
        val EMPTY = ParkingLotUiState(
            capacity = 0,
            slots = emptyList(),
            currentOrder = null,
        )
    }
}

data class ParkingSlotUiState(
    val index: Int,
    val vehicleId: String? = null,
    val color: VehicleArtVariant? = null,
    /** 等候车辆的领域占格长度；仅用于选择等比例车身素材，不参与停车规则计算。 */
    val lengthCells: Int? = null,
    val arrivalSequence: Long? = null,
) {
    init {
        require(index >= 0) { "Parking slot index cannot be negative" }
        require((vehicleId == null) == (color == null)) {
            "An occupied parking slot must include both vehicle id and color"
        }
        require((vehicleId == null) == (lengthCells == null)) {
            "An occupied parking slot must include its vehicle length"
        }
        require(lengthCells == null || lengthCells > 0) {
            "Parking vehicle length must be positive"
        }
        require((vehicleId == null) == (arrivalSequence == null)) {
            "An occupied parking slot must include its stable arrival sequence"
        }
        require(arrivalSequence == null || arrivalSequence > 0L) {
            "Parking arrival sequence must be positive"
        }
    }

    val isOccupied: Boolean
        get() = vehicleId != null
}

data class ParkingColorOrderUiState(
    val id: String,
    val color: VehicleArtVariant,
    val completedCount: Int,
    val requiredCount: Int,
) {
    init {
        require(id.isNotBlank()) { "Parking order id cannot be blank" }
        require(requiredCount > 0) { "Parking order target must be positive" }
        require(completedCount in 0..requiredCount) { "Parking order progress is out of range" }
    }

    val isComplete: Boolean
        get() = completedCount == requiredCount
}

data class GameResultUiState(
    val stars: Int,
    val earnedCoins: Int,
    val coinBalance: Long,
    /** PRD 锁定 L1-L4 隐藏金币，L5 结算才揭示金币系统。 */
    val showCoins: Boolean,
    val hasNextLevel: Boolean = true,
    /** 用于页面展示确认去重，不用于奖励或业务主键。 */
    val presentationToken: String = "",
)

/** 失败终态的稳定展示标识，用于弹层展示确认和进程恢复去重。 */
data class GameFailureUiState(
    val presentationToken: String,
) {
    init {
        require(presentationToken.isNotBlank()) { "Failure presentation token cannot be blank" }
    }
}

data class BoardRenderModel(
    val rows: Int,
    val columns: Int,
    val vehicles: List<VehicleRenderModel>,
    val walls: List<WallRenderModel> = emptyList(),
    val highlightedVehicleId: String? = null,
) {
    init {
        require(rows >= 0 && columns >= 0) { "Board dimensions cannot be negative" }
    }

    companion object {
        val EMPTY = BoardRenderModel(rows = 0, columns = 0, vehicles = emptyList())
    }
}

data class VehicleRenderModel(
    val id: String,
    val row: Int,
    val column: Int,
    val widthCells: Int,
    val heightCells: Int,
    val direction: VehicleDirection,
    val visualState: VehicleVisualState = VehicleVisualState.PARKED,
    /** 稳定的美术语义键；资源 ID 与 Bitmap 由 Android 渲染层解析。 */
    val artVariant: VehicleArtVariant = VehicleArtVariant.BLUE,
    @param:ColorInt val color: Int = DEFAULT_VEHICLE_COLOR,
) {
    init {
        require(id.isNotBlank()) { "Vehicle id cannot be blank" }
        require(widthCells > 0 && heightCells > 0) { "Vehicle dimensions must be positive" }
    }

    val isTappable: Boolean
        get() = visualState == VehicleVisualState.PARKED ||
            visualState == VehicleVisualState.LOCKED

    companion object {
        const val DEFAULT_VEHICLE_COLOR: Int = 0xFF2E86DE.toInt()
    }
}

/**
 * 车辆皮肤的跨渲染语义。枚举不依赖 Android 资源，便于投影层测试与未来替换素材包。
 */
enum class VehicleArtVariant {
    CORAL,
    BLUE,
    YELLOW,
    PURPLE,
    MINT,
    RED,
}

enum class VehicleDirection {
    UP,
    RIGHT,
    DOWN,
    LEFT,
}

enum class VehicleVisualState {
    PARKED,
    LOCKED,
    MOVING,
    TOWED,
    EXITED,
}

data class WallRenderModel(
    val row: Int,
    val column: Int,
    val widthCells: Int = 1,
    val heightCells: Int = 1,
)

/** 领域 PresentationIntent 映射后的轻量 UI 效果。 */
sealed interface GamePresentationEffect {
    val effectId: String

    data class MoveVehicle(
        override val effectId: String,
        val vehicleId: String,
        val deltaRows: Float,
        val deltaColumns: Float,
        val durationMillis: Long = 450L,
        /** 动画结束后隐藏源车位，直到权威 UI 快照不再将该车投影为 MOVING。 */
        val hideSourceUntilStateUpdate: Boolean = false,
        /** 已提交离场的车辆可从业务快照消失；动画使用此轻量副本绘制，不回写领域。 */
        val renderVehicle: VehicleRenderModel? = null,
        /** 棋盘退场后的空间连续动画；为空时保持原有纯棋盘移动。 */
        val parkingMotion: ParkingMotionSpec? = null,
    ) : GamePresentationEffect

    data class ReboundVehicle(
        override val effectId: String,
        val vehicleId: String,
        val deltaRows: Float,
        val deltaColumns: Float,
        val durationMillis: Long = 220L,
    ) : GamePresentationEffect

    data class HighlightVehicle(
        override val effectId: String,
        val vehicleId: String,
        val durationMillis: Long = 600L,
    ) : GamePresentationEffect
}
