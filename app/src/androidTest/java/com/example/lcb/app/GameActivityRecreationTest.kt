package com.example.lcb.app

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.lcb.parking.feature.game.CaroutGameView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 回归覆盖“进入游戏、退出、再次创建”这条真实 Activity 生命周期，并连续执行多轮。 */
@RunWith(AndroidJUnit4::class)
class GameActivityRecreationTest {

    @Test
    fun gameCanvasIsReadyAfterRepeatedIndependentActivityLaunches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mainIntent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        var mainActivity = instrumentation.startActivitySync(mainIntent) as MainActivity
        instrumentation.waitForIdleSync()
        var previousGameActivity: GameActivity? = null
        var previousSession: String? = null

        repeat(LAUNCH_COUNT) { launchIndex ->
            instrumentation.runOnMainSync {
                GameActivityNavigator.openGame(mainActivity, levelNumber = 1)
            }
            // 必须拿到新创建的 Activity，不能把仍处于生命周期切换中的旧实例误判为第二次进入。
            val activity = awaitResumedActivity(excluded = previousGameActivity)

            val state = awaitWebGameState(activity, excludedSession = previousSession)
            assertEquals("launch ${launchIndex + 1} URL", EXPECTED_PATH, state.path)
            assertNotEquals("launch ${launchIndex + 1} reused stale document", previousSession, state.session)
            assertTrue("launch ${launchIndex + 1} did not initialize Canvas: $state", state.canvasReady)
            assertTrue("launch ${launchIndex + 1} did not initialize host bridge: $state", state.hostReady)
            assertTrue("launch ${launchIndex + 1} Canvas did not paint a frame: $state", state.framePainted)

            instrumentation.runOnMainSync {
                activity.onBackPressedDispatcher.onBackPressed()
            }
            mainActivity = awaitResumedActivity()
            previousGameActivity = activity
            previousSession = state.session
        }

        instrumentation.runOnMainSync(mainActivity::finish)
    }

    private inline fun <reified T> awaitResumedActivity(excluded: T? = null): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(MAX_ATTEMPTS) {
            instrumentation.waitForIdleSync()
            val resumed = AtomicReference<T?>()
            instrumentation.runOnMainSync {
                resumed.set(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<T>()
                        .firstOrNull { it !== excluded },
                )
            }
            resumed.get()?.let { return it }
            SystemClock.sleep(RETRY_INTERVAL_MS)
        }
        error("No resumed ${T::class.java.simpleName}")
    }

    private fun awaitWebGameState(
        activity: GameActivity,
        excludedSession: String?,
    ): WebGameState {
        repeat(MAX_ATTEMPTS) {
            inspectWebGameState(activity)?.let { state ->
                if (
                    state.session.isNotEmpty() &&
                    state.session != excludedSession &&
                    state.canvasReady &&
                    state.hostReady &&
                    state.framePainted
                ) {
                    return state
                }
            }
            SystemClock.sleep(RETRY_INTERVAL_MS)
        }
        return requireNotNull(inspectWebGameState(activity))
    }

    private fun inspectWebGameState(activity: GameActivity): WebGameState? {
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val gameView = activity.findViewById<CaroutGameView>(R.id.game_screen)
            val webView = gameView.getChildAt(0) as WebView
            webView.evaluateJavascript(INSPECTION_SCRIPT) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        if (!latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        val fields = result.get().orEmpty().removeSurrounding("\"").split('|')
        if (fields.size != 5) return null
        return WebGameState(
            path = fields[0],
            session = fields[1],
            canvasReady = fields[2] == "true",
            hostReady = fields[3] == "true",
            framePainted = fields[4] == "true",
        )
    }

    private data class WebGameState(
        val path: String,
        val session: String,
        val canvasReady: Boolean,
        val hostReady: Boolean,
        val framePainted: Boolean,
    )

    private companion object {
        const val EXPECTED_PATH = "/assets/index.html"
        const val LAUNCH_COUNT = 3
        const val MAX_ATTEMPTS = 30
        const val RETRY_INTERVAL_MS = 100L
        const val JS_TIMEOUT_SECONDS = 2L
        const val INSPECTION_SCRIPT =
            "(()=>{const c=document.getElementById('game');" +
                "const ready=!!c&&c.width>0;let painted=false;" +
                "try{painted=ready&&c.getContext('2d').getImageData(1,1,1,1).data[3]>0}catch(e){}" +
                "const session=new URLSearchParams(location.search).get('session');" +
                "return [location.pathname,session,ready," +
                "typeof window.CaroutHost==='object',painted].join('|')})()"
    }
}
