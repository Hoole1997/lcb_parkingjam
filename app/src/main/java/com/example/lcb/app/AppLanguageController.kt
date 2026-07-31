package com.example.lcb.app

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** 应用语言的唯一映射入口，UI 不直接拼接 Locale 标签或操作持久化。 */
internal enum class AppLanguageOption(
    val primaryLanguageTag: String,
    @param:StringRes val displayNameRes: Int,
) {
    FOLLOW_SYSTEM("", R.string.settings_language_system),
    SIMPLIFIED_CHINESE("zh-CN", R.string.settings_language_simplified_chinese),
    ENGLISH("en", R.string.settings_language_english),
    JAPANESE("ja", R.string.settings_language_japanese),
    KOREAN("ko", R.string.settings_language_korean),
    SPANISH("es", R.string.settings_language_spanish),
    PORTUGUESE_BRAZIL("pt-BR", R.string.settings_language_portuguese_brazil),
    FRENCH("fr", R.string.settings_language_french),
    GERMAN("de", R.string.settings_language_german),
    ;

    /**
     * 非英语资源缺失时回退到英文，而不是项目默认的中文资源。
     * 这样新增语言可渐进补齐，同时不会出现日语页面混入中文的情况。
     */
    val applicationLanguageTags: String
        get() = when {
            primaryLanguageTag.isEmpty() -> ""
            primaryLanguageTag.equals("en", ignoreCase = true) -> "en"
            else -> "$primaryLanguageTag,en"
        }
}

internal object AppLanguageController {
    fun current(): AppLanguageOption {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return AppLanguageOption.entries.firstOrNull { option ->
            option.primaryLanguageTag.isNotEmpty() &&
                tags.startsWith(option.primaryLanguageTag, ignoreCase = true)
        } ?: AppLanguageOption.FOLLOW_SYSTEM
    }

    fun apply(option: AppLanguageOption) {
        val locales = if (option == AppLanguageOption.FOLLOW_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(option.applicationLanguageTags)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
