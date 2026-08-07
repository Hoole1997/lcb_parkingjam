package com.example.lcb.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.example.lcb.parking.feature.game.GameSettingsUiState
import com.example.lcb.parking.feature.game.GameSettingsView

/**
 * 独立设置 Activity。
 *
 * feature-game 只绘制页面并抛出语义事件；语言、邮件、版本信息和隐私协议全部留在应用层。
 */
class SettingsActivity : ImmersiveGameActivity() {
    private lateinit var settingsView: GameSettingsView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_settings)

        settingsView = findViewById(R.id.game_settings)
        settingsView.setHostCallbacks(
            object : GameSettingsView.HostCallbacks {
                override fun onBackRequested() = closePage()

                override fun onLanguageRequested() = showLanguagePicker()

                override fun onFeedbackRequested() = openFeedbackEmail()

                override fun onPrivacyPolicyRequested() = openPrivacyPolicy()
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = closePage()
            },
        )
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
    }

    private fun renderSettings() {
        val language = AppLanguageController.current(this)
        settingsView.render(
            GameSettingsUiState(
                languageDisplayName = getString(language.displayNameRes),
                versionDisplayName = getString(
                    R.string.settings_version_format,
                    BuildConfig.VERSION_NAME,
                ),
            ),
        )
    }

    private fun showLanguagePicker() {
        LanguageBottomSheetDialog(
            activity = this,
            selectedOption = AppLanguageController.current(this),
            onOptionSelected = { option -> AppLanguageController.apply(this, option) },
        ).show()
    }

    private fun openFeedbackEmail() {
        val subject = getString(
            R.string.settings_feedback_subject,
            getString(R.string.app_name),
            BuildConfig.VERSION_NAME,
        )
        val chooser = Intent.createChooser(
            SettingsEmailIntentFactory.create(
                recipient = FEEDBACK_RECIPIENT,
                subject = subject,
            ),
            getString(R.string.settings_feedback_chooser),
        )
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_feedback_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPrivacyPolicy() {
        if (isFinishing || isDestroyed) return
        try {
            startActivity(PrivacyPolicyIntentFactory.create(BuildConfig.PRIVACY_POLICY_URL))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_privacy_browser_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.settings_privacy_browser_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePage() {
        GameActivityNavigator.closeCurrentPage(this)
    }

    private companion object {
        const val FEEDBACK_RECIPIENT = "naznotechnology@gmail.com"
    }
}
