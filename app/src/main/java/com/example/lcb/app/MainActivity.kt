package com.example.lcb.app

import android.content.ComponentCallbacks2
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.lcb.parking.feature.game.GameHomePrimaryAction
import com.example.lcb.parking.feature.game.GameHomeView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 游戏首页 Activity。
 *
 * 本页面只持有首页 UI 和隐私入口；关卡地图、实际游戏均由独立 Activity 承载，避免三个
 * 大页面同时驻留在一个 View 树中，也让返回栈语义与用户看到的页面保持一致。
 */
class MainActivity : ImmersiveGameActivity() {

    private lateinit var gameHome: GameHomeView
    private lateinit var progressStore: CaroutProgressStore
    private lateinit var privacyChoiceStore: PrivacyChoiceStore
    private var progress = CaroutProgressSnapshot(1, emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveContentView(R.layout.activity_main_home)

        progressStore = CaroutProgressStore(applicationContext)
        privacyChoiceStore = PrivacyChoiceStore(applicationContext)
        gameHome = findViewById(R.id.game_home)
        gameHome.setOnPrimaryActionListener(::handlePrimaryAction)
        gameHome.setOnLevelSelectClickListener {
            GameActivityNavigator.openLevelSelect(this)
        }
        gameHome.setOnSettingsClickListener {
            GameActivityNavigator.openSettings(this)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = LauncherSdkGateway.returnToLauncher()
            },
        )
        resolvePrivacyChoice()
    }

    override fun onResume() {
        super.onResume()
        // 游戏页可能刚刚写入通关进度；首页每次回到前台都读取唯一存档源。
        progress = progressStore.load()
        gameHome.render(progress.toHomeUiState())
        gameHome.setHostActive(true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !hasWindowFocus()) {
            // Activity 间切换保留首页，防止返回时重建；整个应用退到后台后才按内存压力释放。
            gameHome.setHostActive(false)
        }
    }

    private fun handlePrimaryAction(action: GameHomePrimaryAction) {
        if (action == GameHomePrimaryAction.NONE) return
        GameActivityNavigator.openGame(this, progress.continueLevel)
    }

    private fun resolvePrivacyChoice() {
        lifecycleScope.launch {
            val choice = try {
                privacyChoiceStore.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PrivacyChoice.ESSENTIAL_ONLY
            }
            when (choice) {
                PrivacyChoice.UNKNOWN -> showPrivacyChoiceDialog()
                PrivacyChoice.ESSENTIAL_ONLY -> Unit
                PrivacyChoice.OPTIONAL_SDKS_ALLOWED -> OptionalSdkLifecycleGateway.enableAfterConsent()
            }
        }
    }

    private fun showPrivacyChoiceDialog() {
        if (isFinishing || isDestroyed) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_message)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_allow_optional, null)
            .setNegativeButton(R.string.privacy_essential_only, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                persistPrivacyChoice(dialog, PrivacyChoice.OPTIONAL_SDKS_ALLOWED)
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                persistPrivacyChoice(dialog, PrivacyChoice.ESSENTIAL_ONLY)
            }
        }
        dialog.show()
    }

    private fun persistPrivacyChoice(dialog: AlertDialog, choice: PrivacyChoice) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = false
        lifecycleScope.launch {
            val saved = try {
                privacyChoiceStore.write(choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (saved && choice == PrivacyChoice.OPTIONAL_SDKS_ALLOWED) {
                OptionalSdkLifecycleGateway.enableAfterConsent()
            } else if (!saved) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.privacy_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            dialog.dismiss()
        }
    }
}
