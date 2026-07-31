package com.example.lcb.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One-way gate for app-owned optional SDK startup.
 *
 * The gate deliberately exposes no SDK types. Game code can therefore never initialize or call
 * an advertising network directly, and duplicate Activity recreation cannot initialize SDKs twice.
 * Launcher 基类自身的启动行为仍取决于其供应商契约，不能由此应用层网关拦截。
 */
internal object OptionalSdkLifecycleGateway {
    private val gate = OneShotActionGate()

    fun install(enable: () -> Unit) = gate.install(enable)

    fun enableAfterConsent() = gate.enable()
}

/** 线程安全的一次性动作门；支持极端情况下 enable 早于 install，且始终最多执行一次。 */
internal class OneShotActionGate {
    private val enabled = AtomicBoolean(false)
    private val executed = AtomicBoolean(false)
    private val action = AtomicReference<(() -> Unit)?>(null)

    fun install(newAction: () -> Unit) {
        if (action.compareAndSet(null, newAction)) executeIfReady()
    }

    fun enable() {
        enabled.set(true)
        executeIfReady()
    }

    private fun executeIfReady() {
        if (!enabled.get()) return
        val installedAction = action.get() ?: return
        if (executed.compareAndSet(false, true)) installedAction()
    }
}
