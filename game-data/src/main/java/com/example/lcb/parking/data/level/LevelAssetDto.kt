package com.example.lcb.parking.data.level

import com.google.gson.annotations.SerializedName

/**
 * 磁盘 JSON 的专用 DTO。该类型不会暴露给 Domain，避免内容格式演进污染规则模型。
 */
internal data class LevelAssetDto(
    @SerializedName("schema_version") val schemaVersion: Int,
    @SerializedName("level_id") val levelId: String,
    @SerializedName("level_version") val levelVersion: Int,
    @SerializedName("rule_version") val ruleVersion: Int,
    @SerializedName("chapter_id") val chapterId: String,
    @SerializedName("display_number") val displayNumber: Int,
    val mode: String,
    @SerializedName("difficulty_tier") val difficultyTier: String,
    val progression: ProgressionDto,
    val board: BoardDto,
    @SerializedName("parking_rules") val parkingRules: ParkingRulesDto,
    val objective: ObjectiveDto,
    val vehicles: List<VehicleDto>,
    val exits: List<ExitDto>,
    @SerializedName("initial_safety") val initialSafety: InitialSafetyDto,
    @SerializedName("reward_profile_id") val rewardProfileId: String,
    @SerializedName("tutorial_directives") val tutorialDirectives: List<TutorialDirectiveDto>,
    @SerializedName("canonical_solution") val canonicalSolution: List<String>,
    @SerializedName("difficulty_metrics") val difficultyMetrics: DifficultyMetricsDto,
    @SerializedName("content_tags") val contentTags: List<String>,
)

internal data class ProgressionDto(
    @SerializedName("prerequisite_level_ids") val prerequisiteLevelIds: List<String>,
    val skippable: Boolean,
)

internal data class BoardDto(
    val width: Int,
    val height: Int,
)

internal data class ObjectiveDto(
    val type: String,
    @SerializedName("required_vehicle_ids") val requiredVehicleIds: List<String>,
)

internal data class VehicleDto(
    @SerializedName("vehicle_id") val vehicleId: String,
    val type: String,
    @SerializedName("color_id") val colorId: String,
    val anchor: CellDto,
    val direction: String,
    val length: Int,
    val required: Boolean,
    @SerializedName("tow_prohibited") val towProhibited: Boolean,
)

internal data class ParkingRulesDto(
    val capacity: Int,
    @SerializedName("overflow_policy") val overflowPolicy: String,
    val orders: List<ColorOrderDto>,
)

internal data class ColorOrderDto(
    @SerializedName("order_id") val orderId: String,
    @SerializedName("color_id") val colorId: String,
    @SerializedName("required_count") val requiredCount: Int,
)

internal data class CellDto(
    val x: Int,
    val y: Int,
)

internal data class ExitDto(
    @SerializedName("exit_id") val exitId: String,
    val direction: String,
    val offset: Int,
    val length: Int,
    @SerializedName("allowed_vehicle_types") val allowedVehicleTypes: List<String>,
)

internal data class InitialSafetyDto(
    val mode: String,
    val value: Int? = null,
)

internal data class TutorialDirectiveDto(
    @SerializedName("tutorial_id") val tutorialId: String,
    val trigger: String,
    @SerializedName("target_vehicle_id") val targetVehicleId: String? = null,
    val forced: Boolean,
)

internal data class DifficultyMetricsDto(
    @SerializedName("vehicle_count") val vehicleCount: Int,
    @SerializedName("required_count") val requiredCount: Int,
    @SerializedName("solution_steps") val solutionSteps: Int,
    @SerializedName("dependency_depth") val dependencyDepth: Int,
    @SerializedName("safe_first_moves") val safeFirstMoves: Int,
    @SerializedName("false_affordances") val falseAffordances: Int,
    @SerializedName("mechanic_count") val mechanicCount: Int,
    @SerializedName("board_density") val boardDensity: Double,
    @SerializedName("branching_mean") val branchingMean: Double,
)
