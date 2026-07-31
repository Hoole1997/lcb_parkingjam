package com.example.lcb.app

import android.app.Application
import com.blankj.utilcode.util.LogUtils
import com.example.lcb.app.ad.LcbAdInitializer
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import net.corekit.metrics.adjust.AdjustTracker
import net.corekit.metrics.MetricsInitializer

/**
 * 归因 SDK 的稳定回调类型，避免公共初始化逻辑感知渠道 SDK 的混淆类。
 */
internal typealias AttributionListener = (
    isOrganic: Boolean,
    network: String,
    campaign: String,
    adgroup: String,
    creative: String,
    jsonResponse: String,
) -> Unit

/**
 * 承载所有渠道共用的 Application 初始化逻辑。
 *
 * 各渠道的 [LcbApp] 只负责适配对应 Launcher SDK 的 pre-R8 符号，业务初始化统一留在这里，
 * 后续升级任一渠道 SDK 时不需要复制或同步广告、归因逻辑。
 */
internal class LcbAppDelegate(
    private val application: Application,
) {

    fun onCreate(registerAttributionListener: (AttributionListener) -> Unit) {
        // Optional SDKs are installed as a deferred action. MainActivity enables this action only
        // after the user's persisted privacy choice has been read off the main thread.
        OptionalSdkLifecycleGateway.install {
            runCatching {
                FirebaseAnalytics.getInstance(application).setAnalyticsCollectionEnabled(true)
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
            }.onFailure { error ->
                LogUtils.e("Optional Firebase startup failed", error)
            }
            runCatching {
                MetricsInitializer.initializeAfterConsent(application.applicationContext)
            }.onFailure { error ->
                LogUtils.e("Optional metrics startup failed", error)
            }
            runCatching {
                LcbAdInitializer.initialize(application)
            }.onFailure { error ->
                // A broken ad network must not terminate the process or block attribution setup.
                LogUtils.e("Optional advertising startup failed", error)
            }
            runCatching {
                registerAttributionListener { isOrganic, network, campaign, adgroup, creative, jsonResponse ->
                    AdjustTracker.init(
                        context = application.applicationContext,
                        network = network,
                        campaign = campaign,
                        adgroup = adgroup,
                        creative = creative,
                        jsonResponse = jsonResponse,
                    )
                    // Do not print the raw attribution response: it may contain device metadata.
                    LogUtils.i(
                        "Attribution ready: isOrganic=$isOrganic, network=$network, " +
                            "campaign=$campaign, adgroup=$adgroup, creative=$creative",
                    )
                }
            }.onFailure { error ->
                LogUtils.e("Attribution listener registration failed", error)
            }
        }
    }

    fun launcherActivityClass(): Class<*> = MainActivity::class.java

    fun protectedActivityClasses(): List<Class<*>> = listOf(
        MainActivity::class.java,
        SettingsActivity::class.java,
        LevelSelectActivity::class.java,
        GameActivity::class.java,
    )
}
