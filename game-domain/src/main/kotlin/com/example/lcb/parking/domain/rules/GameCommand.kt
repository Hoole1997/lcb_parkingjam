package com.example.lcb.parking.domain.rules

import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.VehicleId

/** IDs are supplied by the caller; reducers never read clocks or random generators. */
sealed interface GameCommand {
    data class TapVehicle(val vehicleId: VehicleId, val effectId: EffectId) : GameCommand
    data class SetPaused(val paused: Boolean) : GameCommand
    data class ConfirmCollisionPresentation(
        val vehicleId: VehicleId,
        val effectId: EffectId,
    ) : GameCommand

    /** Confirms that the pending completion/failure panel has been presented. */
    data class ConfirmTerminalPresentation(val effectId: EffectId) : GameCommand

    data class UseShield(val effectId: EffectId) : GameCommand
    data class TowVehicle(val vehicleId: VehicleId, val effectId: EffectId) : GameCommand
    data class ContinueAfterReward(
        val newAttemptId: AttemptId,
        val effectId: EffectId,
    ) : GameCommand

    data class Restart(
        val newAttemptId: AttemptId,
        val newAttemptChainId: AttemptChainId,
        val effectId: EffectId,
    ) : GameCommand

    data class Quit(val effectId: EffectId) : GameCommand
}
