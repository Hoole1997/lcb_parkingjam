package com.example.lcb.parking.data.state

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptChainSnapshot
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.AttemptSnapshot
import com.example.lcb.parking.domain.model.BoardSnapshot
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.GateId
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingLotSnapshot
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.RewardTransactionId
import com.example.lcb.parking.domain.model.SafetyState
import com.example.lcb.parking.domain.model.ToolInventory
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.model.WaitingVehicle
import com.example.lcb.parking.domain.rules.DomainFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GameStateMapperTest {

    @Test
    fun `snapshot round trip retains stable business state`() {
        val snapshot = GameSnapshot(
            levelId = LevelId("main_005"),
            levelVersion = 2,
            ruleVersion = 2,
            board = BoardSnapshot(
                vehicles = linkedMapOf(
                    VehicleId("A") to VehicleRuleState.Parked,
                    VehicleId("B") to VehicleRuleState.Locked(VehicleId("A")),
                    VehicleId("C") to VehicleRuleState.ExitCommitted(ExitId("north_2"), 1L),
                    VehicleId("D") to VehicleRuleState.Towed,
                    VehicleId("E") to VehicleRuleState.ExitCommitted(ExitId("north_3"), 2L),
                ),
                openGateIds = setOf(GateId("gate_1")),
                nextCommitSequence = 3L,
            ),
            parkingLot = ParkingLotSnapshot(
                slots = listOf(null, WaitingVehicle(VehicleId("C"), arrivalSequence = 2L)),
                fulfilledVehicleIdsByOrder = linkedMapOf(
                    OrderId("order_01") to listOf(VehicleId("E")),
                    OrderId("order_02") to emptyList(),
                ),
                nextArrivalSequence = 3L,
            ),
            attempt = AttemptSnapshot(
                attemptId = AttemptId("attempt_2"),
                attemptChainId = AttemptChainId("chain_1"),
                parentAttemptId = AttemptId("attempt_1"),
                businessState = AttemptBusinessState.ACTIVE,
                presentationState = AttemptPresentationState.PLAYING,
            ),
            chain = AttemptChainSnapshot(
                id = AttemptChainId("chain_1"),
                collisionCount = 1,
                tutorialMistakeCount = 2,
                shieldUsed = true,
                towUsed = true,
                continueUsed = true,
            ),
            safety = SafetyState.Limited(initial = 3, remaining = 2),
            paused = true,
            transientVehicleLocks = mapOf(VehicleId("A") to EffectId("collision_animation")),
            revision = 8L,
        )

        val restored = GameStateMapper.toDomain(GameStateMapper.toDto(snapshot))

        assertEquals(snapshot.stableForPersistence(), restored)
    }

    @Test
    fun `player progress round trip is deterministic`() {
        val progress = PlayerProgress(
            coins = 125L,
            bestStarsByLevel = mapOf(LevelId("main_002") to 2, LevelId("main_001") to 3),
            rewardedCoinsByLevel = mapOf(LevelId("main_001") to 25),
            completedLevelIds = setOf(LevelId("main_001"), LevelId("main_002")),
            appliedRewardTransactionIds = setOf(RewardTransactionId("reward_1")),
            l5StarterRewardClaimed = true,
            inventory = ToolInventory(hints = 2, shields = 1, tows = 3),
            revision = 4L,
        )

        val restored = GameStateMapper.toDomain(GameStateMapper.toDto(progress))

        assertEquals(progress, restored)
    }

    @Test
    fun `parking facts round trip without losing routing identity`() {
        val attemptId = AttemptId("attempt_parking")
        val facts = listOf(
            DomainFact.VehicleQueued(
                attemptId = attemptId,
                vehicleId = VehicleId("B"),
                color = VehicleColor.BLUE,
                slotIndex = 1,
                arrivalSequence = 4L,
            ),
            DomainFact.VehicleOrderFulfilled(
                attemptId = attemptId,
                vehicleId = VehicleId("A"),
                orderId = OrderId("order_01"),
                color = VehicleColor.CORAL,
                fromSlotIndex = 0,
            ),
            DomainFact.ColorOrderCompleted(attemptId, OrderId("order_01")),
            DomainFact.ParkingOverflowRecorded(attemptId, VehicleId("C"), capacity = 3),
        )

        facts.forEach { fact ->
            assertEquals(fact, GameStateMapper.toDomain(GameStateMapper.toDto(fact)))
        }
    }

    @Test
    fun `unknown vehicle state fails explicitly`() {
        val valid = GameStateMapper.toDto(sampleSnapshot())
        val invalidBoard = valid.board.copy(
            vehicles = listOf(VehicleRuleStateDto(vehicleId = "A", state = "future_state")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            GameStateMapper.toDomain(valid.copy(board = invalidBoard))
        }
    }

    private fun sampleSnapshot(): GameSnapshot = GameSnapshot(
        levelId = LevelId("main_001"),
        levelVersion = 2,
        ruleVersion = 2,
        board = BoardSnapshot(
            vehicles = mapOf(VehicleId("A") to VehicleRuleState.Parked),
            openGateIds = emptySet(),
        ),
        parkingLot = ParkingLotSnapshot(
            slots = listOf(null, null),
            fulfilledVehicleIdsByOrder = mapOf(OrderId("order_01") to emptyList()),
        ),
        attempt = AttemptSnapshot(AttemptId("attempt_1"), AttemptChainId("chain_1")),
        chain = AttemptChainSnapshot(AttemptChainId("chain_1")),
        safety = SafetyState.TutorialUnlimited,
    )
}
