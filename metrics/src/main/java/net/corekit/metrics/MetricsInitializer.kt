package net.corekit.metrics

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import net.corekit.core.ads.RevenueAdManager
import net.corekit.core.report.ReportDataManager
import net.corekit.metrics.data.FirebaseReporter
import net.corekit.metrics.data.ThinkingReporter
import net.corekit.metrics.log.MetricsLogger
import net.corekit.metrics.revenue.AdjustRevenueReporter
import net.corekit.metrics.revenue.FirebaseRevenueReporter

/** Initializes optional analytics reporters exactly once, after the host approves collection. */
object MetricsInitializer {
    private val initialized = AtomicBoolean(false)

    fun initializeAfterConsent(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        runCatching {
            val thinkingReporter = ThinkingReporter()
            ThinkingReporter.init(context.applicationContext)
            RevenueAdManager.setReporters(
                listOf(AdjustRevenueReporter(), FirebaseRevenueReporter()),
            )
            val dataReporters = buildList {
                if (thinkingReporter.checkInitialized()) add(thinkingReporter)
                add(FirebaseReporter())
            }
            ReportDataManager.setReporters(dataReporters)
        }.onSuccess {
            MetricsLogger.d("Metrics reporters initialized after consent")
        }.onFailure { error ->
            // Permit a later safe retry if an SDK was not available during process startup.
            initialized.set(false)
            MetricsLogger.e("Metrics reporter initialization failed", error)
        }
    }
}
