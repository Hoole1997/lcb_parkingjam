package com.example.lcb.parking.feature.game

import androidx.annotation.DrawableRes
import com.example.lcb.parking.feature.R

/**
 * 车辆语义键到 Android 资源的唯一映射。
 *
 * 短车与长车使用独立比例的素材；仓库仍会按 resourceId 去重解码，防止未来多个语义键
 * 复用同一素材时重复占用像素内存。
 */
internal object ParkingVehicleArtResources {

    @DrawableRes
    fun resourceIdFor(key: ParkingVehicleArtKey): Int {
        val shortResourceId = shortResourceId(key.variant)
        return when (key.length) {
            ParkingVehicleArtLength.SHORT -> shortResourceId
            ParkingVehicleArtLength.LONG -> longResourceId(key.variant)
        }
    }

    @DrawableRes
    private fun shortResourceId(variant: VehicleArtVariant): Int = when (variant) {
        VehicleArtVariant.CORAL -> R.drawable.parking_car_coral
        VehicleArtVariant.BLUE -> R.drawable.parking_car_blue
        VehicleArtVariant.YELLOW -> R.drawable.parking_car_yellow
        VehicleArtVariant.PURPLE -> R.drawable.parking_car_purple
        VehicleArtVariant.MINT -> R.drawable.parking_car_mint
        VehicleArtVariant.RED -> R.drawable.parking_car_red
    }

    @DrawableRes
    private fun longResourceId(variant: VehicleArtVariant): Int = when (variant) {
        VehicleArtVariant.CORAL -> R.drawable.parking_long_car_coral
        VehicleArtVariant.BLUE -> R.drawable.parking_long_car_blue
        VehicleArtVariant.YELLOW -> R.drawable.parking_long_car_yellow
        VehicleArtVariant.PURPLE -> R.drawable.parking_long_car_purple
        VehicleArtVariant.MINT -> R.drawable.parking_long_car_mint
        VehicleArtVariant.RED -> R.drawable.parking_long_car_red
    }
}
