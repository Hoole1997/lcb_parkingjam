package com.example.lcb.app.ad.renderer

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.android.common.bill.ads.renderer.ToponFullScreenNativeAdRenderer
import com.example.lcb.app.R
import com.thinkup.nativead.api.TUNativeMaterial
import com.thinkup.nativead.api.TUNativePrepareInfo

class DefaultToponFullScreenNativeAdRenderer : ToponFullScreenNativeAdRenderer {

    override fun createLayout(context: Context): ViewGroup {
        return LayoutInflater.from(context)
            .inflate(R.layout.layout_full_native_ad_topon, null, false) as ViewGroup
    }

    override fun bindData(adView: ViewGroup, material: TUNativeMaterial) {
        val ivIcon = adView.findViewById<ImageView>(R.id.iv_ad_icon)
        val tvTitle = adView.findViewById<TextView>(R.id.tv_ad_title)
        val tvDesc = adView.findViewById<TextView>(R.id.tv_ad_desc)
        val tvCta = adView.findViewById<TextView>(R.id.tv_ad_cta)

        tvTitle.text = material.title
        tvDesc.text = material.descriptionText
        tvCta.text = material.callToActionText

        material.iconImageUrl?.let { url ->
            NativeAdImageLoader.loadIcon(url, ivIcon)
        }
    }

    override fun createPrepareInfo(adView: ViewGroup): TUNativePrepareInfo {
        val tvTitle = adView.findViewById<TextView>(R.id.tv_ad_title)
        val tvDesc = adView.findViewById<TextView>(R.id.tv_ad_desc)
        val tvCta = adView.findViewById<TextView>(R.id.tv_ad_cta)
        val ivIcon = adView.findViewById<ImageView>(R.id.iv_ad_icon)

        return TUNativePrepareInfo().apply {
            titleView = tvTitle
            descView = tvDesc
            ctaView = tvCta
            iconView = ivIcon
        }
    }

    override fun createLoadingView(context: Context, container: ViewGroup) {
        container.removeAllViews()
        val progressBar = ProgressBar(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(progressBar)
    }

}
