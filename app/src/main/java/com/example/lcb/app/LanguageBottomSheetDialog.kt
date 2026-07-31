package com.example.lcb.app

import android.app.Activity
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * ImageGen 定稿对应的原生语言 BottomSheet。
 *
 * 对话框只负责展示和回传选择，不写语言偏好；实际 Locale 切换仍由
 * [AppLanguageController] 统一处理。
 */
internal class LanguageBottomSheetDialog(
    private val activity: Activity,
    private val selectedOption: AppLanguageOption,
    private val onOptionSelected: (AppLanguageOption) -> Unit,
) {
    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = BottomSheetDialog(activity)
        // 临时父容器只用于让 LayoutInflater 正确解析根节点布局参数，不进入最终 View 树。
        val inflationParent = FrameLayout(activity)
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_language_picker, inflationParent, false)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setDismissWithAnimation(true)

        bindOptions(content, dialog)
        applyBottomInset(content)
        dialog.setOnShowListener { configureWindow(dialog) }
        dialog.show()
    }

    private fun bindOptions(content: View, dialog: BottomSheetDialog) {
        val inflater = LayoutInflater.from(activity)
        val optionsContainer = content.findViewById<LinearLayout>(R.id.language_options_container)
        val optionsScroll = content.findViewById<NestedScrollView>(R.id.language_options_scroll)
        val itemHeight = activity.dp(OPTION_HEIGHT_DP)
        val itemGap = activity.dp(OPTION_GAP_DP)
        val visibleRows = minOf(AppLanguageOption.entries.size, MAX_VISIBLE_OPTION_ROWS)
        optionsScroll.layoutParams = optionsScroll.layoutParams.apply {
            height = itemHeight * visibleRows + itemGap * (visibleRows - 1).coerceAtLeast(0)
        }

        var selectedItem: View? = null
        val bindings = AppLanguageOption.entries.mapIndexed { index, option ->
            val container = inflater.inflate(
                R.layout.item_language_option,
                optionsContainer,
                false,
            )
            if (index > 0) {
                (container.layoutParams as ViewGroup.MarginLayoutParams).topMargin = itemGap
            }
            optionsContainer.addView(container)
            OptionBinding(
                option = option,
                container = container,
                label = container.findViewById(R.id.language_option_label),
                indicator = container.findViewById(R.id.language_option_indicator),
            )
        }

        bindings.forEach { binding ->
            val isSelected = binding.option == selectedOption
            binding.label.setText(binding.option.displayNameRes)
            binding.container.isSelected = isSelected
            // 每个选项使用独立底板，与视觉稿保持一致，不再包裹整组列表。
            binding.container.setBackgroundResource(
                if (isSelected) {
                    R.drawable.parking_language_option_selected
                } else {
                    R.drawable.parking_language_option_unselected
                },
            )
            // NinePatch 只负责视觉拉伸，行内间距统一由布局控制，切换选中态时不得发生位移。
            binding.container.setPadding(0, 0, 0, 0)
            binding.indicator.setImageResource(
                if (isSelected) {
                    R.drawable.parking_language_radio_selected
                } else {
                    R.drawable.parking_language_radio_unselected
                },
            )
            binding.container.contentDescription = if (isSelected) {
                activity.getString(
                    R.string.settings_language_option_selected_accessibility,
                    binding.label.text,
                )
            } else {
                binding.label.text
            }
            binding.container.setOnClickListener {
                dialog.dismiss()
                if (!isSelected) onOptionSelected(binding.option)
            }
            if (isSelected) selectedItem = binding.container
        }

        // 从靠后的语言再次打开时，当前项必须立即可见。
        selectedItem?.let { item ->
            optionsScroll.post {
                optionsScroll.scrollTo(0, (item.top - itemGap).coerceAtLeast(0))
            }
        }
    }

    private fun applyBottomInset(content: View) {
        val baseBottomPadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = baseBottomPadding + bottomInset)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWindow(dialog: BottomSheetDialog) {
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Material 会为 BottomSheet 宿主保留系统导航栏内边距；本页面自行处理 Insets，
            // 因此在宿主层清零，避免底部露出一条原页面背景。
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                insets
            }
        }
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
            // 列表存在纵向滚动时，BottomSheet 不再参与拖拽，避免列表回滚手势触发关闭。
            // 蒙层点击和系统返回键仍可正常关闭弹框。
            isDraggable = false
        }
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 蒙层只用于建立层级，避免把轻量弹框衬得过于沉重。
            window.setDimAmount(0.42f)
            window.navigationBarColor = Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightNavigationBars = true
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private data class OptionBinding(
        val option: AppLanguageOption,
        val container: View,
        val label: TextView,
        val indicator: ImageView,
    )

    private companion object {
        const val MAX_VISIBLE_OPTION_ROWS = 5
        const val OPTION_HEIGHT_DP = 48
        const val OPTION_GAP_DP = 6
    }
}

private fun Activity.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
