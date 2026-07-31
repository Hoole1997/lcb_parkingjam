package com.example.lcb.parking.domain.ports

import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId

sealed interface LevelLoadResult {
    data class Loaded(val level: LevelDefinition) : LevelLoadResult
    data object NotFound : LevelLoadResult
    data class UnsupportedRuleVersion(val ruleVersion: Int) : LevelLoadResult
    data class Corrupt(val reason: String) : LevelLoadResult
    data class Unavailable(val reason: String) : LevelLoadResult
}

interface LevelSource {
    suspend fun load(levelId: LevelId): LevelLoadResult
}

