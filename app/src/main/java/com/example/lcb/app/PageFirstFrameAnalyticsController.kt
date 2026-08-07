package com.example.lcb.app

import android.view.View
import android.view.ViewTreeObserver

/**
 * 在原生页面完成首帧绘制后开启曝光周期。
 *
 * PreDraw 后再等待一个 Choreographer 帧，确保上报发生在实际绘制之后；页面中途离开时通过
 * generation 丢弃过期回调，避免产生没有真实曝光的 `page_show`。
 */
internal class PageFirstFrameAnalyticsController(
    private val rootView: View,
    private val session: PageAnalyticsSession,
) {
    private var resumed = false
    private var generation = 0L
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    fun onResume() {
        resumed = true
        generation++
        val expectedGeneration = generation
        removePreDrawListener()
        val listener = ViewTreeObserver.OnPreDrawListener {
            removePreDrawListener()
            rootView.postOnAnimation {
                if (resumed && generation == expectedGeneration) session.show()
            }
            true
        }
        preDrawListener = listener
        rootView.viewTreeObserver.addOnPreDrawListener(listener)
        rootView.invalidate()
    }

    fun onPause() {
        resumed = false
        generation++
        removePreDrawListener()
        session.leave()
    }

    fun onDestroy() {
        resumed = false
        generation++
        removePreDrawListener()
        session.leave()
    }

    private fun removePreDrawListener() {
        val listener = preDrawListener ?: return
        preDrawListener = null
        if (rootView.viewTreeObserver.isAlive) {
            rootView.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
}
