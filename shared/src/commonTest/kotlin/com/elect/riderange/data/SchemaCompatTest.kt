package com.elect.riderange.data

import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.readTextFile
import com.elect.riderange.testing.assertEquals
import kotlin.test.Test

/**
 * The Room schema must stay exactly what 1.1-1.2.2 shipped (version 2), so Android installs open their database
 * without a migration. Room re-exports shared/schemas on every build; a changed entity changes the hash here.
 */
class SchemaCompatTest {
    @Test fun schemaIsStillVersion2From122() {
        val path = com.elect.riderange.TestPaths.RESOURCES.substringBefore("/src/commonTest") + "/schemas/com.elect.riderange.data.RideDb/2.json"
        val db = JSONObject(readTextFile(path)).getJSONObject("database")
        assertEquals(2, db.getInt("version"))
        assertEquals("60336484c666d365ef0ae25d08be65d0", db.getString("identityHash"))
        assertEquals(DbMigrations.ADD_VEHICLE_ID, "ALTER TABLE trips ADD COLUMN vehicleId TEXT")
    }
}
