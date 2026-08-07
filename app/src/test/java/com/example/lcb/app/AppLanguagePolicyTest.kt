package com.example.lcb.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagePolicyTest {

    @Test
    fun followSystem_tibetanUsesEnglishInsteadOfSecondSystemLanguage() {
        assertEquals(
            AppLanguageOption.ENGLISH,
            AppLanguagePolicy.effectiveOption(
                AppLanguageOption.FOLLOW_SYSTEM,
                Locale.forLanguageTag("bo-US"),
            ),
        )
    }

    @Test
    fun followSystem_supportedLanguageUsesItsTranslation() {
        assertEquals(
            AppLanguageOption.SPANISH,
            AppLanguagePolicy.effectiveOption(
                AppLanguageOption.FOLLOW_SYSTEM,
                Locale.forLanguageTag("es-MX"),
            ),
        )
    }

    @Test
    fun unsupportedRegionalVariantUsesEnglish() {
        assertEquals(
            AppLanguageOption.ENGLISH,
            AppLanguagePolicy.effectiveOption(
                AppLanguageOption.FOLLOW_SYSTEM,
                Locale.forLanguageTag("zh-TW"),
            ),
        )
        assertEquals(
            AppLanguageOption.ENGLISH,
            AppLanguagePolicy.effectiveOption(
                AppLanguageOption.FOLLOW_SYSTEM,
                Locale.forLanguageTag("pt-PT"),
            ),
        )
    }

    @Test
    fun explicitLanguageIgnoresSystemLocale() {
        assertEquals(
            AppLanguageOption.JAPANESE,
            AppLanguagePolicy.effectiveOption(
                AppLanguageOption.JAPANESE,
                Locale.forLanguageTag("bo-US"),
            ),
        )
    }
}
