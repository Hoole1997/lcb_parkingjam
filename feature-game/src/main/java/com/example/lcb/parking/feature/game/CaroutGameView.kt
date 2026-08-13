package com.example.lcb.parking.feature.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 carout 游戏的原生宿主。
 *
 * Web 层只负责高频 Canvas 绘制和玩法；宿主只暴露进度、导航和商业能力，二者通过窄接口
 * 通信。页面仅允许访问 APK 内的 appassets 域，文件系统、ContentProvider 和外部跳转均关闭。
 */
class CaroutGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 每个实例使用独立编号，方便对比第一次和第二次 Activity 创建的完整加载链路。 */
    private val instanceId = nextInstanceId.incrementAndGet()

    interface HostCallbacks {
        /** 当前文档的 Canvas 已完成首帧，宿主此时可以安全显示游戏窗口。 */
        fun onFirstFrameRendered()

        /** 返回游戏自己的原生首页，不得返回 Launcher。 */
        fun onExitToGameHomeRequested()

        /**
         * Web 层请求持久化完整进度。
         *
         * 该回调运行在 WebView 的 JavaScript Bridge 工作线程，宿主不得访问 View；必须在
         * 返回前完成校验和持久化，并返回规范化后的 JSON。同步确认可以避免通关后立即退出
         * Activity 时，尚未执行的主线程消息丢失本次进度。
         */
        fun onProgressSaveRequested(progressJson: String): String

        /** 局内声音偏好由应用层持久化，玩法页面不直接依赖 Android 存储 API。 */
        fun onSoundEnabledChanged(enabled: Boolean)

        fun onLevelCompleted(levelNumber: Int)

        /** 网页只提供本地化业务文案，具体提示组件由应用层统一展示。 */
        fun onToastRequested(message: String, duration: GameToastDuration)

        /** 类型化玩法事件由应用层统一转换成统计 SDK 埋点。 */
        fun onTelemetry(event: GameTelemetryEvent)

        /**
         * 请求应用层展示激励广告。只有用户真正获得奖励时才回传 true；游戏层据此执行
         * 一次道具效果，不接触具体广告 SDK。
         */
        fun onRewardedAdRequested(
            placement: GameRewardedAdPlacement,
            onResult: (rewardEarned: Boolean) -> Unit,
        )
    }

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(ASSET_PATH, WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    // 某些厂商 WebView 在连续 destroy/create 时会冻结新 Canvas 的帧调度。渲染容器由池统一
    // 持有并跨游戏 Activity 复用，页面状态和宿主回调仍由每个 CaroutGameView 独立管理。
    private val webView = CaroutWebViewPool.acquire(context)
    private val languageTag = resources.configuration.locales[0].toLanguageTag()
    private val webViewInstanceId = Integer.toHexString(System.identityHashCode(webView))
    @Volatile
    private var callbacks: HostCallbacks? = null
    @Volatile
    private var initialProgressJson: String = DEFAULT_PROGRESS_JSON
    @Volatile
    private var initialSoundEnabled: Boolean = true
    private var loadedOrLoadingLevel: Int? = null
    private var hostActive = false
    private var pageReady = false
    private var firstVisualFrameCommitted = false
    private var recoveryRevealActive = false
    private var recoveryReloaded = false
    private var loadedUrl: String? = null
    private var released = false
    private val activeRewardRequestIds = mutableSetOf<Int>()

    init {
        Log.i(LOG_TAG, "view#$instanceId created webView#$webViewInstanceId")
        // 池中的 WebView 可能仍保留上一局的合成画面。新宿主先隐藏它，并让 Activity 的
        // 庭院背景负责加载兜底；当前文档的视觉帧真正提交后才恢复显示。
        setBackgroundColor(Color.TRANSPARENT)
        webView.alpha = HIDDEN_ALPHA
        addView(
            webView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        configureWebView()
    }

    /** 在首次加载前绑定宿主。之后的进度变更由桥接回调持续同步。 */
    @MainThread
    fun bind(
        initialProgressJson: String,
        initialSoundEnabled: Boolean,
        callbacks: HostCallbacks,
    ) {
        this.callbacks = callbacks
        this.initialProgressJson = initialProgressJson
        this.initialSoundEnabled = initialSoundEnabled
    }

    /** 加载独立游戏 Activity 需要展示的关卡。相同关卡不会被重复加载。 */
    @MainThread
    fun showLevel(levelNumber: Int, entry: GameLevelEntry) {
        ensureLevelLoaded(levelNumber.coerceAtLeast(1), entry)
    }

    @MainThread
    private fun ensureLevelLoaded(levelNumber: Int, entry: GameLevelEntry) {
        if (released) return
        if (loadedOrLoadingLevel == levelNumber) {
            Log.i(LOG_TAG, "view#$instanceId skip duplicate level=$levelNumber")
            return
        }
        loadedOrLoadingLevel = levelNumber
        pageReady = false
        firstVisualFrameCommitted = false
        recoveryRevealActive = false
        recoveryReloaded = false
        webView.alpha = HIDDEN_ALPHA
        webView.onResume()
        val url = gameUrl(levelNumber, entry)
        loadedUrl = url
        Log.i(
            LOG_TAG,
            "view#$instanceId webView#$webViewInstanceId loadUrl level=$levelNumber url=$url",
        )
        webView.loadUrl(url)
    }

    /**
     * 首帧门禁超时后的自恢复入口。
     *
     * WebView 不能继续保持透明，否则页面即使稍后完成也只会露出 Activity 的庭院背景。
     * 已完成页面优先重新请求合成确认；主文档或 JS 未就绪时只自动重载一次，避免无限重试。
     */
    @MainThread
    fun recoverFromFirstFrameTimeout() {
        if (released || firstVisualFrameCommitted) return
        recoveryRevealActive = true
        Log.w(
            LOG_TAG,
            "view#$instanceId first-frame recovery pageReady=$pageReady url=${webView.url}",
        )
        webView.onResume()
        webView.alpha = VISIBLE_ALPHA
        webView.invalidate()

        // 即使 onPageFinished 尚未到达，也先探测 JS 运行时；避免文档其实已初始化时重载，
        // 从而重复发送 level_start 等桥接事件。
        webView.evaluateJavascript(RECOVERY_READY_SCRIPT) { ready ->
            post {
                if (released || firstVisualFrameCommitted) return@post
                if (ready == "true") {
                    awaitFirstVisualFrame(instanceId)
                } else {
                    reloadForRecovery()
                }
            }
        }
    }

    /** 页面隐藏时暂停定时器和 WebView，防止首页背后继续跑帧。 */
    @MainThread
    fun setHostActive(active: Boolean) {
        if (released) return
        Log.i(LOG_TAG, "view#$instanceId hostActive=$active pageReady=$pageReady")
        hostActive = active
        if (active) {
            webView.onResume()
        } else {
            evaluateHostScript("window.CaroutHost?.setPaused(true)")
            webView.onPause()
        }
        if (active && pageReady) evaluateHostScript("window.CaroutHost?.setPaused(false)")
    }

    /** 游戏页的系统返回始终回到游戏首页，Launcher 返回只由 Activity 首页处理。 */
    @MainThread
    fun handleSystemBack(): Boolean {
        callbacks?.onExitToGameHomeRequested()
        return true
    }

    @MainThread
    fun release() {
        if (released) return
        released = true
        Log.i(
            LOG_TAG,
            "view#$instanceId webView#$webViewInstanceId release " +
                "currentUrl=${webView.url} pageReady=$pageReady",
        )
        callbacks = null
        activeRewardRequestIds.clear()
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        // 不在退出阶段发起 about:blank 导航：它是异步任务，可能污染下一实例的加载状态。
        webView.webViewClient = WebViewClient()
        removeView(webView)
        webView.onPause()
        CaroutWebViewPool.recycle(webView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.overScrollMode = OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(NativeBridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val response = assetLoader.shouldInterceptRequest(request.url)
                if (request.url.isTrustedGameUrl()) {
                    Log.d(
                        LOG_TAG,
                        "view#$instanceId asset url=${request.url} handled=${response != null}",
                    )
                }
                return response
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = !request.url.isTrustedGameUrl()

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // loadUrl 是异步的；在新文档开始时再次清除可见状态，避免复用容器闪出旧帧。
                pageReady = false
                firstVisualFrameCommitted = false
                view.alpha = if (recoveryRevealActive) VISIBLE_ALPHA else HIDDEN_ALPHA
                Log.i(LOG_TAG, "view#$instanceId pageStarted url=$url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                val trusted = Uri.parse(url).isTrustedGameUrl()
                Log.i(LOG_TAG, "view#$instanceId pageFinished trusted=$trusted url=$url")
                if (!trusted) return
                pageReady = true
                evaluateHostScript("window.CaroutHost?.setPaused(${!hostActive})")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                Log.e(
                    LOG_TAG,
                    "view#$instanceId mainFrameError code=${error.errorCode} " +
                        "description=${error.description} url=${request.url}",
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                Log.e(
                    LOG_TAG,
                    "view#$instanceId mainFrameHttpError status=${errorResponse.statusCode} " +
                        "reason=${errorResponse.reasonPhrase} url=${request.url}",
                )
            }
        }
    }

    private fun evaluateHostScript(script: String) {
        if (!pageReady || released) return
        webView.evaluateJavascript(script, null)
    }

    @MainThread
    private fun reloadForRecovery() {
        if (released || recoveryReloaded) return
        val url = loadedUrl ?: return
        recoveryReloaded = true
        pageReady = false
        Log.w(LOG_TAG, "view#$instanceId retry cold document url=$url")
        webView.stopLoading()
        webView.loadUrl(url)
    }

    /**
     * Canvas 的 requestAnimationFrame 已执行不代表该帧已经进入 Android 的 WebView 合成层。
     * VisualStateCallback 保证当前 Web 内容可在下一次 View 绘制中呈现，再在下一帧显示
     * WebView 并通知 Activity 放开首帧门，消除冷启动时序竞争造成的偶发空白。
     */
    @MainThread
    private fun awaitFirstVisualFrame(sessionId: Long) {
        if (released || sessionId != instanceId || firstVisualFrameCommitted) return
        webView.postVisualStateCallback(
            sessionId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    post {
                        if (
                            released ||
                            requestId != instanceId ||
                            firstVisualFrameCommitted
                        ) {
                            return@post
                        }
                        firstVisualFrameCommitted = true
                        webView.alpha = VISIBLE_ALPHA
                        webView.invalidate()
                        // alpha 和已提交的 Web 内容在同一个显示帧生效后，宿主才允许窗口交接。
                        webView.postOnAnimation {
                            if (!released && firstVisualFrameCommitted) {
                                Log.i(
                                    LOG_TAG,
                                    "view#$instanceId first visual frame committed",
                                )
                                callbacks?.onFirstFrameRendered()
                            }
                        }
                    }
                }
            },
        )
    }

    /** 奖励结果统一回到主线程，并以 requestId 保证同一广告最多结算一次。 */
    @MainThread
    private fun completeRewardedAdRequest(requestId: Int, rewardEarned: Boolean) {
        if (!activeRewardRequestIds.remove(requestId)) return
        evaluateHostScript(
            "window.CaroutHost?.completeRewardedAd($requestId, $rewardEarned)",
        )
    }

    private fun gameUrl(levelNumber: Int, entry: GameLevelEntry): String =
        buildCaroutGameUrl(levelNumber, instanceId, languageTag, entry)

    private fun Uri.isTrustedGameUrl(): Boolean =
        scheme == "https" && host == APP_ASSETS_HOST && path.orEmpty().startsWith(ASSET_PATH)

    private inner class NativeBridge {
        @JavascriptInterface
        fun loadProgress(): String = initialProgressJson

        @JavascriptInterface
        fun saveProgress(progressJson: String): Boolean {
            val hostCallbacks = callbacks ?: return false
            val stableProgressJson = runCatching {
                hostCallbacks.onProgressSaveRequested(progressJson)
            }.onFailure { error ->
                Log.e(LOG_TAG, "view#$instanceId failed to persist progress", error)
            }.getOrNull() ?: return false

            // 保存成功后立刻刷新宿主快照，当前 WebView 因系统原因重载时不会读到旧进度。
            initialProgressJson = stableProgressJson
            return true
        }

        @JavascriptInterface
        fun loadSoundEnabled(): Boolean = initialSoundEnabled

        @JavascriptInterface
        fun saveSoundEnabled(enabled: Boolean) {
            // 同步更新当前宿主快照，页面自恢复重载时也能立即读到刚刚保存的状态。
            initialSoundEnabled = enabled
            post { callbacks?.onSoundEnabledChanged(enabled) }
        }

        @JavascriptInterface
        fun firstFrameRendered(sessionId: Long) {
            // 复用 WebView 时旧文档可能仍有排队回调，只接受当前 CaroutGameView 的会话。
            if (sessionId != instanceId) return
            post { awaitFirstVisualFrame(sessionId) }
        }

        @JavascriptInterface
        fun exitToGameHome() {
            post { callbacks?.onExitToGameHomeRequested() }
        }

        @JavascriptInterface
        fun levelCompleted(levelNumber: Int) {
            post { callbacks?.onLevelCompleted(levelNumber) }
        }

        @JavascriptInterface
        fun showToast(message: String, durationValue: String) {
            val duration = GameToastDuration.fromBridgeValue(durationValue) ?: return
            val safeMessage = message.trim().take(MAX_TOAST_MESSAGE_LENGTH)
            if (safeMessage.isEmpty()) return
            post { callbacks?.onToastRequested(safeMessage, duration) }
        }

        @JavascriptInterface
        fun levelStarted(levelNumber: Int, entryValue: String) {
            val entry = GameLevelEntry.fromBridgeValue(entryValue) ?: return
            post {
                callbacks?.onTelemetry(GameTelemetryEvent.LevelStarted(levelNumber, entry))
            }
        }

        @JavascriptInterface
        fun gameActionClicked(levelNumber: Int, actionValue: String) {
            val action = GameActionType.fromBridgeValue(actionValue) ?: return
            post {
                callbacks?.onTelemetry(GameTelemetryEvent.ActionClicked(levelNumber, action))
            }
        }

        @JavascriptInterface
        fun levelResult(levelNumber: Int, resultValue: String) {
            val result = GameResultType.fromBridgeValue(resultValue) ?: return
            post {
                callbacks?.onTelemetry(GameTelemetryEvent.LevelResult(levelNumber, result))
            }
        }

        @JavascriptInterface
        fun resultActionClicked(levelNumber: Int, resultValue: String, actionValue: String) {
            val result = GameResultType.fromBridgeValue(resultValue) ?: return
            val action = GameResultActionType.fromBridgeValue(actionValue) ?: return
            val validCombination = when (action) {
                GameResultActionType.NEXT_LEVEL -> result == GameResultType.WIN
                GameResultActionType.RETRY -> result == GameResultType.FAIL
                GameResultActionType.HOME -> true
            }
            if (!validCombination) return
            post {
                callbacks?.onTelemetry(
                    GameTelemetryEvent.ResultActionClicked(levelNumber, result, action),
                )
            }
        }

        @JavascriptInterface
        fun requestRewardedAd(placementValue: String, requestId: Int) {
            post {
                val placement = GameRewardedAdPlacement.fromBridgeValue(placementValue)
                if (placement == null || requestId <= 0) {
                    evaluateHostScript(
                        "window.CaroutHost?.completeRewardedAd($requestId, false)",
                    )
                    return@post
                }
                // 重复 requestId 可能来自异常或恶意脚本；静默忽略，不能误结算正在展示的请求。
                if (!activeRewardRequestIds.add(requestId)) return@post

                val hostCallbacks = callbacks
                if (hostCallbacks == null) {
                    completeRewardedAdRequest(requestId, false)
                    return@post
                }
                try {
                    hostCallbacks.onRewardedAdRequested(placement) { rewardEarned ->
                        post { completeRewardedAdRequest(requestId, rewardEarned) }
                    }
                } catch (_: Exception) {
                    completeRewardedAdRequest(requestId, false)
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "ParkingGameLoad"
        const val BRIDGE_NAME = "CaroutNative"
        const val APP_ASSETS_HOST = "appassets.androidplatform.net"
        const val ASSET_PATH = "/assets/"
        const val DEFAULT_PROGRESS_JSON = "{\"unlocked\":1,\"done\":{}}"
        const val MAX_TOAST_MESSAGE_LENGTH = 200
        // 完全透明的 WebView 在部分厂商内核会被合成器跳过，导致 VisualStateCallback 不返回。
        // 保留不可感知的 1% alpha 让冷启动文档持续参与合成，真正提交首帧后再切到完全可见。
        const val HIDDEN_ALPHA = 0.01f
        const val VISIBLE_ALPHA = 1f
        const val RECOVERY_READY_SCRIPT =
            "Boolean(window.CaroutHost && document.getElementById('game'))"
        val nextInstanceId = AtomicLong(0L)
    }
}

/** 统一生成本地游戏地址，避免根路径尾斜杠与页面前斜杠组合成无法映射的双斜杠。 */
internal fun buildCaroutGameUrl(
    levelNumber: Int,
    sessionId: Long? = null,
    languageTag: String? = null,
    entry: GameLevelEntry? = null,
): String =
    buildString {
        append("https://appassets.androidplatform.net/assets/index.html?host=1&level=")
        append(levelNumber)
        if (!languageTag.isNullOrBlank()) {
            append("&lang=")
            append(Uri.encode(languageTag))
        }
        if (entry != null) {
            append("&entry=")
            append(Uri.encode(entry.bridgeValue))
        }
        // 复用 WebView 时用独立 URL 标识本次文档，既避免读到上一关残留帧，也方便日志追踪。
        if (sessionId != null) append("&session=$sessionId")
    }

/**
 * 进程内只保留一个闲置 WebView，避免部分系统 WebView 在快速销毁、重建时让新 Canvas 停帧。
 *
 * WebView 使用 Application Context，不持有任何 Activity；回收前宿主必须移除 JS bridge、
 * WebViewClient 和 View parent，因此缓存不会把旧页面或 Activity 回调带到下一次进入。
 */
private object CaroutWebViewPool {
    private var cachedWebView: WebView? = null

    @MainThread
    fun acquire(context: Context): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        return cachedWebView?.also { cachedWebView = null }
            ?: WebView(context.applicationContext)
    }

    @MainThread
    fun recycle(webView: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (cachedWebView == null) {
            cachedWebView = webView
            return
        }

        // 正常导航不会出现两个闲置实例；该分支仅处理系统生命周期重叠，保持缓存有界。
        webView.destroy()
    }
}
