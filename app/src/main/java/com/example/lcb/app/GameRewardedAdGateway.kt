package com.example.lcb.app

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.common.bill.ads.ext.AdShowExt
import com.example.lcb.parking.feature.game.GameRewardedAdPlacement
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

/** 当前 Launcher/Bill 广告实现；奖励只以 SDK 的 onRewardEarned 回调为准。 */
internal object LauncherGameRewardedAdGateway : GameRewardedAdGateway {
    override fun show(
        activity: FragmentActivity,
        placement: GameRewardedAdPlacement,
        onResult: (rewardEarned: Boolean) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }

        activity.lifecycleScope.launch {
            val rewardEarned = AtomicBoolean(false)
            try {
                LauncherSdkGateway.beforeShowAd(activity)
                AdShowExt.showRewardedAd(
                    activity = activity,
                    onRewardEarned = { rewardEarned.set(true) },
                    // 道具入口明确标记为 AD，只允许真正的激励广告完成奖励，不降级成插屏。
                    competeWithInterstitial = false,
                    position = placement.adPosition,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // SDK 加载、展示或回调异常均按未获得奖励处理，绝不提前发放道具。
            }
            if (!activity.isFinishing && !activity.isDestroyed) {
                onResult(rewardEarned.get())
            }
        }
    }
}
