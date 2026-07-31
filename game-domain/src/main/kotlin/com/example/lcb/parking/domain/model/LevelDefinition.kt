package com.example.lcb.parking.domain.model

/** Integer-only board coordinate. Rendering pixels must never enter the domain model. */
data class Cell(val x: Int, val y: Int) {
    fun step(direction: Direction, distance: Int = 1): Cell =
        Cell(x + direction.dx * distance, y + direction.dy * distance)
}

enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0),
}

data class BoardDefinition(val width: Int, val height: Int) {
    fun contains(cell: Cell): Boolean = cell.x in 0 until width && cell.y in 0 until height
}

enum class VehicleType { CAR, TRUCK, RESCUE, KEY_CAR, SPECIAL }

/**
 * 参与停车订单规则的权威车辆颜色。枚举表达玩法语义，不包含 Android 色值或资源 ID。
 */
enum class VehicleColor { CORAL, BLUE, YELLOW, PURPLE, MINT, RED }

/**
 * [anchor] is the top-left/minimum x,y cell of the occupied bounding box. N/S vehicles occupy
 * anchor.y..anchor.y+length-1; E/W vehicles occupy anchor.x..anchor.x+length-1. The head is at the
 * anchor end for NORTH/WEST and at the far end for SOUTH/EAST.
 */
data class VehicleDefinition(
    val id: VehicleId,
    val type: VehicleType,
    val color: VehicleColor,
    val anchor: Cell,
    val direction: Direction,
    val length: Int,
    val required: Boolean = true,
    val towProhibited: Boolean = false,
    val lockedBy: VehicleId? = null,
) {
    fun occupiedCells(): List<Cell> = when (direction) {
        Direction.NORTH, Direction.SOUTH -> List(length) { anchor.copy(y = anchor.y + it) }
        Direction.EAST, Direction.WEST -> List(length) { anchor.copy(x = anchor.x + it) }
    }

    fun headCell(): Cell = when (direction) {
        Direction.NORTH, Direction.WEST -> anchor
        Direction.SOUTH -> anchor.copy(y = anchor.y + length - 1)
        Direction.EAST -> anchor.copy(x = anchor.x + length - 1)
    }

    /** Carout V3 中车长同时决定可接纳的乘客数。 */
    fun passengerCapacity(): Int = when (length) {
        2 -> 4
        3 -> 6
        else -> error("Unsupported V3 vehicle length: $length")
    }
}

data class ExitDefinition(
    val id: ExitId,
    /** The in-board boundary cell through which the vehicle leaves. */
    val boundaryCell: Cell,
    val direction: Direction,
    val allowedVehicleTypes: Set<VehicleType> = VehicleType.entries.toSet(),
)

/** 压缩保存的有序乘客队列片段；运行时会展开成逐个乘客。 */
data class PassengerQueueGroup(
    val color: VehicleColor,
    val count: Int,
)

data class ParkingRules(
    val capacity: Int,
    val passengerQueue: List<PassengerQueueGroup>,
) {
    /** 展开只发生在开局，关卡规模有严格上限，避免运行中重复分配。 */
    fun expandedPassengerQueue(): List<VehicleColor> = buildList {
        passengerQueue.forEach { group -> repeat(group.count) { add(group.color) } }
    }
}

data class WallDefinition(val id: String, val cells: Set<Cell>)

data class GateDefinition(
    val id: GateId,
    val cells: Set<Cell>,
    val initiallyOpen: Boolean = false,
)

data class PressurePlateDefinition(
    val id: PressurePlateId,
    val cells: Set<Cell>,
    val triggeringVehicleId: VehicleId,
    val opensGateIds: Set<GateId>,
)

sealed interface LevelObjective {
    val requiredVehicleIds: Set<VehicleId>

    data class ClearAll(override val requiredVehicleIds: Set<VehicleId>) : LevelObjective

    data class RescueTarget(val targetVehicleId: VehicleId) : LevelObjective {
        override val requiredVehicleIds: Set<VehicleId> = setOf(targetVehicleId)
    }

    data class BossClear(
        override val requiredVehicleIds: Set<VehicleId>,
        val requiredOpenGateIds: Set<GateId> = emptySet(),
        val rescueTargetVehicleId: VehicleId? = null,
    ) : LevelObjective
}

sealed interface InitialSafety {
    data object TutorialUnlimited : InitialSafety
    data class Limited(val points: Int) : InitialSafety
}

enum class LevelMode { TUTORIAL, NORMAL, BOSS, RESCUE, HARD_PREVIEW, HARD, DAILY }
enum class DifficultyTier { D1, D2, D3, D4, D5 }

sealed interface RewardProfile {
    data object Normal : RewardProfile
    data class Boss(val baseCoins: Int) : RewardProfile
}

sealed interface CanonicalAction {
    data class ExitVehicle(val vehicleId: VehicleId) : CanonicalAction
}

/** 关卡包声明的解锁关系；规则层不再从关卡编号或难度模式猜测主线结构。 */
data class LevelProgression(
    val prerequisiteLevelIds: Set<LevelId> = emptySet(),
    val skippable: Boolean = false,
)

data class LevelDefinition(
    val id: LevelId,
    val levelVersion: Int,
    val ruleVersion: Int,
    val chapterId: String,
    val displayNumber: Int,
    val mode: LevelMode,
    val difficultyTier: DifficultyTier,
    val board: BoardDefinition,
    val vehicles: List<VehicleDefinition>,
    val exits: List<ExitDefinition>,
    val walls: List<WallDefinition> = emptyList(),
    val gates: List<GateDefinition> = emptyList(),
    val pressurePlates: List<PressurePlateDefinition> = emptyList(),
    val parkingRules: ParkingRules,
    val objective: LevelObjective,
    val initialSafety: InitialSafety,
    val rewardProfile: RewardProfile = RewardProfile.Normal,
    val canonicalSolution: List<CanonicalAction>,
    val progression: LevelProgression = LevelProgression(),
    val contentTags: Set<String> = emptySet(),
) {
    val vehicleById: Map<VehicleId, VehicleDefinition> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vehicles.associateBy(VehicleDefinition::id)
    }
    val gateById: Map<GateId, GateDefinition> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        gates.associateBy(GateDefinition::id)
    }

    /** Unlimited safety is now global; only the explicit onboarding range is tutorial scoring. */
    fun isTutorialCollisionMode(): Boolean = displayNumber in 1..5
}
