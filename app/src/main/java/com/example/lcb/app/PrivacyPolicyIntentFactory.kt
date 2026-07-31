package com.example.lcb.app

import android.content.Intent
import androidx.core.net.toUri

/** 集中校验并创建隐私协议浏览器 Intent，设置页不关心 URL 解析细节。 */
internal object PrivacyPolicyIntentFactory {
    fun create(url: String): Intent {
        val uri = url.trim().toUri()
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Privacy policy URL must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Privacy policy URL must include a host" }
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}
