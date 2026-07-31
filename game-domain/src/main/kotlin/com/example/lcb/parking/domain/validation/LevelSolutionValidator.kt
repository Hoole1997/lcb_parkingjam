package com.example.lcb.parking.domain.validation

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleRuleState
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.rules.GameCommand
import com.example.lcb.parking.domain.rules.GameReducer
import com.example.lcb.parking.domain.rules.RuleDecision

/**
 * Publication-time solution validation using the production reducer in zero-animation mode.
 *
 * Search explores only successful, collision-free exits. V2 ordered parking intentionally permits bad
 * choices, so validation proves bounded reachability of at least one win instead of requiring every branch
 * to win. Malformed or highly branching content returns [ISSUE_SEARCH_LIMIT].
 */
class LevelSolutionValidator(
    private val maxStates: Int = DEFAULT_MAX_STATES,
) {
    init {
        require(maxStates > 0) { "maxStates must be positive" }
    }

    fun validate(level: LevelDefinition): LevelValidationReport {
        val structural = LevelValidator.validateStructure(level)
        if (!structural.isValid) return structural

        val issues = mutableListOf<LevelValidationIssue>()
        issues += replayCanonical(level)
        issues += validateReachability(level)
        return LevelValidationReport(issues)
    }

    fun validateCanonicalSolution(level: LevelDefinition): LevelValidationReport {
        val structural = LevelValidator.validateStructure(level)
        if (!structural.isValid) return structural
        return LevelValidationReport(replayCanonical(level))
    }

    fun validateStrongSolvability(level: LevelDefinition): LevelValidationReport {
        val structural = LevelValidator.validateStructure(level)
        if (!structural.isValid) return structural
        return LevelValidationReport(validateReachability(level))
    }

    private fun replayCanonical(level: LevelDefinition): List<LevelValidationIssue> {
        var snapshot = initialSnapshot(level, "canonical")
        level.canonicalSolution.forEachIndexed { index, action ->
            val vehicleId = when (action) {
                is CanonicalAction.ExitVehicle -> action.vehicleId
            }
            val effectId = EffectId("validator-canonical-${index + 1}-${vehicleId.value}")
            val decision = GameReducer.reduce(
                level,
                snapshot,
                GameCommand.TapVehicle(vehicleId, effectId),
            )
            val applied = decision as? RuleDecision.Applied
            val committed = applied?.facts?.any { fact ->
                fact is DomainFact.VehicleExitCommitted && fact.vehicleId == vehicleId
            } == true
            if (!committed) {
                val outcome = when (decision) {
                    is RuleDecision.Rejected -> "rejected: ${decision.reason}"
                    is RuleDecision.Applied -> "produced no VehicleExitCommitted fact"
                }
                return listOf(
                    LevelValidationIssue(
                        ISSUE_CANONICAL_STEP,
                        "Canonical step ${index + 1} (${vehicleId.value}) $outcome",
                    ),
                )
            }
            snapshot = applied.snapshot
        }

        return if (snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) {
            emptyList()
        } else {
            listOf(
                LevelValidationIssue(
                    ISSUE_CANONICAL_NOT_COMPLETE,
                    "Canonical solution ended in ${snapshot.attempt.businessState} instead of COMPLETE",
                ),
            )
        }
    }

    /**
     * V2 parking orders deliberately allow geometrically safe but strategically wrong exits. Therefore
     * publication requires at least one bounded solution instead of requiring every legal branch to win.
     */
    private fun validateReachability(level: LevelDefinition): List<LevelValidationIssue> {
        val memo = HashMap<SearchKey, Boolean>()
        var visitedStates = 0
        var firstDeadEndPath: List<VehicleId>? = null

        fun canComplete(snapshot: GameSnapshot, path: List<VehicleId>): Boolean {
            if (snapshot.attempt.businessState == AttemptBusinessState.COMPLETE) return true
            val key = SearchKey.from(level, snapshot)
            memo[key]?.let { return it }
            if (visitedStates >= maxStates) throw SearchLimitReached(visitedStates)
            val stateOrdinal = visitedStates++

            val successors = level.vehicles
                .asSequence()
                .sortedBy { it.id.value }
                .filter { snapshot.board.vehicles[it.id] == VehicleRuleState.Parked }
                .mapIndexedNotNull { vehicleIndex, vehicle ->
                    val effectId = EffectId(
                        "validator-dfs-$stateOrdinal-$vehicleIndex-${vehicle.id.value}",
                    )
                    val decision = GameReducer.reduce(
                        level,
                        snapshot,
                        GameCommand.TapVehicle(vehicle.id, effectId),
                    )
                    val applied = decision as? RuleDecision.Applied ?: return@mapIndexedNotNull null
                    val safelyExited = applied.facts.any { fact ->
                        fact is DomainFact.VehicleExitCommitted && fact.vehicleId == vehicle.id
                    }
                    if (safelyExited) vehicle.id to applied.snapshot else null
                }
                .toList()

            if (successors.isEmpty()) {
                if (firstDeadEndPath == null) firstDeadEndPath = path
                memo[key] = false
                return false
            }

            val hasSolvableSuccessor = successors.any { (vehicleId, next) ->
                canComplete(next, path + vehicleId)
            }
            memo[key] = hasSolvableSuccessor
            return hasSolvableSuccessor
        }

        return try {
            val solvable = canComplete(initialSnapshot(level, "search"), emptyList())
            if (solvable) {
                emptyList()
            } else {
                val pathText = firstDeadEndPath
                    .orEmpty()
                    .joinToString(separator = " -> ") { it.value }
                    .ifEmpty { "<initial state>" }
                listOf(
                    LevelValidationIssue(
                        ISSUE_DEAD_END,
                        "No complete route was found; first dead end: $pathText",
                    ),
                )
            }
        } catch (limit: SearchLimitReached) {
            listOf(
                LevelValidationIssue(
                    ISSUE_SEARCH_LIMIT,
                    "Solvability search reached maxStates=$maxStates after ${limit.visitedStates} states",
                ),
            )
        }
    }

    private fun initialSnapshot(level: LevelDefinition, purpose: String): GameSnapshot =
        GameSnapshot.initial(
            level,
            AttemptId("validator-$purpose-attempt"),
            AttemptChainId("validator-$purpose-chain"),
        )

    private data class SearchKey(
        val vehicleStates: String,
        val gateStates: String,
        val parkingSlots: String,
        val orderCounts: String,
    ) {
        companion object {
            fun from(level: LevelDefinition, snapshot: GameSnapshot): SearchKey {
                val vehicles = level.vehicles
                    .sortedBy { it.id.value }
                    .joinToString(separator = "") { vehicle ->
                        when (snapshot.board.vehicles[vehicle.id]) {
                            VehicleRuleState.Parked -> "P"
                            is VehicleRuleState.Locked -> "L"
                            is VehicleRuleState.ExitCommitted -> "E"
                            VehicleRuleState.Towed -> "T"
                            null -> "?"
                        }
                    }
                val gates = level.gates
                    .sortedBy { it.id.value }
                    .joinToString(separator = "") { gate ->
                        if (gate.id in snapshot.board.openGateIds) "1" else "0"
                    }
                val parkingSlots = snapshot.parkingLot.slots.joinToString(separator = "|") { waiting ->
                    waiting?.let { "${it.vehicleId.value}@${it.arrivalSequence}" } ?: "_"
                }
                val orderCounts = level.parkingRules.orders.joinToString(separator = "|") { order ->
                    snapshot.parkingLot.fulfilledVehicleIdsByOrder[order.id].orEmpty().size.toString()
                }
                return SearchKey(vehicles, gates, parkingSlots, orderCounts)
            }
        }
    }

    private class SearchLimitReached(val visitedStates: Int) : RuntimeException()

    companion object {
        const val DEFAULT_MAX_STATES: Int = 100_000
        const val ISSUE_CANONICAL_STEP: String = "CANONICAL_STEP_NOT_EXIT"
        const val ISSUE_CANONICAL_NOT_COMPLETE: String = "CANONICAL_NOT_COMPLETE"
        const val ISSUE_DEAD_END: String = "STRONG_DEAD_END"
        const val ISSUE_SEARCH_LIMIT: String = "STRONG_SEARCH_LIMIT"
    }
}
