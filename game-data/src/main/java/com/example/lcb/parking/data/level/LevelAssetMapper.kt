package com.example.lcb.parking.data.level

import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.DifficultyTier
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.ExitDefinition
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.LevelProgression
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingOverflowPolicy
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.RewardProfile
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType

/** 把易变的内容格式显式投影到稳定 Domain 模型。 */
internal object LevelAssetMapper {

    const val SUPPORTED_ASSET_SCHEMA_VERSION = 2

    fun map(dto: LevelAssetDto): LevelMappingResult {
        if (dto.schemaVersion != SUPPORTED_ASSET_SCHEMA_VERSION) {
            return LevelMappingResult.UnsupportedAssetSchema(dto.schemaVersion)
        }

        return try {
            validateContentMetadata(dto)
            val board = BoardDefinition(width = dto.board.width, height = dto.board.height)
            val vehicles = dto.vehicles.map(::mapVehicle)
            val vehicleIds = vehicles.mapTo(linkedSetOf(), VehicleDefinition::id)
            val objective = mapObjective(dto.objective, vehicleIds)
            val exits = dto.exits.flatMap { mapExitSegment(it, board) }
            val parkingRules = mapParkingRules(dto.parkingRules)

            LevelMappingResult.Mapped(
                LevelDefinition(
                    id = LevelId(dto.levelId),
                    levelVersion = dto.levelVersion,
                    ruleVersion = dto.ruleVersion,
                    chapterId = dto.chapterId,
                    displayNumber = dto.displayNumber,
                    mode = mapMode(dto.mode),
                    difficultyTier = mapDifficulty(dto.difficultyTier),
                    board = board,
                    vehicles = vehicles,
                    exits = exits,
                    parkingRules = parkingRules,
                    objective = objective,
                    initialSafety = mapSafety(dto.initialSafety),
                    rewardProfile = mapRewardProfile(dto.rewardProfileId),
                    canonicalSolution = dto.canonicalSolution.map { vehicleId ->
                        CanonicalAction.ExitVehicle(VehicleId(vehicleId))
                    },
                    progression = LevelProgression(
                        prerequisiteLevelIds = dto.progression.prerequisiteLevelIds
                            .mapTo(linkedSetOf(), ::LevelId),
                        skippable = dto.progression.skippable,
                    ),
                    contentTags = dto.contentTags.toSet(),
                ),
            )
        } catch (error: IllegalArgumentException) {
            LevelMappingResult.Invalid(error.message ?: "Invalid level value")
        } catch (error: IllegalStateException) {
            LevelMappingResult.Invalid(error.message ?: "Invalid level state")
        } catch (error: NullPointerException) {
            // Gson can assign null to Kotlin non-null fields when required JSON fields are absent.
            LevelMappingResult.Invalid("Required level field is missing")
        }
    }

    /** 校验内容元数据；进度关系和视觉标签会在验证后投影为稳定 Domain 值。 */
    private fun validateContentMetadata(dto: LevelAssetDto) {
        require(LEVEL_ID_PATTERN.matches(dto.levelId)) { "Invalid built-in level ID" }
        require(dto.progression.prerequisiteLevelIds.none(String::isBlank)) {
            "Progression contains a blank prerequisite"
        }
        require(dto.progression.prerequisiteLevelIds.distinct().size ==
            dto.progression.prerequisiteLevelIds.size) {
            "Progression contains duplicate prerequisites"
        }
        require(dto.levelId !in dto.progression.prerequisiteLevelIds) {
            "Level cannot depend on itself"
        }
        require(dto.contentTags.isNotEmpty() && dto.contentTags.none(String::isBlank)) {
            "content_tags must contain non-blank values"
        }
        require(dto.contentTags.distinct().size == dto.contentTags.size) {
            "content_tags must be unique"
        }
        require(dto.tutorialDirectives.all { directive ->
            directive.tutorialId.isNotBlank() && directive.trigger.isNotBlank()
        }) { "Tutorial directive ID and trigger must not be blank" }

        val vehicleIds = dto.vehicles.map(VehicleDto::vehicleId).toSet()
        require(dto.tutorialDirectives.mapNotNull(TutorialDirectiveDto::targetVehicleId)
            .all(vehicleIds::contains)) {
            "Tutorial directive references an unknown vehicle"
        }
        require(dto.difficultyMetrics.vehicleCount == dto.vehicles.size) {
            "difficulty_metrics.vehicle_count does not match vehicles"
        }
        require(dto.difficultyMetrics.requiredCount == dto.objective.requiredVehicleIds.size) {
            "difficulty_metrics.required_count does not match objective"
        }
        require(dto.difficultyMetrics.solutionSteps == dto.canonicalSolution.size) {
            "difficulty_metrics.solution_steps does not match canonical solution"
        }
        require(dto.difficultyMetrics.safeFirstMoves in 0..dto.vehicles.size) {
            "difficulty_metrics.safe_first_moves is out of range"
        }
        require(dto.difficultyMetrics.boardDensity in 0.0..1.0) {
            "difficulty_metrics.board_density is out of range"
        }

        validateParkingMetadata(dto)
    }

    /**
     * Orders are authored from consecutive canonical groups. Keeping this invariant in the data
     * boundary guarantees that the published canonical replay always targets the active color.
     */
    private fun validateParkingMetadata(dto: LevelAssetDto) {
        require(dto.parkingRules.capacity > 0) { "Parking capacity must be positive" }
        require(dto.parkingRules.orders.isNotEmpty()) { "Parking orders must not be empty" }
        require(dto.parkingRules.orders.map(ColorOrderDto::orderId).none(String::isBlank)) {
            "Parking order ID must not be blank"
        }
        require(dto.parkingRules.orders.map(ColorOrderDto::orderId).distinct().size ==
            dto.parkingRules.orders.size) {
            "Parking order IDs must be unique"
        }
        require(dto.parkingRules.orders.all { it.requiredCount > 0 }) {
            "Parking order required_count must be positive"
        }

        val vehicleColorById = dto.vehicles.associate { vehicle -> vehicle.vehicleId to vehicle.colorId }
        var canonicalOffset = 0
        dto.parkingRules.orders.forEach { order ->
            val endOffset = canonicalOffset + order.requiredCount
            require(endOffset <= dto.canonicalSolution.size) {
                "Parking order counts exceed canonical_solution"
            }
            val canonicalGroup = dto.canonicalSolution.subList(canonicalOffset, endOffset)
            require(canonicalGroup.all { vehicleId -> vehicleColorById[vehicleId] == order.colorId }) {
                "Canonical parking group does not match order color ${order.colorId}"
            }
            canonicalOffset = endOffset
        }
        require(canonicalOffset == dto.canonicalSolution.size) {
            "Parking order counts must cover canonical_solution"
        }
    }

    private fun mapVehicle(dto: VehicleDto): VehicleDefinition {
        return VehicleDefinition(
            id = VehicleId(dto.vehicleId),
            type = mapVehicleType(dto.type),
            color = mapVehicleColor(dto.colorId),
            anchor = Cell(dto.anchor.x, dto.anchor.y),
            direction = mapDirection(dto.direction),
            length = dto.length,
            required = dto.required,
            towProhibited = dto.towProhibited,
        )
    }

    private fun mapParkingRules(dto: ParkingRulesDto): ParkingRules = ParkingRules(
        capacity = dto.capacity,
        orders = dto.orders.map { order ->
            ColorOrderDefinition(
                id = OrderId(order.orderId),
                color = mapVehicleColor(order.colorId),
                requiredCount = order.requiredCount,
            )
        },
        overflowPolicy = when (dto.overflowPolicy) {
            "reject_exit" -> ParkingOverflowPolicy.REJECT_EXIT
            "fail_attempt" -> ParkingOverflowPolicy.FAIL_ATTEMPT
            else -> throw IllegalArgumentException(
                "Unknown parking overflow policy: ${dto.overflowPolicy}",
            )
        },
    )

    private fun mapObjective(
        dto: ObjectiveDto,
        allVehicleIds: Set<VehicleId>,
    ): LevelObjective {
        val requiredIds = dto.requiredVehicleIds.mapTo(linkedSetOf(), ::VehicleId)
        require(requiredIds.all(allVehicleIds::contains)) {
            "Objective references an unknown vehicle"
        }
        return when (dto.type) {
            "clear_all" -> LevelObjective.ClearAll(requiredIds)
            "rescue_target" -> {
                require(requiredIds.size == 1) { "rescue_target requires exactly one vehicle" }
                LevelObjective.RescueTarget(requiredIds.single())
            }
            "boss_clear" -> LevelObjective.BossClear(requiredIds)
            else -> throw IllegalArgumentException("Unknown objective type: ${dto.type}")
        }
    }

    /**
     * 内容层可用一个边界段表达整条开放边，Domain 则保留每个可离场边界格，
     * 便于整数射线判定。
     */
    private fun mapExitSegment(dto: ExitDto, board: BoardDefinition): List<ExitDefinition> {
        require(dto.offset >= 0) { "Exit offset must be non-negative" }
        require(dto.length > 0) { "Exit length must be positive" }
        val direction = mapDirection(dto.direction)
        val boundarySize = when (direction) {
            Direction.NORTH, Direction.SOUTH -> board.width
            Direction.EAST, Direction.WEST -> board.height
        }
        require(dto.offset + dto.length <= boundarySize) {
            "Exit segment ${dto.exitId} exceeds board boundary"
        }
        val allowedTypes = dto.allowedVehicleTypes.mapTo(linkedSetOf(), ::mapVehicleType)
        require(allowedTypes.isNotEmpty()) { "Exit ${dto.exitId} has no allowed vehicle types" }

        return List(dto.length) { segmentIndex ->
            val boundaryOffset = dto.offset + segmentIndex
            val boundaryCell = when (direction) {
                Direction.NORTH -> Cell(boundaryOffset, 0)
                Direction.SOUTH -> Cell(boundaryOffset, board.height - 1)
                Direction.EAST -> Cell(board.width - 1, boundaryOffset)
                Direction.WEST -> Cell(0, boundaryOffset)
            }
            val domainExitId = if (dto.length == 1) {
                dto.exitId
            } else {
                "${dto.exitId}_$boundaryOffset"
            }
            ExitDefinition(
                id = ExitId(domainExitId),
                boundaryCell = boundaryCell,
                direction = direction,
                allowedVehicleTypes = allowedTypes,
            )
        }
    }

    private fun mapSafety(dto: InitialSafetyDto): InitialSafety = when (dto.mode) {
        "unlimited" -> InitialSafety.TutorialUnlimited
        "limited" -> {
            val points = requireNotNull(dto.value) { "Limited safety requires value" }
            require(points > 0) { "Safety points must be positive" }
            InitialSafety.Limited(points)
        }
        else -> throw IllegalArgumentException("Unknown safety mode: ${dto.mode}")
    }

    private fun mapRewardProfile(profileId: String): RewardProfile = when (profileId) {
        "main_default", "main_tutorial_hidden", "main_tutorial_bonus" -> RewardProfile.Normal
        "boss_60" -> RewardProfile.Boss(baseCoins = 60)
        "boss_120" -> RewardProfile.Boss(baseCoins = 120)
        else -> throw IllegalArgumentException("Unknown reward profile: $profileId")
    }

    private fun mapDirection(value: String): Direction = when (value) {
        "north" -> Direction.NORTH
        "east" -> Direction.EAST
        "south" -> Direction.SOUTH
        "west" -> Direction.WEST
        else -> throw IllegalArgumentException("Unknown direction: $value")
    }

    private fun mapVehicleType(value: String): VehicleType = when (value) {
        "car" -> VehicleType.CAR
        "truck" -> VehicleType.TRUCK
        "rescue" -> VehicleType.RESCUE
        "key_car" -> VehicleType.KEY_CAR
        "special" -> VehicleType.SPECIAL
        else -> throw IllegalArgumentException("Unknown vehicle type: $value")
    }

    private fun mapVehicleColor(value: String): VehicleColor = when (value) {
        "coral" -> VehicleColor.CORAL
        "blue" -> VehicleColor.BLUE
        "yellow" -> VehicleColor.YELLOW
        "purple" -> VehicleColor.PURPLE
        "mint" -> VehicleColor.MINT
        "red" -> VehicleColor.RED
        else -> throw IllegalArgumentException("Unknown vehicle color: $value")
    }

    private fun mapMode(value: String): LevelMode = when (value) {
        "tutorial" -> LevelMode.TUTORIAL
        "normal" -> LevelMode.NORMAL
        "boss" -> LevelMode.BOSS
        "rescue" -> LevelMode.RESCUE
        "hard_preview" -> LevelMode.HARD_PREVIEW
        "hard" -> LevelMode.HARD
        "daily" -> LevelMode.DAILY
        else -> throw IllegalArgumentException("Unknown level mode: $value")
    }

    private fun mapDifficulty(value: String): DifficultyTier = when (value) {
        "d1" -> DifficultyTier.D1
        "d2" -> DifficultyTier.D2
        "d3" -> DifficultyTier.D3
        "d4" -> DifficultyTier.D4
        "d5" -> DifficultyTier.D5
        else -> throw IllegalArgumentException("Unknown difficulty tier: $value")
    }

    private val LEVEL_ID_PATTERN = Regex("main_[0-9]{3}")
}

internal sealed interface LevelMappingResult {
    data class Mapped(val level: LevelDefinition) : LevelMappingResult
    data class UnsupportedAssetSchema(val schemaVersion: Int) : LevelMappingResult
    data class Invalid(val reason: String) : LevelMappingResult
}
