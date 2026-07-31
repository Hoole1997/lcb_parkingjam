package com.example.lcb.parking.feature.game

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import java.util.IdentityHashMap

/**
 * 按“颜色 + 车身长度”提供 Compose 车辆图片。
 *
 * Android Bitmap 由 [ParkingArtRepository] 在后台一次性解码；这里仅创建零拷贝 ImageBitmap
 * 视图。若多个语义键复用同一资源，还会按 Bitmap 身份复用包装对象，不复制像素。
 */
@Composable
internal fun rememberParkingVehicleImagesByKey(): Map<ParkingVehicleArtKey, ImageBitmap> {
    val resources = LocalResources.current
    var artRevision by remember(resources) { mutableIntStateOf(0) }
    val onReady = remember(resources) { { artRevision += 1 } }

    DisposableEffect(resources, onReady) {
        ParkingArtRepository.prepare(resources, onReady)
        onDispose { }
    }

    return remember(resources, artRevision) {
        val imageByBitmap = IdentityHashMap<Bitmap, ImageBitmap>()
        buildMap(ParkingVehicleArtKey.all.size) {
            ParkingVehicleArtKey.all.forEach { key ->
                ParkingArtRepository.vehicleBitmap(key)?.let { bitmap ->
                    val image = imageByBitmap[bitmap] ?: bitmap.asImageBitmap().also {
                        imageByBitmap[bitmap] = it
                    }
                    put(key, image)
                }
            }
        }
    }
}
