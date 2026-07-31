package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.VehicleId

/**
 * game-domain 业务模型与 Android 绘制模型之间的显式边界。
 * 该投影不改变 Snapshot，也不依据动画回调计算完成、星级或奖励。
 */
data class DomainGameProjection(
    val level: LevelDefinition,
    val snapshot: GameSnapshot,
    /** 由领域/进度层计算；展示层不得根据碰撞数重新定义星级。 */
    val resultStars: Int = 0,
    val earnedCoins: Int = 0,
    /** L5 揭示的是可靠账本余额，不以动画累计值作为货币真相源。 */
    val coinBalance: Long = 0L,
    val tutorialMessage: String? = null,
    val hasNextLevel: Boolean = true,
    /** 仅进程内存在；恢复存档时为空，已提交车辆直接投影为 EXITED。 */
    val pendingExitVehicleIds: Set<VehicleId> = emptySet(),
)

class DomainGameUiProjector : MainGameUiMapper<DomainGameProjection> {

    override fun map(aggregate: DomainGameProjection): MainGameUiState {
        val snapshot = aggregate.snapshot
        val result = if (snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            GameResultUiState(
                stars = aggregate.resultStars,
                earnedCoins = aggregate.earnedCoins,
                coinBalance = aggregate.coinBalance,
                showCoins = aggregate.level.displayNumber >= COIN_REVEAL_LEVEL,
                hasNextLevel = aggregate.hasNextLevel,
                presentationToken = snapshot.attempt.attemptId.value,
            )
        } else {
            null
        }
        val failure = if (snapshot.attempt.businessState == AttemptBusinessState.FAIL) {
            GameFailureUiState(presentationToken = snapshot.attempt.attemptId.value)
        } else {
            null
        }
        return MainGameUiState(
            phase = phaseFor(snapshot, aggregate.pendingExitVehicleIds),
            levelNumber = aggregate.level.displayNumber,
            board = boardFor(aggregate.level, snapshot, aggregate.pendingExitVehicleIds),
            parkingLot = parkingLotFor(aggregate.level, snapshot),
            tutorialMessage = aggregate.tutorialMessage,
            result = result,
            failure = failure,
        )
    }

    private fun phaseFor(snapshot: GameSnapshot, pendingExitVehicleIds: Set<VehicleId>): GameScreenPhase {
        return when {
            snapshot.attempt.businessState == AttemptBusinessState.COMPLETE &&
                snapshot.board.vehicles.keys.any(pendingExitVehicleIds::contains) -> GameScreenPhase.COMPLETING
            snapshot.attempt.businessState == AttemptBusinessState.COMPLETE -> GameScreenPhase.RESULT
            snapshot.attempt.businessState == AttemptBusinessState.FAIL &&
                snapshot.transientVehicleLocks.isNotEmpty() -> GameScreenPhase.FAILING
            snapshot.attempt.businessState == AttemptBusinessState.FAIL -> GameScreenPhase.FAILURE
            snapshot.attempt.businessState == AttemptBusinessState.QUIT -> GameScreenPhase.QUIT
            snapshot.attempt.businessState == AttemptBusinessState.CONTENT_ERROR -> GameScreenPhase.ERROR
            snapshot.paused -> GameScreenPhase.PAUSED
            else -> GameScreenPhase.PLAYING
        }
    }

    private fun boardFor(
        level: LevelDefinition,
        snapshot: GameSnapshot,
        pendingExitVehicleIds: Set<VehicleId>,
    ): BoardRenderModel {
        val vehicles = ArrayList<VehicleRenderModel>(level.vehicles.size)
        var vehicleIndex = 0
        while (vehicleIndex < level.vehicles.size) {
            val definition = level.vehicles[vehicleIndex]
            val state = snapshot.board.vehicles[definition.id] ?: VehicleRuleState.Parked
            vehicles += vehicleFor(
                definition = definition,
                state = state,
                exitPending = definition.id in pendingExitVehicleIds,
            )
            vehicleIndex++
        }

        var wallCellCount = 0
        var wallIndex = 0
        while (wallIndex < level.walls.size) {
            wallCellCount += level.walls[wallIndex].cells.size
            wallIndex++
        }
        var gateIndex = 0
        while (gateIndex < level.gates.size) {
            val gate = level.gates[gateIndex]
            if (gate.id !in snapshot.board.openGateIds) wallCellCount += gate.cells.size
            gateIndex++
        }
        val walls = ArrayList<WallRenderModel>(wallCellCount)
        wallIndex = 0
        while (wallIndex < level.walls.size) {
            appendCells(level.walls[wallIndex].cells, walls)
            wallIndex++
        }
        gateIndex = 0
        while (gateIndex < level.gates.size) {
            val gate = level.gates[gateIndex]
            if (gate.id !in snapshot.board.openGateIds) appendCells(gate.cells, walls)
            gateIndex++
        }

        return BoardRenderModel(
            rows = level.board.height,
            columns = level.board.width,
            vehicles = vehicles,
            walls = walls,
        )
    }

    private fun vehicleFor(
        definition: VehicleDefinition,
        state: VehicleRuleState,
        exitPending: Boolean,
    ): VehicleRenderModel {
        return definition.toRenderModel(
            visualState = when (state) {
                VehicleRuleState.Parked -> VehicleVisualState.PARKED
                is VehicleRuleState.Locked -> VehicleVisualState.LOCKED
                is VehicleRuleState.ExitCommitted -> {
                    if (exitPending) VehicleVisualState.MOVING else VehicleVisualState.EXITED
                }
                VehicleRuleState.Towed -> VehicleVisualState.TOWED
            },
        )
    }

    /** 只投影当前订单与完整候车位；后续订单不占用游戏主界面的认知空间。 */
    private fun parkingLotFor(level: LevelDefinition, snapshot: GameSnapshot): ParkingLotUiState {
        val rules = level.parkingRules
        var orderIndex = 0
        var currentOrder: ParkingColorOrderUiState? = null
        while (orderIndex < rules.orders.size) {
            val definition = rules.orders[orderIndex]
            val completed = snapshot.parkingLot.fulfilledVehicleIdsByOrder[definition.id]
                .orEmpty()
                .size
                .coerceAtMost(definition.requiredCount)
            if (currentOrder == null && completed < definition.requiredCount) {
                currentOrder = ParkingColorOrderUiState(
                    id = definition.id.value,
                    color = definition.color.toArtVariant(),
                    completedCount = completed,
                    requiredCount = definition.requiredCount,
                )
            }
            orderIndex++
        }

        val slots = ArrayList<ParkingSlotUiState>(rules.capacity)
        var slotIndex = 0
        while (slotIndex < rules.capacity) {
            val waiting = snapshot.parkingLot.slots.getOrNull(slotIndex)
            val vehicle = waiting?.let { level.vehicleById[it.vehicleId] }
            slots += ParkingSlotUiState(
                index = slotIndex,
                vehicleId = vehicle?.id?.value,
                color = vehicle?.color?.toArtVariant(),
                lengthCells = vehicle?.length,
                arrivalSequence = waiting?.arrivalSequence,
            )
            slotIndex++
        }
        return ParkingLotUiState(
            capacity = rules.capacity,
            slots = slots,
            currentOrder = currentOrder,
        )
    }

    private fun appendCells(cells: Set<Cell>, destination: MutableList<WallRenderModel>) {
        val iterator = cells.iterator()
        while (iterator.hasNext()) {
            val cell = iterator.next()
            destination += WallRenderModel(row = cell.y, column = cell.x)
        }
    }

    private companion object {
        const val COIN_REVEAL_LEVEL = 5
    }
}
