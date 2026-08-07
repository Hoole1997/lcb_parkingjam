package com.example.lcb.app

import androidx.fragment.app.FragmentActivity
import com.example.lcb.app.utils.showRewardedAd
import com.example.lcb.parking.feature.game.GameRewardedAdPlacement
import java.util.concurrent.atomic.AtomicBoolean
import net.corekit.core.controller.AdSlotSwitchController

/**
 * 应用层的激励广告端口。
 *
 * GameActivity 只面向该接口；以后替换聚合 SDK、增加频控或接入埋点时，不需要修改
 * Web 玩法和 feature-game 桥接代码。
 */
internal fun interface GameRewardedAdGateway {
    fun show(
        activity: FragmentActivity,
        placement: GameRewardedAdPlacement,
        onResult: (rewardEarned: Boolean) -> Unit,
    )
}

/**
 * BusinessAdExt 激励广告实现。
 *
 * 广告位开关关闭或开关读取异常时直接发放奖励；开关开启时只使用统一封装方法的成功回调，
 * 不直接依赖底层广告 SDK。
 */
internal object BusinessGameRewardedAdGateway : GameRewardedAdGateway {
    override fun show(
        activity: FragmentActivity,
        placement: GameRewardedAdPlacement,
        onResult: (rewardEarned: Boolean) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }

        val resultDelivered = AtomicBoolean(false)
        fun complete(success: Boolean) {
            if (resultDelivered.compareAndSet(false, true)) onResult(success)
        }

        val adEnabled = try {
            AdSlotSwitchController.isEnabled(placement.adSlotSwitchKey)
        } catch (_: Exception) {
            // 远程开关异常按关闭处理，不能阻断玩家主动触发的道具和车位功能。
            false
        }
        if (!adEnabled) {
            complete(true)
            return
        }

        activity.showRewardedAd(position = placement.adSlotSwitchKey, call = { success -> complete(success) })
    }
}
