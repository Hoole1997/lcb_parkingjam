package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.VehicleId

sealed interface RuleDecision {
    data class Applied(
        val snapshot: GameSnapshot,
        val facts: List<DomainFact> = emptyList(),
        val presentationIntents: List<PresentationIntent> = emptyList(),
        /** False only for process-local animation acknowledgement. */
        val requiresPersistence: Boolean = true,
    ) : RuleDecision

    data class Rejected(
        val reason: RuleRejection,
        val presentationIntents: List<PresentationIntent> = emptyList(),
    ) : RuleDecision
}

sealed interface RuleRejection {
    data object LevelMismatch : RuleRejection
    data object AttemptNotActive : RuleRejection
    data object Paused : RuleRejection
    data class VehicleNotFound(val vehicleId: VehicleId) : RuleRejection
    data class VehicleBusy(val vehicleId: VehicleId) : RuleRejection
    data class VehicleAlreadyRemoved(val vehicleId: VehicleId) : RuleRejection
    data class VehicleLocked(val vehicleId: VehicleId) : RuleRejection
    data class ParkingLotFull(val vehicleId: VehicleId) : RuleRejection
    data object SessionNotStable : RuleRejection
    data object ShieldUnavailable : RuleRejection
    data object ShieldAtCapacity : RuleRejection
    data object ShieldAlreadyUsed : RuleRejection
    data object TowAlreadyUsed : RuleRejection
    data object TowUnavailable : RuleRejection
    data class TowProhibited(val vehicleId: VehicleId) : RuleRejection
    data object ContinueUnavailable : RuleRejection
    data object TerminalPresentationUnavailable : RuleRejection
    data object StalePresentationAcknowledgement : RuleRejection
    data object AlreadyInRequestedPauseState : RuleRejection
}
