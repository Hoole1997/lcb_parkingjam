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

        /** Web 层保存后的完整进度 JSON，宿主应做校验再持久化。 */
        fun onProgressChanged(progressJson: String)

        fun onLevelCompleted(levelNumber: Int)

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
    private var callbacks: HostCallbacks? = null
    @Volatile
    private var initialProgressJson: String = DEFAULT_PROGRESS_JSON
    private var loadedOrLoadingLevel: Int? = null
    private var hostActive = false
    private var pageReady = false
    private var released = false
    private val activeRewardRequestIds = mutableSetOf<Int>()

    init {
        Log.i(LOG_TAG, "view#$instanceId created webView#$webViewInstanceId")
        setBackgroundColor(Color.rgb(238, 246, 222))
        addView(
            webView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        configureWebView()
    }

    /** 在首次加载前绑定宿主。之后的进度变更由桥接回调持续同步。 */
    @MainThread
    fun bind(initialProgressJson: String, callbacks: HostCallbacks) {
        this.callbacks = callbacks
        this.initialProgressJson = initialProgressJson
    }

    /** 加载独立游戏 Activity 需要展示的关卡。相同关卡不会被重复加载。 */
    @MainThread
    fun showLevel(levelNumber: Int) {
        ensureLevelLoaded(levelNumber.coerceAtLeast(1))
    }

    @MainThread
    private fun ensureLevelLoaded(levelNumber: Int) {
        if (released) return
        if (loadedOrLoadingLevel == levelNumber) {
            Log.i(LOG_TAG, "view#$instanceId skip duplicate level=$levelNumber")
            return
        }
        loadedOrLoadingLevel = levelNumber
        pageReady = false
        // Activity 负责页面显隐，WebView 只加载当前关卡，不再参与跨 Activity 的首帧切换。
        webView.onResume()
        val url = gameUrl(levelNumber)
        Log.i(
            LOG_TAG,
            "view#$instanceId webView#$webViewInstanceId loadUrl level=$levelNumber url=$url",
        )
        webView.loadUrl(url)
    }

    /** 宿主完成校验后回写规范化进度，保证下一次页面重载不会读取旧快照。 */
    @MainThread
    fun updateProgressJson(progressJson: String) {
        initialProgressJson = progressJson
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

    /** 奖励结果统一回到主线程，并以 requestId 保证同一广告最多结算一次。 */
    @MainThread
    private fun completeRewardedAdRequest(requestId: Int, rewardEarned: Boolean) {
        if (!activeRewardRequestIds.remove(requestId)) return
        evaluateHostScript(
            "window.CaroutHost?.completeRewardedAd($requestId, $rewardEarned)",
        )
    }

    private fun gameUrl(levelNumber: Int): String =
        buildCaroutGameUrl(levelNumber, instanceId, languageTag)

    private fun Uri.isTrustedGameUrl(): Boolean =
        scheme == "https" && host == APP_ASSETS_HOST && path.orEmpty().startsWith(ASSET_PATH)

    private inner class NativeBridge {
        @JavascriptInterface
        fun loadProgress(): String = initialProgressJson

        @JavascriptInterface
        fun saveProgress(progressJson: String) {
            post { callbacks?.onProgressChanged(progressJson) }
        }

        @JavascriptInterface
        fun firstFrameRendered(sessionId: Long) {
            // 复用 WebView 时旧文档可能仍有排队回调，只接受当前 CaroutGameView 的会话。
            if (sessionId != instanceId) return
            post { callbacks?.onFirstFrameRendered() }
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
        val nextInstanceId = AtomicLong(0L)
    }
}

/** 统一生成本地游戏地址，避免根路径尾斜杠与页面前斜杠组合成无法映射的双斜杠。 */
internal fun buildCaroutGameUrl(
    levelNumber: Int,
    sessionId: Long? = null,
    languageTag: String? = null,
): String =
    buildString {
        append("https://appassets.androidplatform.net/assets/index.html?host=1&level=")
        append(levelNumber)
        if (!languageTag.isNullOrBlank()) {
            append("&lang=")
            append(Uri.encode(languageTag))
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
