package com.example.lcb.app

import android.content.Intent
import android.net.Uri

/** 仅创建标准 mailto Intent；Activity 负责选择器和无邮箱客户端时的用户反馈。 */
internal object SettingsEmailIntentFactory {
    fun create(recipient: String, subject: String): Intent {
        require(recipient.isNotBlank()) { "Feedback recipient must not be blank" }
        require(subject.isNotBlank()) { "Feedback subject must not be blank" }
        // 收件人放入 mailto 主体，主题使用查询参数编码；同时设置标准 Extra，兼容不同
        // 邮箱客户端对 ACTION_SENDTO 参数的解析差异。
        val uri = Uri.parse(
            "mailto:${Uri.encode(recipient)}?subject=${Uri.encode(subject)}",
        )
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
    }
}
