package com.example.lcb.parking.feature.game

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.example.lcb.parking.feature.R
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程级停车场美术缓存。
 *
 * 解码只会在受限为单并发的后台 dispatcher 中执行；Canvas 侧只能读取已经发布的不可变
 * [ArtSnapshot]，因此不会在 onDraw 中触发磁盘读取、位图解码或锁等待。
 */
internal object ParkingArtRepository {

    private data class ArtSnapshot(
        val vehicles: Map<ParkingVehicleArtKey, Bitmap>,
        val boardTexture: Bitmap?,
    ) {
        companion object {
            val EMPTY = ArtSnapshot(emptyMap(), null)
        }
    }

    private enum class PrepareState {
        IDLE,
        LOADING,
        READY,
    }

    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )

    /** 仅在首次解码期间短暂持有，发布快照后会立即清空。 */
    private val pendingReadyCallbacks = ArrayList<() -> Unit>()

    @Volatile
    private var snapshot: ArtSnapshot = ArtSnapshot.EMPTY

    private var prepareState: PrepareState = PrepareState.IDLE

    /**
     * 异步准备当前内置美术。重复调用只会复用同一次解码任务；完成后回调始终在主线程执行。
     * 单个资源失败不会阻断其他资源发布，View 可对返回 null 的资源继续使用色块 fallback。
     */
    fun prepare(resources: Resources, onReady: () -> Unit) {
        var startDecode = false
        var notifyImmediately = false
        synchronized(stateLock) {
            when (prepareState) {
                PrepareState.READY -> notifyImmediately = true
                PrepareState.LOADING -> addCallbackIfAbsent(onReady)
                PrepareState.IDLE -> {
                    prepareState = PrepareState.LOADING
                    addCallbackIfAbsent(onReady)
                    startDecode = true
                }
            }
        }

        if (notifyImmediately) {
            dispatchReadyCallbacks(listOf(onReady))
        }
        if (startDecode) {
            decodeScope.launch {
                val decodedSnapshot = decodeSnapshot(resources)
                val callbacks: List<() -> Unit>
                synchronized(stateLock) {
                    // volatile 写入一次性发布完整快照，绘制线程不会看到正在构建的 Map。
                    snapshot = decodedSnapshot
                    prepareState = PrepareState.READY
                    callbacks = pendingReadyCallbacks.toList()
                    pendingReadyCallbacks.clear()
                }
                dispatchReadyCallbacks(callbacks)
            }
        }
    }

    /** 无锁热路径：未准备或对应资源解码失败时返回 null。 */
    fun vehicleBitmap(key: ParkingVehicleArtKey): Bitmap? = snapshot.vehicles[key]

    /** 使用模型携带的车身长度选图，棋盘与跨层动画共享同一选择逻辑。 */
    fun vehicleBitmap(vehicle: VehicleRenderModel): Bitmap? = vehicleBitmap(vehicle.parkingArtKey)

    /** 无锁热路径：未准备或纹理解码失败时返回 null。 */
    fun boardTextureBitmap(): Bitmap? = snapshot.boardTexture

    private fun decodeSnapshot(resources: Resources): ArtSnapshot {
        val decodedByResourceId = HashMap<Int, Bitmap>(VehicleArtVariant.entries.size)
        val attemptedResourceIds = HashSet<Int>(VehicleArtVariant.entries.size)
        val decodedVehicles = LinkedHashMap<ParkingVehicleArtKey, Bitmap>(
            ParkingVehicleArtKey.all.size,
        )
        ParkingVehicleArtKey.all.forEach { key ->
            val resourceId = ParkingVehicleArtResources.resourceIdFor(key)
            if (attemptedResourceIds.add(resourceId)) {
                decodeBitmap(resources, resourceId, hasAlpha = true)?.let { bitmap ->
                    decodedByResourceId[resourceId] = bitmap
                }
            }
            decodedByResourceId[resourceId]?.let { bitmap ->
                decodedVehicles[key] = bitmap
            }
        }

        val immutableVehicles = Collections.unmodifiableMap(
            LinkedHashMap<ParkingVehicleArtKey, Bitmap>(decodedVehicles),
        )
        return ArtSnapshot(
            vehicles = immutableVehicles,
            boardTexture = decodeBitmap(
                resources,
                R.drawable.parking_asphalt_texture,
                hasAlpha = false,
            ),
        )
    }

    private fun decodeBitmap(
        resources: Resources,
        resourceId: Int,
        hasAlpha: Boolean,
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            // 所有素材位于 drawable-nodpi，由棋盘依据格子尺寸显式缩放。
            inScaled = false
            inMutable = false
            inPreferredConfig = if (hasAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeResource(resources, resourceId, options)
        } catch (_: Resources.NotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            // 不扩大堆、不强制 GC；已成功解码的资源仍可作为部分快照发布。
            null
        }
    }

    private fun addCallbackIfAbsent(callback: () -> Unit) {
        var index = 0
        while (index < pendingReadyCallbacks.size) {
            if (pendingReadyCallbacks[index] === callback) return
            index++
        }
        pendingReadyCallbacks += callback
    }

    private fun dispatchReadyCallbacks(callbacks: List<() -> Unit>) {
        if (callbacks.isEmpty()) return
        mainHandler.post {
            var index = 0
            while (index < callbacks.size) {
                // 一个已销毁页面的回调异常不能阻断其他正在显示的页面刷新。
                runCatching(callbacks[index])
                index++
            }
        }
    }
}
