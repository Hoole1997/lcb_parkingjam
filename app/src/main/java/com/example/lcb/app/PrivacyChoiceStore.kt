package com.example.lcb.app

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class PrivacyChoice {
    UNKNOWN,
    ESSENTIAL_ONLY,
    OPTIONAL_SDKS_ALLOWED,
}

/**
 * Small consent store kept outside analytics and ad SDKs.
 *
 * Reads and durable writes are dispatched away from the main thread. A failed write falls back to
 * essential-only mode, so a storage error can never accidentally authorize network SDK startup.
 */
internal class PrivacyChoiceStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    suspend fun read(): PrivacyChoice = withContext(ioDispatcher) {
        preferences.getString(KEY_CHOICE, null)
            ?.let { stored -> PrivacyChoice.entries.firstOrNull { it.name == stored } }
            ?: PrivacyChoice.UNKNOWN
    }

    suspend fun write(choice: PrivacyChoice): Boolean = withContext(ioDispatcher) {
        preferences.edit().putString(KEY_CHOICE, choice.name).commit()
    }

    private companion object {
        const val FILE_NAME = "privacy_choice_v1"
        const val KEY_CHOICE = "choice"
    }
}
