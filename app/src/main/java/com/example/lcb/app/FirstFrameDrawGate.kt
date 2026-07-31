package com.example.lcb.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread

/**
 * 在异步内容完成首帧前阻止 Activity 提交空窗口。
 *
 * 系统会继续保留栈内上一 Activity 的最后画面；超时只负责兜底解锁，避免 WebView 异常时
 * 页面永久无法绘制。该组件不感知 WebView、游戏规则或导航，可单独复用于其他异步页面。
 */
internal class FirstFrameDrawGate(
    private val rootView: View,
    timeoutMillis: Long,
    private val onTimeout: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var opened = false
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener { opened }
    private val timeoutRunnable = Runnable {
        if (open()) onTimeout()
    }

    init {
        rootView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        mainHandler.postDelayed(timeoutRunnable, timeoutMillis)
    }

    /** 返回 true 表示本次调用实际打开了绘制门。 */
    @MainThread
    fun open(): Boolean {
        if (opened) return false
        opened = true
        mainHandler.removeCallbacks(timeoutRunnable)
        if (rootView.viewTreeObserver.isAlive) {
            rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        rootView.invalidate()
        return true
    }
}
