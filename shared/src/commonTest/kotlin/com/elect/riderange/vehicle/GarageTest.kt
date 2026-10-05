package com.elect.riderange.vehicle

import com.elect.riderange.data.DbMigrations
import com.elect.riderange.data.VehicleJson
import com.elect.riderange.data.toLearned
import com.elect.riderange.range.EnergyModel
import com.elect.riderange.range.PhysicsModel
import com.elect.riderange.range.RangeEstimator
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test

class GarageTest {
    @Test fun presetsAreSane() {
        for (t in VehicleType.entries) {
            val p = t.preset
            assertTrue(t.name, p.packWh in 100.0..1000.0)
            assertTrue(t.name, p.weightKg in 5.0..40.0)
            assertTrue(t.name, p.usableFraction in 0.8..1.0)
            assertTrue(t.name, p.defaultWhPerMi in 10.0..30.0)
            assertNull(t.name, Garage.validate(Vehicle.create(t, "x")))
            if (t.isOnewheel) {
                assertNotNull(t.name, p.wheelDiameterMm)
                assertTrue(t.name, p.crr > VehicleType.NINEBOT_MAX_G2.preset.crr)   // fat tyre rolls harder
                assertTrue(t.name, p.cruiseMps < VehicleType.NINEBOT_MAX_G2.preset.cruiseMps)
            }
        }
        val g2 = VehicleType.NINEBOT_MAX_G2.preset
        assertEquals(551.0, g2.packWh, 0.0); assertEquals(24.5, g2.weightKg, 0.0); assertEquals(16.0, g2.defaultWhPerMi, 0.0)
        assertEquals(148.0, VehicleType.ONEWHEEL_PINT.preset.packWh, 0.0)
        assertEquals(324.0, VehicleType.ONEWHEEL_XR.preset.packWh, 0.0)
        assertEquals(525.0, VehicleType.ONEWHEEL_GT.preset.packWh, 0.0)
        assertEquals(LinkKind.VESC, VehicleType.VESC_BOARD.link)
        assertEquals(20, VehicleType.VESC_BOARD.preset.cellsSeries)
        assertEquals(LinkKind.NONE, VehicleType.GENERIC.link)
    }

    @Test fun eachVehicleHasItsOwnRangeAndPhysics() {
        val g2 = Vehicle.create(VehicleType.NINEBOT_MAX_G2, "a")
        val xr = Vehicle.create(VehicleType.ONEWHEEL_XR, "b")
        assertEquals(551.0, g2.range.packWh, 0.0)
        assertEquals(324.0, xr.range.packWh, 0.0)
        // Same rider: the board's heavier rolling resistance costs more energy per mile at the same speed.
        val pG2 = PhysicsModel(g2.params(100.0), g2.defaultWhPerMi)
        val pXr = PhysicsModel(xr.params(88.0), 16.0)
        assertTrue(pXr.raw(5.0, 0.0) / pXr.params.massKg > pG2.raw(5.0, 0.0) / pG2.params.massKg)
        // Cruise speed never above ~85 % of the vehicle's top speed.
        assertTrue(Vehicle.create(VehicleType.ONEWHEEL_PINT, "c").cruiseMps() <= 16 * 0.44704 * 0.85 + 1e-9)
        // Range circles: same battery %, the XR goes much less far than the G2.
        val rG2 = RangeEstimator(g2.range).estimate(80.0, EnergyModel.rollingTerrainWhPerMi(pG2, g2.cruiseMps(), 0.02))
        val rXr = RangeEstimator(xr.range).estimate(80.0, EnergyModel.rollingTerrainWhPerMi(pXr, xr.cruiseMps(), 0.02))
        assertTrue(rXr.oneWayRadiusM < rG2.oneWayRadiusM)
        assertEquals(rG2.oneWayRadiusM / 2, rG2.roundTripRadiusM, 1e-9)
    }

    @Test fun learnedModelsStayWithTheirVehicle() {
        val m = ModelSnapshot(doubleArrayOf(9.0, 0.05, 1.2, 0.4, 0.1), 31.0, 1.4, 5)
        val list = listOf(Vehicle.create(VehicleType.NINEBOT_MAX_G2, "a").copy(model = m), Vehicle.create(VehicleType.ONEWHEEL_XR, "b"))
        val back = VehicleJson.listFrom(VehicleJson.listToJson(list))
        assertEquals(list, back)
        assertEquals(31.0, back[0].model!!.toLearned().miles, 0.0)
        assertNull(back[1].model)
        assertEquals(9.0 + 0.05 * 25, back[0].model!!.toLearned().whPerMi(5.0, 0.0), 1e-9)
    }

    @Test fun garageListOperations() {
        val a = Vehicle.create(VehicleType.NINEBOT_MAX_G2, "a"); val b = Vehicle.create(VehicleType.ONEWHEEL_GT, "b")
        var list = Garage.upsert(Garage.upsert(emptyList(), a), b)
        assertEquals(2, list.size)
        list = Garage.upsert(list, b.copy(name = "Trail GT"))
        assertEquals("Trail GT", list[1].name)
        assertEquals(a, Garage.active(list, null))
        assertEquals(b.id, Garage.active(list, "b")!!.id)
        val (rest, active) = Garage.remove(list, "b", "b")
        assertEquals(listOf(a), rest); assertEquals("a", active)
        assertEquals("a" to "a", Garage.remove(list, "a", "b").let { it.first.single().id to it.second })
        assertEquals(null, Garage.remove(listOf(a), "a", "a").second)
    }

    @Test fun validationCatchesBadNumbers() {
        val v = Vehicle.create(VehicleType.VESC_BOARD, "v")
        assertNull(Garage.validate(v))
        assertNotNull(Garage.validate(v.copy(packWh = 5.0)))
        assertNotNull(Garage.validate(v.copy(cellsSeries = 2)))
        assertNotNull(Garage.validate(v.copy(wheelDiameterMm = null)))
    }

    // ---- 1.0.x -> 1.1 migration ----

    @Test fun upgradeBecomesAMaxG2WithEverything() {
        val model = ModelSnapshot(doubleArrayOf(8.0, 0.04, 1.1, 0.3, 0.2), 25.0, 1.1, 99)
        val legacy = LegacyData(packWh = 540.0, usableFraction = 0.93, reservePct = 12.0, whPerMi = 17.5, detourFactor = 1.3,
            scooterAddress = "AA:BB:CC:DD:EE:FF", scooterName = "MAXG2", pastedKeyHex = "00112233445566778899AABBCCDDEEFF",
            knownSerials = listOf("SERIAL1"), model = model, tripCount = 12, riderLb = 180.0)
        val r = GarageMigration.migrate(legacy, "id-1", 1000)
        val v = r.vehicles.single()
        assertEquals("id-1", v.id); assertEquals("Max G2", v.name); assertEquals(VehicleType.NINEBOT_MAX_G2, v.type)
        assertEquals(540.0, v.packWh, 0.0); assertEquals(0.93, v.usableFraction, 0.0); assertEquals(12.0, v.reservePct, 0.0)
        assertEquals(17.5, v.defaultWhPerMi, 0.0); assertEquals(1.3, v.detourFactor, 0.0)
        assertEquals("AA:BB:CC:DD:EE:FF", v.bleAddress); assertEquals("MAXG2", v.bleName)
        assertEquals("00112233445566778899AABBCCDDEEFF", v.pastedKeyHex)
        assertEquals(model, v.model)
        assertEquals(24.5, v.weightKg, 0.0)
        assertEquals("id-1", r.activeId); assertEquals("id-1", r.assignTripsTo)
        assertTrue(r.skipOnboarding)
        assertEquals(180.0, r.riderLb!!, 0.0)
        // Survives storage.
        assertEquals(v, VehicleJson.listFrom(VehicleJson.listToJson(r.vehicles)).single())
    }

    @Test fun upgradeWithOnlyTripsKeepsDefaultsAndOldRiderWeight() {
        val r = GarageMigration.migrate(LegacyData(tripCount = 3), "id-2", 0)
        val v = r.vehicles.single()
        assertEquals(551.0, v.packWh, 0.0); assertEquals(16.0, v.defaultWhPerMi, 0.0); assertEquals(10.0, v.reservePct, 0.0)
        assertEquals(GarageMigration.LEGACY_DEFAULT_RIDER_LB, r.riderLb!!, 0.0)
        assertEquals("id-2", r.assignTripsTo)
    }

    @Test fun freshInstallGetsAnEmptyGarageAndOnboarding() {
        assertFalse(GarageMigration.isUpgrade(LegacyData()))
        val r = GarageMigration.migrate(LegacyData(), "id-3", 0)
        assertTrue(r.vehicles.isEmpty()); assertNull(r.activeId); assertNull(r.assignTripsTo); assertFalse(r.skipOnboarding); assertNull(r.riderLb)
    }

    @Test fun roomMigrationAddsANullableVehicleColumn() {
        assertEquals("ALTER TABLE trips ADD COLUMN vehicleId TEXT", DbMigrations.ADD_VEHICLE_ID)
    }

    @Test fun oldStoredJsonWithMissingFieldsGetsPresetDefaults() {
        val v = VehicleJson.listFrom("""[{"id":"z","name":"Pint","type":"ONEWHEEL_PINT"}, {"id":"q","type":"NO_SUCH_TYPE"}]""").single()
        assertEquals(148.0, v.packWh, 0.0)
        assertEquals(VehicleType.ONEWHEEL_PINT.preset.wheelDiameterMm, v.wheelDiameterMm)
        assertTrue(VehicleJson.listFrom("not json").isEmpty())
    }
}
