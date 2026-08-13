package com.example.lcb.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.example.lcb.app.utils.loadInterstitial
import com.example.lcb.parking.feature.game.CaroutGameView
import com.example.lcb.parking.feature.game.GameRewardedAdPlacement
import com.example.lcb.parking.feature.game.GameTelemetryEvent
import com.example.lcb.parking.feature.game.GameToastDuration

/**
 * 独立游戏 Activity。
 *
 * WebView、Canvas 帧循环和广告回调只在本页面生命周期内存在；离开游戏即 release，首页和
 * 选关页不再常驻 WebView，从结构上降低后台绘制、内存占用和跨页面状态耦合。
 */
class GameActivity : ImmersiveGameActivity() {
    override val autoRehideTransientSystemBars: Boolean
        get() = true

    private val activityInstanceId = Integer.toHexString(System.identityHashCode(this))
    private lateinit var gameScreen: CaroutGameView
    private lateinit var firstFrameDrawGate: FirstFrameDrawGate
    private lateinit var progressStore: CaroutProgressStore
    private lateinit var gamePreferencesStore: CaroutGamePreferencesStore
    private val rewardedAdGateway: GameRewardedAdGateway = BusinessGameRewardedAdGateway
    private val analyticsCoordinator = GameplayAnalyticsCoordinator(GameAnalyticsReporter())
    private var gameScreenReleased = false
    private var activityResumed = false
    private var firstVisualFrameReady = false
    private var activeGameToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_game)
        firstFrameDrawGate = FirstFrameDrawGate(
            rootView = findViewById(R.id.game_root),
            timeoutMillis = FIRST_FRAME_TIMEOUT_MILLIS,
            onTimeout = {
                Log.w(LOG_TAG, "activity#$activityInstanceId first-frame gate timed out")
                // 门禁超时不能只显示原生背景；通知 Web 宿主解除透明状态并按需自恢复。
                if (::gameScreen.isInitialized && !gameScreenReleased) {
                    gameScreen.recoverFromFirstFrameTimeout()
                }
            },
        )

        progressStore = CaroutProgressStore(applicationContext)
        gamePreferencesStore = CaroutGamePreferencesStore(applicationContext)
        val progress = progressStore.load()
        val requestedLevel = GameActivityNavigator.requestedLevel(this)
        val requestedEntry = GameActivityNavigator.requestedEntry(this)
        Log.i(
            LOG_TAG,
            "activity#$activityInstanceId onCreate requestedLevel=$requestedLevel " +
                "unlockedLevel=${progress.unlockedLevel}",
        )
        if (requestedLevel > progress.unlockedLevel) {
            Log.e(LOG_TAG, "activity#$activityInstanceId rejected locked level=$requestedLevel")
            finish()
            return
        }

        gameScreen = findViewById(R.id.game_screen)
        gameScreen.bind(
            initialProgressJson = progress.toJson(),
            initialSoundEnabled = gamePreferencesStore.isSoundEnabled(),
            callbacks = object : CaroutGameView.HostCallbacks {
                override fun onFirstFrameRendered() {
                    firstVisualFrameReady = true
                    if (firstFrameDrawGate.open()) {
                        Log.i(LOG_TAG, "activity#$activityInstanceId first-frame gate opened")
                    }
                    if (activityResumed) analyticsCoordinator.onPageShown()
                }

                override fun onExitToGameHomeRequested() = returnToGameHome()

                override fun onProgressSaveRequested(progressJson: String): String =
                    progressStore.save(progressJson).toJson()

                override fun onSoundEnabledChanged(enabled: Boolean) {
                    gamePreferencesStore.setSoundEnabled(enabled)
                }

                override fun onLevelCompleted(levelNumber: Int) {
                    if (levelNumber == LEVEL_COUNT) {
                        showGameToast(getString(R.string.game_all_complete), GameToastDuration.SHORT)
                    }
                    // 关卡规则只上报完成事件；插屏策略仍由应用 Activity 决定。
                    loadInterstitial(condition = { levelNumber % INTERSTITIAL_INTERVAL == 0 }) { }
                }

                override fun onToastRequested(message: String, duration: GameToastDuration) {
                    showGameToast(message, duration)
                }

                override fun onTelemetry(event: GameTelemetryEvent) {
                    analyticsCoordinator.onTelemetry(event)
                }

                override fun onRewardedAdRequested(
                    placement: GameRewardedAdPlacement,
                    onResult: (Boolean) -> Unit,
                ) {
                    rewardedAdGateway.show(this@GameActivity, placement, onResult)
                }
            },
        )
        // GameActivity 自己就是最终展示页，直接加载关卡，不做跨页面首帧等待。
        gameScreen.showLevel(requestedLevel, requestedEntry)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = returnToGameHome()
            },
        )
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        Log.i(LOG_TAG, "activity#$activityInstanceId onResume")
        if (::gameScreen.isInitialized && !gameScreenReleased) {
            gameScreen.setHostActive(true)
            if (firstVisualFrameReady) {
                // 广告或系统遮挡返回后，复用已经提交的 WebView 画面开启新的页面曝光周期。
                gameScreen.postOnAnimation {
                    if (activityResumed && !gameScreenReleased) {
                        analyticsCoordinator.onPageShown()
                    }
                }
            }
        }
    }

    override fun onPause() {
        activityResumed = false
        analyticsCoordinator.onPageLeave()
        Log.i(LOG_TAG, "activity#$activityInstanceId onPause")
        if (::gameScreen.isInitialized && !gameScreenReleased) gameScreen.setHostActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        activityResumed = false
        analyticsCoordinator.onPageLeave()
        Log.i(LOG_TAG, "activity#$activityInstanceId onDestroy finishing=$isFinishing")
        activeGameToast?.cancel()
        activeGameToast = null
        if (::firstFrameDrawGate.isInitialized) firstFrameDrawGate.open()
        releaseGameScreen()
        super.onDestroy()
    }

    private fun returnToGameHome() {
        if (isFinishing || isDestroyed) return
        // 保留游戏最后一帧直到窗口真正退出，避免提前移除 WebView 后露出空背景。
        // 渲染容器在 onDestroy 中统一解绑并回收到进程内单实例池。
        GameActivityNavigator.returnToGameHome(this)
    }

    /** 连续局内提示复用同一个 Toast，新的业务提示直接替换旧提示，避免系统 Toast 排队。 */
    private fun showGameToast(message: String, duration: GameToastDuration) {
        if (isFinishing || isDestroyed || message.isBlank()) return
        activeGameToast?.cancel()
        val androidDuration = when (duration) {
            GameToastDuration.SHORT -> Toast.LENGTH_SHORT
            GameToastDuration.LONG -> Toast.LENGTH_LONG
        }
        activeGameToast = Toast.makeText(applicationContext, message, androidDuration).also(Toast::show)
    }

    /** 统一、幂等地结束游戏渲染资源；用户返回与系统销毁共用同一条释放路径。 */
    private fun releaseGameScreen() {
        if (!::gameScreen.isInitialized || gameScreenReleased) return
        gameScreenReleased = true
        gameScreen.release()
    }

    private companion object {
        const val LOG_TAG = "ParkingGameLoad"
        const val INTERSTITIAL_INTERVAL = 3
        const val FIRST_FRAME_TIMEOUT_MILLIS = 2_500L
    }
}
