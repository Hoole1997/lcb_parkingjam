package com.example.lcb.parking.feature.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel

internal enum class ParkingMotionStage {
    BOARD_TO_WAITING_SLOT,
    BOARD_TO_ORDER,
    WAITING_SLOT_TO_ORDER,
}

/** 单帧只绘制一辆跨层车辆，保证内存与每帧绘制成本都有稳定上界。 */
internal data class ActiveParkingMotion(
    val vehicleId: String,
    val artKey: ParkingVehicleArtKey,
    val stage: ParkingMotionStage,
    val boardVehicle: VehicleRenderModel? = null,
    val slotIndex: Int? = null,
)

/**
 * Compose 停车动画的有界顺序协调器。
 *
 * 它维护的是纯视觉账本：权威快照可以立刻进入最终状态，但入槽车辆在抵达前不会瞬移出现，
 * 已调度车辆也会保留在原槽位直到轮到自己驶离。账本从不回写领域规则。
 */
@Stable
internal class ParkingMotionController(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    init {
        require(queueCapacity > 0) { "Parking motion queue capacity must be positive" }
    }

    private val pendingEffects = Channel<GamePresentationEffect.MoveVehicle>(queueCapacity)
    private val handoffSignals = Channel<Unit>(queueCapacity)
    /** 已登记但尚未收到 Canvas 离场完成事件的 effect。仅在主线程访问。 */
    private val awaitingHandoffEffectIds = HashSet<String>(queueCapacity)
    private val readyHandoffEffectIds = HashSet<String>(queueCapacity)

    /** 已经写入权威车位、但视觉上尚未抵达的车辆。 */
    private var pendingArrivalVehicleIds by mutableStateOf(emptySet<String>())
    /** 已从权威车位移除、但视觉上仍停留或正在驶离的车辆。 */
    private var dispatchedVehicleIds by mutableStateOf(emptySet<String>())
    private var heldDispatches by mutableStateOf(emptyList<ParkingDispatchMotion>())
    private var pendingCount by mutableIntStateOf(0)

    var activeMotion by mutableStateOf<ActiveParkingMotion?>(null)
        private set
    /** 高频帧进度与低频元数据分离，避免每帧复制完整车辆模型。 */
    var activeProgress by mutableFloatStateOf(0f)
        private set

    val isIdle: Boolean
        get() = pendingCount == 0 && activeMotion == null

    /**
     * 与棋盘 Scheduler 共用同一个 effect 收集点。队列溢出时只快进视觉账本，业务状态和棋盘
     * 确认仍由原有有界调度器处理，绝不会阻塞点击线程。
     */
    fun enqueue(effect: GamePresentationEffect) {
        val movement = effect as? GamePresentationEffect.MoveVehicle ?: return
        val spec = movement.parkingMotion ?: return
        if (spec.presentationDurationMillis <= 0L) return

        register(spec)
        val accepted = pendingEffects.trySend(movement).isSuccess
        if (accepted) {
            awaitingHandoffEffectIds += movement.effectId
            pendingCount += 1
        } else {
            release(spec)
        }
    }

    /** Canvas 最后一帧落定后调用；重复或已快进的信号会被安全忽略。 */
    fun onBoardExitReady(effectId: String) {
        if (effectId !in awaitingHandoffEffectIds) return
        if (readyHandoffEffectIds.add(effectId)) {
            // 容量与待处理移动相同；即使通知已满，ready set 仍保存真实状态。
            handoffSignals.trySend(Unit)
        }
    }

    /** 返回该槽位此刻应绘制的完整车型；槽位底图仍由稳定 UI 状态负责。 */
    fun presentedArtKey(slot: ParkingSlotUiState): ParkingVehicleArtKey? {
        val held = heldDispatches.firstOrNull { dispatch ->
            dispatch.fromSlotIndex == slot.index &&
                dispatch.vehicleId !in pendingArrivalVehicleIds
        }
        if (held != null) return held.parkingArtKey
        if (slot.vehicleId in pendingArrivalVehicleIds || slot.vehicleId in dispatchedVehicleIds) {
            return null
        }
        return slot.parkingArtKey
    }

    /** 由生命周期 STARTED 区间持有；退出区间时协程取消并在 finally 中快进账本。 */
    suspend fun run(animationsEnabled: () -> Boolean) {
        try {
            while (true) {
                val effect = pendingEffects.receive()
                val spec = effect.parkingMotion ?: continue
                awaitBoardExit(effect.effectId)
                if (animationsEnabled()) {
                    play(spec)
                } else {
                    release(spec)
                }
                pendingCount = (pendingCount - 1).coerceAtLeast(0)
            }
        } finally {
            fastForward()
        }
    }

    /** 页面停止、隐藏或销毁时清空所有进程内视觉状态；稳定快照会立即成为画面真相。 */
    fun fastForward() {
        while (pendingEffects.tryReceive().isSuccess) {
            // Channel 中只保存轻量效果；无需逐项释放，下面一次性清空视觉账本。
        }
        while (handoffSignals.tryReceive().isSuccess) {
            // 通知不携带业务数据；清空集合即可完成快进。
        }
        awaitingHandoffEffectIds.clear()
        readyHandoffEffectIds.clear()
        activeMotion = null
        activeProgress = 0f
        pendingArrivalVehicleIds = emptySet()
        dispatchedVehicleIds = emptySet()
        heldDispatches = emptyList()
        pendingCount = 0
    }

    private suspend fun awaitBoardExit(effectId: String) {
        while (!readyHandoffEffectIds.remove(effectId)) {
            handoffSignals.receive()
        }
        awaitingHandoffEffectIds.remove(effectId)
    }

    private suspend fun play(spec: ParkingMotionSpec) {
        when (val destination = spec.destination) {
            is ParkingMotionDestination.WaitingSlot -> {
                animate(
                    frame = ActiveParkingMotion(
                        vehicleId = spec.arrivingVehicle.id,
                        artKey = spec.arrivingVehicle.parkingArtKey,
                        stage = ParkingMotionStage.BOARD_TO_WAITING_SLOT,
                        boardVehicle = spec.arrivingVehicle,
                        slotIndex = destination.slotIndex,
                    ),
                    durationMillis = ParkingMotionTiming.WAITING_SLOT_ARRIVAL_MILLIS,
                )
                pendingArrivalVehicleIds = pendingArrivalVehicleIds - spec.arrivingVehicle.id
            }
            ParkingMotionDestination.CurrentOrder -> {
                animate(
                    frame = ActiveParkingMotion(
                        vehicleId = spec.arrivingVehicle.id,
                        artKey = spec.arrivingVehicle.parkingArtKey,
                        stage = ParkingMotionStage.BOARD_TO_ORDER,
                        boardVehicle = spec.arrivingVehicle,
                    ),
                    durationMillis = ParkingMotionTiming.ORDER_ARRIVAL_MILLIS,
                )
            }
            ParkingMotionDestination.Bypass -> Unit
        }

        // dispatches 已由领域按 arrivalSequence 排序；这里严格逐辆播放，不按槽位重排。
        var dispatchIndex = 0
        while (dispatchIndex < spec.dispatches.size) {
            val dispatch = spec.dispatches[dispatchIndex]
            heldDispatches = heldDispatches - dispatch
            animate(
                frame = ActiveParkingMotion(
                    vehicleId = dispatch.vehicleId,
                    artKey = dispatch.parkingArtKey,
                    stage = ParkingMotionStage.WAITING_SLOT_TO_ORDER,
                    slotIndex = dispatch.fromSlotIndex,
                ),
                durationMillis = ParkingMotionTiming.DISPATCH_TO_ORDER_MILLIS,
            )
            dispatchedVehicleIds = dispatchedVehicleIds - dispatch.vehicleId
            dispatchIndex++
        }
        release(spec)
    }

    private suspend fun animate(
        frame: ActiveParkingMotion,
        durationMillis: Long,
    ) {
        val progress = Animatable(0f)
        activeMotion = frame
        activeProgress = 0f
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMillis.toInt(),
                easing = NaturalDeceleration,
            ),
        ) {
            activeProgress = value
        }
        activeMotion = null
        activeProgress = 0f
    }

    private fun register(spec: ParkingMotionSpec) {
        val destination = spec.destination
        if (destination is ParkingMotionDestination.WaitingSlot) {
            pendingArrivalVehicleIds = pendingArrivalVehicleIds + spec.arrivingVehicle.id
        }
        if (spec.dispatches.isNotEmpty()) {
            heldDispatches = heldDispatches + spec.dispatches
            dispatchedVehicleIds = dispatchedVehicleIds + spec.dispatches.map { it.vehicleId }
        }
    }

    private fun release(spec: ParkingMotionSpec) {
        pendingArrivalVehicleIds = pendingArrivalVehicleIds - spec.arrivingVehicle.id
        if (spec.dispatches.isNotEmpty()) {
            val dispatchIds = spec.dispatches.mapTo(HashSet(spec.dispatches.size)) { it.vehicleId }
            dispatchedVehicleIds = dispatchedVehicleIds - dispatchIds
            heldDispatches = heldDispatches.filterNot { it.vehicleId in dispatchIds }
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 32
        val NaturalDeceleration = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    }
}
