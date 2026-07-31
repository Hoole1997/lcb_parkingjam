package com.example.lcb.app.ad.renderer

import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * Shared, lifecycle-aware image path for third-party native ad renderers.
 *
 * Glide bounds decode size to the actual icon target and cancels requests with the owning view,
 * avoiding one thread and one original-resolution bitmap allocation per ad impression.
 */
internal object NativeAdImageLoader {
    private const val MAX_ICON_SIZE_PX = 256

    fun loadIcon(url: String, target: ImageView) {
        Glide.with(target)
            .load(url)
            .override(MAX_ICON_SIZE_PX, MAX_ICON_SIZE_PX)
            .centerCrop()
            .into(target)
    }
}
