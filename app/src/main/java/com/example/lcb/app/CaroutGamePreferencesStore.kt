package com.example.lcb.app

import android.content.Context
import androidx.core.content.edit

/**
 * 局内用户偏好的原生持久化边界。
 *
 * 这类设置不属于关卡进度，单独存储可以避免进度 JSON 升级或重置时误伤用户偏好。
 */
internal class CaroutGamePreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isSoundEnabled(): Boolean = preferences.getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_SOUND_ENABLED, enabled) }
    }

    private companion object {
        const val PREFERENCES_NAME = "carout_game_preferences"
        const val KEY_SOUND_ENABLED = "sound_enabled"
    }
}
