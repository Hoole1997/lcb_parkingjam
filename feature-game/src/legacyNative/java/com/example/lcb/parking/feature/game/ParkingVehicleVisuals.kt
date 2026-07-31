package com.example.lcb.parking.feature.game

/** Canvas 降级车辆、订单图标、停车位缩略车与领域颜色投影共用的稳定 ARGB。 */
val VehicleArtVariant.argb: Int
    get() = when (this) {
        VehicleArtVariant.CORAL -> 0xFFFF8067.toInt()
        VehicleArtVariant.BLUE -> 0xFF5BC4EF.toInt()
        VehicleArtVariant.YELLOW -> 0xFFFFC83D.toInt()
        VehicleArtVariant.PURPLE -> 0xFFBB80E3.toInt()
        VehicleArtVariant.MINT -> 0xFF83DDB0.toInt()
        VehicleArtVariant.RED -> 0xFFFF5652.toInt()
    }
