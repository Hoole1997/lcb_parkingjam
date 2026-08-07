package com.example.lcb.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal enum class AppLanguageOption(
    val languageTag: String,
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
}

/**
 * 应用语言唯一控制器。
 *
 * 生效语言只通过 AndroidX AppCompatDelegate 设置；自有存储只记录“跟随系统”业务模式，
 * 用于实现产品要求的“只看系统第一语言，不支持则英文”。
 */
internal object AppLanguageController {
    fun current(context: Context): AppLanguageOption = AppLanguageSelectionStore(context).load()

    /** 在 Activity.onCreate() 之后调用，避免违反 AppCompatDelegate 的生命周期约束。 */
    fun synchronize(context: Context) {
        applyEffectiveLanguage(context, current(context))
    }

    fun apply(context: Context, option: AppLanguageOption) {
        AppLanguageSelectionStore(context).save(option)
        applyEffectiveLanguage(context, option)
    }

    private fun applyEffectiveLanguage(context: Context, selectedOption: AppLanguageOption) {
        // 必须读取忽略应用专属 Locale 的真实系统列表；切换过中文后，Resources.getSystem()
        // 在部分系统进程内仍可能返回中文，导致“跟随系统”错误地保持上一个应用语言。
        val primarySystemLocale = LocaleManagerCompat.getSystemLocales(context)[0] ?: Locale.ENGLISH
        val effectiveOption = AppLanguagePolicy.effectiveOption(selectedOption, primarySystemLocale)
        val locales = LocaleListCompat.forLanguageTags(effectiveOption.languageTag)
        if (AppCompatDelegate.getApplicationLocales() == locales) return
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

/** 纯决策层，确保系统第二语言不会改变首选语言的英文兜底结果。 */
internal object AppLanguagePolicy {
    fun effectiveOption(
        selectedOption: AppLanguageOption,
        primarySystemLocale: Locale,
    ): AppLanguageOption = if (selectedOption == AppLanguageOption.FOLLOW_SYSTEM) {
        supportedSystemOption(primarySystemLocale) ?: AppLanguageOption.ENGLISH
    } else {
        selectedOption
    }

    private fun supportedSystemOption(locale: Locale): AppLanguageOption? = when (locale.language) {
        Locale.ENGLISH.language -> AppLanguageOption.ENGLISH
        Locale.JAPANESE.language -> AppLanguageOption.JAPANESE
        Locale.KOREAN.language -> AppLanguageOption.KOREAN
        "es" -> AppLanguageOption.SPANISH
        "fr" -> AppLanguageOption.FRENCH
        "de" -> AppLanguageOption.GERMAN
        "pt" -> if (locale.country.equals("BR", ignoreCase = true)) {
            AppLanguageOption.PORTUGUESE_BRAZIL
        } else {
            null
        }
        "zh" -> if (locale.isSimplifiedChinese()) {
            AppLanguageOption.SIMPLIFIED_CHINESE
        } else {
            null
        }
        else -> null
    }

    private fun Locale.isSimplifiedChinese(): Boolean = when {
        script.equals("Hans", ignoreCase = true) -> true
        script.equals("Hant", ignoreCase = true) -> false
        country.isEmpty() -> true
        else -> country.uppercase(Locale.ROOT) in SIMPLIFIED_CHINESE_REGIONS
    }

    private val SIMPLIFIED_CHINESE_REGIONS = setOf("CN", "SG", "MY")
}
