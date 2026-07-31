package net.corekit.metrics.revenue

import android.content.Context
import com.adjust.sdk.AdjustAdRevenue
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.corekit.metrics.adjust.AdjustTracker
import net.corekit.metrics.log.MetricsLogger
import net.corekit.metrics.provider.MetricsModuleProvider
import net.corekit.core.ads.RevenueAdData
import net.corekit.core.ads.RevenueAdReporter
import net.corekit.core.utils.ConfigRemoteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.io.IOException

/**
 * 收益配置项数据类
 */
data class RevenueConfigItem(
    @SerializedName("name")
    val name: String,
    @SerializedName("rate")
    val rate: Int
)

/**
 * Adjust广告收益上报器实现
 * 将广告收益数据上报到Adjust平台
 */
class AdjustRevenueReporter : RevenueAdReporter {
    
    private val gson = Gson()
    @Volatile
    private var revenueConfigs: List<RevenueConfigItem> = emptyList()

    @Volatile
    private var isConfigLoaded = false
    
    /**
     * 构造函数，异步获取Firebase Remote Config配置
     */
    init {
        loadRevenueConfig()
    }
    
    /**
     * 异步加载收益配置
     */
    private fun loadRevenueConfig() {
        configScope.launch {
            try {
                // 首先尝试从Firebase Remote Config获取
                val remoteConfigJson = ConfigRemoteManager.getString("rev_adj", "")
                
                if (remoteConfigJson != null && remoteConfigJson.isNotEmpty()) {
                    // 使用Remote Config的配置
                    revenueConfigs = gson.fromJson(remoteConfigJson, Array<RevenueConfigItem>::class.java).toList()
                    MetricsLogger.d("从Remote Config加载收益配置成功: $revenueConfigs")
                } else {
                    // 如果Remote Config没有配置，从assets文件读取
                    val assetsConfigJson = loadConfigFromAssets()
                    if (assetsConfigJson != null) {
                        revenueConfigs = gson.fromJson(assetsConfigJson, Array<RevenueConfigItem>::class.java).toList()
                        MetricsLogger.d("从assets加载收益配置成功: $revenueConfigs")
                    } else {
                        // 如果assets文件也读取失败，使用硬编码默认配置
                        revenueConfigs = getDefaultConfig()
                        MetricsLogger.d("使用硬编码默认收益配置: $revenueConfigs")
                    }
                }
                
                isConfigLoaded = true
                
            } catch (e: Exception) {
                MetricsLogger.e("加载收益配置失败", e)
                // 使用默认配置
                revenueConfigs = getDefaultConfig()
                isConfigLoaded = true
            }
        }
    }
    
    /**
     * 从assets文件读取配置
     * @return JSON字符串，读取失败返回null
     */
    private fun loadConfigFromAssets(): String? {
        return try {
            val context = MetricsModuleProvider.getApplicationContext()
            if (context != null) {
                context.assets.open("revenue_config.json").use { inputStream ->
                    inputStream.bufferedReader().use { reader ->
                        reader.readText()
                    }
                }
            } else {
                MetricsLogger.w("无法获取Context，跳过从assets读取配置")
                null
            }
        } catch (e: IOException) {
            MetricsLogger.e("从assets读取revenue_config.json失败", e)
            null
        } catch (e: Exception) {
            MetricsLogger.e("读取assets配置异常", e)
            null
        }
    }
    
    /**
     * 获取默认配置
     * @return 默认配置列表
     */
    private fun getDefaultConfig(): List<RevenueConfigItem> {
        return listOf(
            RevenueConfigItem("applovin_max_sdk", 70),
            RevenueConfigItem("ironsource_sdk", 30)
        )
    }
    
    /**
     * 根据随机数选择收益配置
     * @return 选中的配置项名称
     */
    private fun selectRevenueConfig(): String {
        if (!isConfigLoaded || revenueConfigs.isEmpty()) {
            MetricsLogger.w("收益配置未加载或为空，使用默认值")
            return "admob_sdk"
        }
        
        val randomValue = Random.nextInt(100)
        var cumulativeRate = 0
        
        for (config in revenueConfigs) {
            cumulativeRate += config.rate
            if (randomValue < cumulativeRate) {
                MetricsLogger.d("随机数: $randomValue, 选中配置: ${config.name}")
                return config.name
            }
        }
        
        // 如果没有匹配到，返回最后一个配置
        val lastConfig = revenueConfigs.last()
        MetricsLogger.d("随机数: $randomValue, 未匹配到配置，使用最后一个: ${lastConfig.name}")
        return lastConfig.name
    }

    override fun reportAdRevenue(adRevenueData: RevenueAdData) {
        try {
            if (!AdjustTracker.checkInitialized()) {
                // Reporter callbacks may arrive on the main thread; never wait for an SDK here.
                // The ad orchestration layer can retry via its durable outbox in a later milestone.
                MetricsLogger.w("Adjust SDK未初始化，跳过本次广告收益上报")
                return
            }
            
            // 根据随机数选择收益配置
            val selectedConfigName = selectRevenueConfig()
            
            // 创建Adjust广告收益对象，使用选中的配置名称
            val adjustAdRevenue = AdjustAdRevenue(selectedConfigName)
            
            // 设置收益数据
            adjustAdRevenue.setRevenue(
                adRevenueData.revenue.value,
                adRevenueData.revenue.currencyCode
            )
            
            // 设置网络类型
            adjustAdRevenue.setAdRevenueNetwork(adRevenueData.adRevenueNetwork)
            
            // 设置广告相关参数
            adjustAdRevenue.setAdRevenueUnit(adRevenueData.adRevenueUnit)
            adjustAdRevenue.setAdRevenuePlacement(adRevenueData.adRevenuePlacement)
            
            // 发送广告收益数据
            com.adjust.sdk.Adjust.trackAdRevenue(adjustAdRevenue)
            
            MetricsLogger.d("广告收益数据已上报到Adjust: $adRevenueData, 使用配置: $selectedConfigName")
            
        } catch (e: Exception) {
            MetricsLogger.e("上报广告收益数据到Adjust失败", e)
        }
    }

    private companion object {
        // A single process scope prevents one unmanaged thread pool/job per reporter instance.
        val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
