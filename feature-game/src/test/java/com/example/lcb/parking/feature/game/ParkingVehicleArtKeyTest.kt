package com.example.lcb.parking.feature.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingVehicleArtKeyTest {

    @Test
    fun `render model selects short and long art from its occupied cell length`() {
        val verticalShort = vehicle(widthCells = 1, heightCells = 2)
        val horizontalLong = vehicle(widthCells = 3, heightCells = 1)

        assertEquals(2, verticalShort.lengthCells)
        assertEquals(ParkingVehicleArtLength.SHORT, verticalShort.parkingArtKey.length)
        assertEquals(3, horizontalLong.lengthCells)
        assertEquals(ParkingVehicleArtLength.LONG, horizontalLong.parkingArtKey.length)
        assertEquals(VehicleArtVariant.MINT, horizontalLong.parkingArtKey.variant)
    }

    @Test
    fun `all colors expose one cache key for each supported length`() {
        assertEquals(
            VehicleArtVariant.entries.size * ParkingVehicleArtLength.entries.size,
            ParkingVehicleArtKey.all.size,
        )
        VehicleArtVariant.entries.forEach { variant ->
            val keys = ParkingVehicleArtKey.all.filter { key -> key.variant == variant }
            assertEquals(ParkingVehicleArtLength.entries.toSet(), keys.map { it.length }.toSet())
        }
    }

    @Test
    fun `short and long keys resolve to dedicated resources`() {
        val resolvedResourceIds = ParkingVehicleArtKey.all
            .map(ParkingVehicleArtResources::resourceIdFor)

        VehicleArtVariant.entries.forEach { variant ->
            val shortId = ParkingVehicleArtResources.resourceIdFor(
                ParkingVehicleArtKey(variant, ParkingVehicleArtLength.SHORT),
            )
            val longId = ParkingVehicleArtResources.resourceIdFor(
                ParkingVehicleArtKey(variant, ParkingVehicleArtLength.LONG),
            )
            assertTrue(shortId != longId)
        }
        assertEquals(ParkingVehicleArtKey.all.size, resolvedResourceIds.distinct().size)
        assertTrue(resolvedResourceIds.all { resourceId -> resourceId != 0 })
    }

    private fun vehicle(
        widthCells: Int,
        heightCells: Int,
    ): VehicleRenderModel = VehicleRenderModel(
        id = "vehicle_${widthCells}_$heightCells",
        row = 0,
        column = 0,
        widthCells = widthCells,
        heightCells = heightCells,
        direction = if (heightCells > widthCells) VehicleDirection.UP else VehicleDirection.RIGHT,
        artVariant = VehicleArtVariant.MINT,
    )
}
