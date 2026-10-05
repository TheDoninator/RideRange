package com.elect.riderange.data

import com.elect.riderange.testing.assertEquals
import com.elect.riderange.vehicle.VehicleType
import kotlin.test.Test

/** A garage exactly as RideRange 1.2.2 (Android org.json) stored it must read back unchanged in 2.0. */
class VehicleJsonCompatTest {
    private val stored = """[{"id":"g2","name":"Segway-Ninebot Max G2","type":"NINEBOT_MAX_G2","packWh":551,"usable":0.95,"reserve":10,"whPerMi":16,"detour":1.25,"weightKg":24.5,"topMph":22,"rangeMi":43,"created":1},{"id":"pint","name":"Onewheel Pint","type":"ONEWHEEL_PINT","packWh":148,"usable":0.9,"reserve":10,"whPerMi":19,"detour":1.25,"weightKg":10.4,"topMph":16,"rangeMi":7,"created":1,"wheelMm":283}]"""

    @Test fun garageFrom122ReadsBack() {
        val list = VehicleJson.listFrom(stored)
        assertEquals(listOf("g2", "pint"), list.map { it.id })
        assertEquals(VehicleType.ONEWHEEL_PINT, list[1].type)
        assertEquals(283.0, list[1].wheelDiameterMm!!, 0.0)
        assertEquals(551.0, list[0].packWh, 0.0)
        assertEquals(list, VehicleJson.listFrom(VehicleJson.listToJson(list)))
    }
}
