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
        // AndroidX 要求应用语言 API 在 Activity.onCreate() 之后调用；首次同步发生在内容绘制前。
        AppLanguageController.synchronize(this)
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
        // 应用使用英文兜底时，系统语言变化未必改变当前 Configuration；恢复前主动重新同步。
        AppLanguageController.synchronize(this)
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
