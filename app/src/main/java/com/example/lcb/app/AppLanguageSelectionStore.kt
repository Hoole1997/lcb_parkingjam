package com.example.lcb.app

import android.content.Context
import androidx.core.content.edit

/**
 * 只保存用户在应用内选择的模式；最终 Locale 仍全部交给 AppCompatDelegate 管理。
 *
 * Android 会从系统语言列表第二项继续匹配翻译，因此“首选语言不支持则英文”需要保留
 * FOLLOW_SYSTEM 这个业务语义，不能通过当前生效 Locale 反向推断。
 */
internal class AppLanguageSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AppLanguageOption = preferences.getString(KEY_SELECTED_OPTION, null)
        ?.let { storedName -> AppLanguageOption.entries.firstOrNull { it.name == storedName } }
        ?: AppLanguageOption.FOLLOW_SYSTEM

    fun save(option: AppLanguageOption) {
        preferences.edit { putString(KEY_SELECTED_OPTION, option.name) }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_language_selection"
        const val KEY_SELECTED_OPTION = "selected_option_v1"
    }
}
