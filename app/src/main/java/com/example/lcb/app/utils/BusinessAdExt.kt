package com.example.lcb.app.utils

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.common.bill.ads.AdResult
import com.android.common.bill.ads.ext.AdShowExt
import com.android.common.bill.ui.NativeAdStyleType
import com.example.lcb.app.LauncherSdkGateway
import kotlinx.coroutines.launch

fun FragmentActivity.loadNative(
    container: ViewGroup,
    styleType: NativeAdStyleType = NativeAdStyleType.STANDARD,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {}
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                container.visibility = View.GONE
                call.invoke(false)
                return@launch
            }

            val success = AdShowExt.showNativeAdInContainer(
                context = container.context,
                container = container,
                styleType = styleType
            )

            if (success) {
                container.visibility = View.VISIBLE
                call.invoke(true)
            } else {
                container.visibility = View.GONE
                call.invoke(false)
            }
        } catch (_: Exception) {
            container.visibility = View.GONE
            call.invoke(false)
        }
    }
}

fun FragmentActivity.loadInterstitial(
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                call.invoke(false)
                return@launch
            }
            LauncherSdkGateway.beforeShowAd(this@loadInterstitial)
            when (AdShowExt.showInterstitialAd(this@loadInterstitial)) {
                is AdResult.Success -> call.invoke(true)
                is AdResult.Failure -> call.invoke(false)
            }
        } catch (_: Exception) {
            call.invoke(false)
        }
    }
}

fun FragmentActivity.showRewardedAd(
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit,
    position: String? = null
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                call.invoke(false)
                return@launch
            }
            LauncherSdkGateway.beforeShowAd(this@showRewardedAd)
            when (AdShowExt.showRewardedAd(this@showRewardedAd, position = position)) {
                is AdResult.Success -> call.invoke(true)
                is AdResult.Failure -> call.invoke(false)
            }
        } catch (_: Exception) {
            call.invoke(false)
        }
    }
}
