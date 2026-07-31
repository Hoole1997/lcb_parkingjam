package com.example.lcb.parking.feature.game

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 主线程上的有界表现调度器。
 *
 * 合法驶出仍按 FIFO 播放，避免交叉车流穿模；阻挡回弹则按 vehicleId 独立并发，同一车辆的
 * 重复反馈直接合并并确认。这样连点三辆被挡车辆不会把整张棋盘锁进串行动画队列。
 */
internal class GameAnimationScheduler(
    private val boardView: ParkingBoardView,
    private val maxPendingEffects: Int = DEFAULT_MAX_PENDING_EFFECTS,
    private val maxConcurrentRebounds: Int = DEFAULT_MAX_CONCURRENT_REBOUNDS,
) {
    init {
        require(maxPendingEffects > 0) { "maxPendingEffects must be positive" }
        require(maxConcurrentRebounds > 0) { "maxConcurrentRebounds must be positive" }
    }

    private val primaryPendingEffects = ArrayDeque<GamePresentationEffect>(maxPendingEffects)
    private var primaryAnimator: ValueAnimator? = null
    private var activePrimaryEffect: GamePresentationEffect? = null
    private val reboundsByVehicleId = LinkedHashMap<String, RunningRebound>(maxConcurrentRebounds)
    private val reboundGate = ReboundEffectGate(maxConcurrentRebounds)
    private var cancelled = false

    var onQueueIdle: (() -> Unit)? = null
    var onEffectCompleted: ((GamePresentationEffect) -> Unit)? = null
    /** 棋盘离场最后一帧落定后、停车区动画开始前的精确交接点。 */
    var onBoardExitReady: ((GamePresentationEffect.MoveVehicle) -> Unit)? = null

    val isIdle: Boolean
        get() = primaryAnimator == null &&
            primaryPendingEffects.isEmpty() &&
            reboundsByVehicleId.isEmpty()

    fun enqueue(effect: GamePresentationEffect) {
        if (effect is GamePresentationEffect.ReboundVehicle) {
            enqueueRebound(effect)
        } else {
            enqueuePrimary(effect)
        }
    }

    private fun enqueueRebound(effect: GamePresentationEffect.ReboundVehicle) {
        when (reboundGate.acquire(effect.vehicleId)) {
            ReboundAdmission.COALESCED,
            ReboundAdmission.SATURATED,
            -> {
                // 同车连点只保留首个可见回弹；新 effect 仍立即确认，避免表现门悬挂。
                onEffectCompleted?.invoke(effect)
                notifyIdleIfNeeded()
                return
            }
            ReboundAdmission.START -> {
                if (totalTrackedEffects() >= maxPendingEffects) {
                    reboundGate.release(effect.vehicleId)
                    onEffectCompleted?.invoke(effect)
                    notifyIdleIfNeeded()
                    return
                }
            }
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = effect.durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { runningAnimator ->
                val progress = runningAnimator.animatedValue as Float
                val reboundProgress = sin(PI * progress).toFloat()
                boardView.setAnimatedVehicleOffset(
                    vehicleId = effect.vehicleId,
                    rowOffset = effect.deltaRows * reboundProgress,
                    columnOffset = effect.deltaColumns * reboundProgress,
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    boardView.clearAnimatedVehicleOffset(effect.vehicleId)
                    val running = reboundsByVehicleId[effect.vehicleId]
                    if (running?.animator === animation) reboundsByVehicleId.remove(effect.vehicleId)
                    reboundGate.release(effect.vehicleId)
                    if (!cancelled) onEffectCompleted?.invoke(effect)
                    notifyIdleIfNeeded()
                }
            })
        }
        reboundsByVehicleId[effect.vehicleId] = RunningRebound(effect, animator)
        animator.start()
    }

    private fun enqueuePrimary(effect: GamePresentationEffect) {
        if (totalTrackedEffects() >= maxPendingEffects) {
            // 业务已经提交；表现有界溢出时安全快进，绝不能反向阻塞规则或结算。
            suppressSourceIfRequired(effect)
            onEffectCompleted?.invoke(effect)
            notifyIdleIfNeeded()
            return
        }
        primaryPendingEffects.addLast(effect)
        if (primaryAnimator == null) playNextPrimary()
    }

    /** 页面停止/销毁时快进并确认所有已接收效果，防止表现门永久等待 Animator。 */
    fun finishAll() {
        cancel(fastForward = true)
    }

    fun cancel(fastForward: Boolean = false) {
        cancelled = true
        val effectsToComplete = ArrayList<GamePresentationEffect>(totalTrackedEffects())
        activePrimaryEffect?.let(effectsToComplete::add)
        while (primaryPendingEffects.isNotEmpty()) {
            effectsToComplete += primaryPendingEffects.removeFirst()
        }
        val runningRebounds = ArrayList(reboundsByVehicleId.values)
        var reboundIndex = 0
        while (reboundIndex < runningRebounds.size) {
            effectsToComplete += runningRebounds[reboundIndex].effect
            reboundIndex++
        }

        activePrimaryEffect = null
        reboundsByVehicleId.clear()
        reboundGate.clear()
        primaryAnimator?.cancel()
        primaryAnimator = null
        reboundIndex = 0
        while (reboundIndex < runningRebounds.size) {
            runningRebounds[reboundIndex].animator.cancel()
            reboundIndex++
        }

        var effectIndex = 0
        while (effectIndex < effectsToComplete.size) {
            suppressSourceIfRequired(effectsToComplete[effectIndex])
            effectIndex++
        }
        boardView.clearAllAnimatedVehicleOffsets()
        boardView.clearAllTransientHighlights()
        cancelled = false

        if (fastForward) {
            effectIndex = 0
            while (effectIndex < effectsToComplete.size) {
                onEffectCompleted?.invoke(effectsToComplete[effectIndex])
                effectIndex++
            }
            onQueueIdle?.invoke()
        }
    }

    private fun playNextPrimary() {
        val effect = primaryPendingEffects.removeFirstOrNull()
        if (effect == null) {
            primaryAnimator = null
            activePrimaryEffect = null
            notifyIdleIfNeeded()
            return
        }
        activePrimaryEffect = effect
        when (effect) {
            is GamePresentationEffect.MoveVehicle -> playPrimaryMovement(effect)
            is GamePresentationEffect.HighlightVehicle -> playPrimaryHighlight(effect)
            is GamePresentationEffect.ReboundVehicle -> error("Rebounds must use their per-vehicle lane")
        }
    }

    private fun playPrimaryMovement(effect: GamePresentationEffect.MoveVehicle) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = effect.durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { runningAnimator ->
                val progress = runningAnimator.animatedValue as Float
                boardView.setAnimatedVehicleOffset(
                    vehicleId = effect.vehicleId,
                    rowOffset = effect.deltaRows * progress,
                    columnOffset = effect.deltaColumns * progress,
                    renderVehicle = effect.renderVehicle,
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleMovementFrame(effect)
                    if (cancelled) return
                    onBoardExitReady?.invoke(effect)
                    val postExitDuration = effect.parkingMotion
                        ?.presentationDurationMillis
                        .orZero()
                    if (postExitDuration > 0L) {
                        playPostExitDelay(effect, postExitDuration)
                    } else {
                        completePrimary(effect, presentationAlreadySettled = true)
                    }
                }
            })
        }
        primaryAnimator = animator
        animator.start()
    }

    /**
     * Compose 在这一段播放跨层入槽/调度动画。Scheduler 保持同一个主效果处于活动态，
     * 因而下一辆成功离场仍按 FIFO 开始，终态弹层也不会提前越过停车动画。
     */
    private fun playPostExitDelay(
        effect: GamePresentationEffect.MoveVehicle,
        durationMillis: Long,
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis.coerceIn(
                MIN_DURATION_MILLIS,
                MAX_POST_EXIT_DURATION_MILLIS,
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        completePrimary(effect, presentationAlreadySettled = true)
                    }
                }
            })
        }
        primaryAnimator = animator
        animator.start()
    }

    private fun playPrimaryHighlight(effect: GamePresentationEffect.HighlightVehicle) {
        boardView.setTransientHighlight(effect.vehicleId, highlighted = true)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = effect.durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
            addListener(primaryCompletionListener(effect))
        }
        primaryAnimator = animator
        animator.start()
    }

    private fun primaryCompletionListener(effect: GamePresentationEffect): AnimatorListenerAdapter {
        return object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) completePrimary(effect, presentationAlreadySettled = false)
            }
        }
    }

    private fun settleMovementFrame(effect: GamePresentationEffect.MoveVehicle) {
        if (effect.hideSourceUntilStateUpdate) {
            boardView.hideVehicleAtSourceUntilStateUpdate(effect.vehicleId)
        } else {
            boardView.clearAnimatedVehicleOffset(effect.vehicleId)
        }
    }

    private fun completePrimary(
        effect: GamePresentationEffect,
        presentationAlreadySettled: Boolean,
    ) {
        if (!presentationAlreadySettled) {
            when (effect) {
                is GamePresentationEffect.MoveVehicle -> settleMovementFrame(effect)
                is GamePresentationEffect.HighlightVehicle -> {
                    boardView.setTransientHighlight(effect.vehicleId, highlighted = false)
                }
                is GamePresentationEffect.ReboundVehicle -> Unit
            }
        }
        primaryAnimator = null
        activePrimaryEffect = null
        onEffectCompleted?.invoke(effect)
        playNextPrimary()
    }

    private fun totalTrackedEffects(): Int =
        primaryPendingEffects.size +
            reboundsByVehicleId.size +
            if (activePrimaryEffect == null) 0 else 1

    private fun notifyIdleIfNeeded() {
        if (!cancelled && isIdle) onQueueIdle?.invoke()
    }

    private fun suppressSourceIfRequired(effect: GamePresentationEffect?) {
        if (effect is GamePresentationEffect.MoveVehicle && effect.hideSourceUntilStateUpdate) {
            boardView.hideVehicleAtSourceUntilStateUpdate(effect.vehicleId)
        }
    }

    private data class RunningRebound(
        val effect: GamePresentationEffect.ReboundVehicle,
        val animator: ValueAnimator,
    )

    private companion object {
        const val DEFAULT_MAX_PENDING_EFFECTS = 32
        const val DEFAULT_MAX_CONCURRENT_REBOUNDS = 6
        const val MIN_DURATION_MILLIS = 1L
        const val MAX_DURATION_MILLIS = 2_000L
        const val MAX_POST_EXIT_DURATION_MILLIS = 5_000L
    }
}

private fun Long?.orZero(): Long = this ?: 0L
