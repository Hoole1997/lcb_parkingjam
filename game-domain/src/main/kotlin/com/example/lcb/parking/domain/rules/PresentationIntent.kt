package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GateId
import com.example.lcb.parking.domain.model.VehicleId

/** Presentation consumes these only after persistence succeeds. They never feed collision/win rules. */
sealed interface PresentationIntent {
    val effectId: EffectId

    data class ExitCommitted(
        override val effectId: EffectId,
        val vehicleId: VehicleId,
        val sweepPath: List<Cell>,
        val commitSequence: Long,
        val openedGateIds: Set<GateId>,
        val unlockedVehicleIds: Set<VehicleId>,
        val parkingDestination: ParkingDestination,
        val parkingDispatches: List<ParkingDispatch> = emptyList(),
        /** Non-null means this committed path is also the completion animation barrier. */
        val completedStars: Int? = null,
    ) : PresentationIntent

    data class Collision(
        override val effectId: EffectId,
        val vehicleId: VehicleId,
        val kind: CollisionKind,
        val tutorialDemo: Boolean,
        val fatal: Boolean,
    ) : PresentationIntent

    data class ParkingLotFull(
        override val effectId: EffectId,
        val vehicleId: VehicleId,
        val capacity: Int,
        val fatal: Boolean,
    ) : PresentationIntent

    data class LockedVehicleFeedback(
        override val effectId: EffectId,
        val vehicleId: VehicleId,
        val requiredKeyVehicleId: VehicleId,
    ) : PresentationIntent

    data class ShieldApplied(override val effectId: EffectId, val safetyRemaining: Int) : PresentationIntent
    data class VehicleTowed(
        override val effectId: EffectId,
        val vehicleId: VehicleId,
        val completedStars: Int? = null,
    ) : PresentationIntent
    data class FailureReady(override val effectId: EffectId) : PresentationIntent
    data class AttemptContinued(override val effectId: EffectId) : PresentationIntent
    data class AttemptRestarted(override val effectId: EffectId) : PresentationIntent
    data class AttemptQuit(override val effectId: EffectId) : PresentationIntent
}
