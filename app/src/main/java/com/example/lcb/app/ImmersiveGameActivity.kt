package com.example.lcb.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity

/** 三个游戏页面共用的沉浸式窗口生命周期，避免各 Activity 复制系统栏处理。 */
abstract class ImmersiveGameActivity : AppCompatActivity() {
    private lateinit var immersiveWindowController: ImmersiveWindowController
    protected open val autoRehideTransientSystemBars: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    protected fun setImmersiveContentView(@LayoutRes layoutResId: Int) {
        setContentView(layoutResId)
        immersiveWindowController = ImmersiveWindowController(
            window = window,
            autoRehideTransientBars = autoRehideTransientSystemBars,
        ).also { it.onCreate() }
    }

    override fun onResume() {
        super.onResume()
        if (::immersiveWindowController.isInitialized) immersiveWindowController.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::immersiveWindowController.isInitialized) {
            immersiveWindowController.onWindowFocusChanged(hasFocus)
        }
    }

    override fun onDestroy() {
        if (::immersiveWindowController.isInitialized) immersiveWindowController.onDestroy()
        super.onDestroy()
    }
}
