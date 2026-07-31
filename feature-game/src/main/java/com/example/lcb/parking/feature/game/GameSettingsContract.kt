package com.example.lcb.parking.feature.game

import androidx.compose.runtime.Immutable

/** 设置页只消费展示文本；语言持久化、邮件和隐私协议均由应用层负责。 */
@Immutable
data class GameSettingsUiState(
    val languageDisplayName: String,
    val versionDisplayName: String,
) {
    init {
        require(languageDisplayName.isNotBlank()) { "Language display name must not be blank" }
        require(versionDisplayName.isNotBlank()) { "Version display name must not be blank" }
    }
}

