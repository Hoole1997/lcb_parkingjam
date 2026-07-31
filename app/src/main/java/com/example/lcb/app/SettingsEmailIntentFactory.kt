package com.example.lcb.app

import android.content.Intent
import android.net.Uri

/** 仅创建标准 mailto Intent；Activity 负责选择器和无邮箱客户端时的用户反馈。 */
internal object SettingsEmailIntentFactory {
    fun create(subject: String): Intent {
        require(subject.isNotBlank()) { "Feedback subject must not be blank" }
        val uri = Uri.Builder()
            .scheme("mailto")
            .appendQueryParameter("subject", subject)
            .build()
        return Intent(Intent.ACTION_SENDTO, uri)
    }
}

