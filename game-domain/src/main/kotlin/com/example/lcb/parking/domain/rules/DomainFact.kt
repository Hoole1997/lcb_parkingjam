package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GateId
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleId

/** Durable business facts. Data/telemetry adapters may map these to an atomic outbox. */
sealed interface DomainFact {
    data class VehicleExitCommitted(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val commitSequence: Long,
        val openedGateIds: Set<GateId>,
    ) : DomainFact

    data class VehicleQueued(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val color: VehicleColor,
        val slotIndex: Int,
        val arrivalSequence: Long,
    ) : DomainFact

    data class VehicleOrderFulfilled(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val orderId: OrderId,
        val color: VehicleColor,
        val fromSlotIndex: Int? = null,
    ) : DomainFact

    data class ColorOrderCompleted(
        val attemptId: AttemptId,
        val orderId: OrderId,
    ) : DomainFact

    data class ParkingOverflowRecorded(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val capacity: Int,
    ) : DomainFact

    data class CollisionRecorded(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val collisionKind: CollisionKind,
        val chainCollisionCount: Int,
    ) : DomainFact

    data class TutorialMistakeRecorded(
        val attemptId: AttemptId,
        val vehicleId: VehicleId,
        val tutorialMistakeCount: Int,
    ) : DomainFact

    data class ToolUsed(
        val attemptId: AttemptId,
        val tool: ToolKind,
        val vehicleId: VehicleId? = null,
    ) : DomainFact

    data class AttemptEnded(
        val attemptId: AttemptId,
        val attemptChainId: AttemptChainId,
        val result: AttemptResult,
        val stars: Int? = null,
    ) : DomainFact

    data class AttemptStarted(
        val attemptId: AttemptId,
        val attemptChainId: AttemptChainId,
        val parentAttemptId: AttemptId? = null,
        val continued: Boolean,
    ) : DomainFact

    data class PresentationAcknowledged(val effectId: EffectId) : DomainFact
}

enum class CollisionKind { VEHICLE, WALL, CLOSED_GATE, CLOSED_BOUNDARY }
enum class ToolKind { SHIELD, TOW, CONTINUE_SHIELD }
enum class AttemptResult { COMPLETE, FAIL, QUIT, RESTART }
