package com.example.lcb.parking.domain.model

@JvmInline
value class LevelId(val value: String) {
    init { require(value.isNotBlank()) { "levelId must not be blank" } }
}

@JvmInline
value class VehicleId(val value: String) {
    init { require(value.isNotBlank()) { "vehicleId must not be blank" } }
}

@JvmInline
value class OrderId(val value: String) {
    init { require(value.isNotBlank()) { "orderId must not be blank" } }
}

@JvmInline
value class ExitId(val value: String) {
    init { require(value.isNotBlank()) { "exitId must not be blank" } }
}

@JvmInline
value class GateId(val value: String) {
    init { require(value.isNotBlank()) { "gateId must not be blank" } }
}

@JvmInline
value class PressurePlateId(val value: String) {
    init { require(value.isNotBlank()) { "pressurePlateId must not be blank" } }
}

@JvmInline
value class AttemptId(val value: String) {
    init { require(value.isNotBlank()) { "attemptId must not be blank" } }
}

@JvmInline
value class AttemptChainId(val value: String) {
    init { require(value.isNotBlank()) { "attemptChainId must not be blank" } }
}

@JvmInline
value class EffectId(val value: String) {
    init { require(value.isNotBlank()) { "effectId must not be blank" } }
}

@JvmInline
value class RewardTransactionId(val value: String) {
    init { require(value.isNotBlank()) { "rewardTransactionId must not be blank" } }
}
