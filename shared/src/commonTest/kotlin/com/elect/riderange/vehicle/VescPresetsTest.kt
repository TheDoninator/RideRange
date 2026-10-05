package com.elect.riderange.vehicle

import com.elect.riderange.Fixtures
import com.elect.riderange.data.VehicleJson
import com.elect.riderange.range.PhysicsModel
import com.elect.riderange.routing.RouteMode
import com.elect.riderange.routing.RoutePlans
import com.elect.riderange.rules.Regulations
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test

/** 1.2.0: VESC e-scooter, e-bike and e-skateboard presets, their physics, routing and rules. */
class VescPresetsTest {
    private val vescTypes = listOf(VehicleType.VESC_BOARD, VehicleType.VESC_SCOOTER, VehicleType.VESC_EBIKE, VehicleType.VESC_ESKATE, VehicleType.VESC_ESKATE_DUAL)

    @Test fun everyVescPresetCanComputeSpeedAndBattery() {
        for (t in vescTypes) {
            val p = t.preset
            assertEquals(t.name, LinkKind.VESC, t.link)
            assertNotNull(t.name, p.wheelDiameterMm); assertNotNull(t.name, p.motorPolePairs)
            assertNotNull(t.name, p.cellsSeries); assertNotNull(t.name, p.gearRatio)
            assertNull(t.name, Garage.validate(Vehicle.create(t, "x")))
        }
        assertEquals(VehicleClass.KICK_SCOOTER, VehicleType.VESC_SCOOTER.vehicleClass)
        assertEquals(VehicleClass.E_BIKE, VehicleType.VESC_EBIKE.vehicleClass)
        assertEquals(VehicleClass.E_SKATEBOARD, VehicleType.VESC_ESKATE.vehicleClass)
        assertEquals(VehicleClass.E_SKATEBOARD, VehicleType.VESC_ESKATE_DUAL.vehicleClass)
        assertEquals(2, VehicleType.VESC_ESKATE_DUAL.preset.motors)
        assertEquals(1, VehicleType.VESC_ESKATE.preset.motors)
        assertEquals(2.25, VehicleType.VESC_ESKATE.preset.gearRatio!!, 0.0)          // 16:36 belt
        assertEquals(1.0, VehicleType.VESC_SCOOTER.preset.gearRatio!!, 0.0)          // hub motor
    }

    @Test fun energyParamsFollowTheVehicleShape() {
        val bike = VehicleType.VESC_EBIKE.preset
        val skate = VehicleType.VESC_ESKATE.preset
        val scooter = VehicleType.VESC_SCOOTER.preset
        val board = VehicleType.VESC_BOARD.preset
        // Bicycle tyres roll easiest, urethane skate wheels and the fat one-wheel tyre hardest.
        assertTrue(bike.crr < scooter.crr)
        assertTrue(skate.crr > scooter.crr)
        assertTrue(board.crr >= skate.crr)
        // Standing riders catch more air than a seated cyclist; skateboards regenerate more than bikes.
        assertTrue(skate.cdA > bike.cdA)
        assertTrue(skate.regenRecovery > bike.regenRecovery)
        // Same rider and speed on the flat: the e-bike needs the least energy per mile, the skateboard more than the scooter.
        fun flat(t: VehicleType) = PhysicsModel(Vehicle.create(t, "x").params(90.0), 16.0).raw(6.0, 0.0)
        assertTrue(flat(VehicleType.VESC_EBIKE) < flat(VehicleType.VESC_SCOOTER))
        assertTrue(flat(VehicleType.VESC_ESKATE) > flat(VehicleType.VESC_SCOOTER))
        // Cruise speeds stay below 85 % of top speed.
        for (t in vescTypes) Vehicle.create(t, "x").let { assertTrue(t.name, it.cruiseMps() <= it.topSpeedMph * 0.44704 * 0.85 + 1e-9) }
    }

    @Test fun gearRatioIsStoredAndValidated() {
        val v = Vehicle.create(VehicleType.VESC_ESKATE_DUAL, "d").copy(gearRatio = 2.6)
        assertEquals(2.6, VehicleJson.fromJson(VehicleJson.toJson(v))!!.gearRatio!!, 0.0)
        // Older JSON without the field gets the preset value.
        val old = VehicleJson.toJson(v).apply { remove("gear") }
        assertEquals(2.25, VehicleJson.fromJson(old)!!.gearRatio!!, 0.0)
        assertNotNull(Garage.validate(v.copy(gearRatio = 50.0)))
        assertNull(Garage.validate(v.copy(wheelDiameterMm = 83.0)))     // small skate wheels are fine for VESC
        assertNotNull(Garage.validate(v.copy(wheelDiameterMm = 30.0)))
        assertNotNull(Garage.validate(Vehicle.create(VehicleType.ONEWHEEL_XR, "o").copy(wheelDiameterMm = 90.0)))
    }

    @Test fun eSkateboardsRoutePavedOnlyAndEBikesUseBikeProfiles() {
        val es = RouteMode.entries.associateWith { RoutePlans.requests(it, VehicleClass.E_SKATEBOARD).map { r -> r.profile } }
        assertEquals(listOf(RoutePlans.ES_TRAILS), es[RouteMode.TRAILS])
        assertEquals(listOf(RoutePlans.ES_TRAFFIC), es[RouteMode.TRAFFIC])
        assertEquals(listOf("trekking"), RoutePlans.requests(RouteMode.TRAILS, VehicleClass.E_BIKE).map { it.profile })
        assertEquals(listOf("safety"), RoutePlans.requests(RouteMode.TRAFFIC, VehicleClass.E_BIKE).map { it.profile })
        assertEquals("trekking", RoutePlans.fallback(RoutePlans.ES_TRAILS))
        for (p in listOf(RoutePlans.ES_TRAILS, RoutePlans.ES_TRAFFIC)) {
            val text = Fixtures.main("assets/" + RoutePlans.asset(p)!!)
            assertTrue(p, text.contains("assign   allow_steps              = false"))
            // Every MTB trail, every rough track and unpaved paths are impassable.
            assertTrue(p, text.contains("mtb:scale=0|1|2|3|4|5|6 ) then 10000"))
            assertTrue(p, text.contains("( tracktype=grade2 ) then 10000"))
            assertTrue(p, text.contains("if isunpaved then 10000 else 3.0"))
            assertFalse(p, text.contains("if isunpaved then 1.5"))
        }
        assertTrue(Fixtures.main("assets/brouter/eskate-traffic.brf").contains("if isbike then 4 else 10000"))
    }

    @Test fun rulesTabLeadsWithTheVehicleClass() {
        val regs = Regulations.parse(Fixtures.main("assets/regulations.json"))
        assertEquals("ebike", regs.primaryFor(VehicleClass.E_BIKE))
        assertEquals("eskate", regs.primaryFor(VehicleClass.E_SKATEBOARD))
        assertEquals("boards", regs.primaryFor(VehicleClass.ONEWHEEL))
    }
}
