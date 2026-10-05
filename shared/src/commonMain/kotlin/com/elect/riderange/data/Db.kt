package com.elect.riderange.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Update
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.parking.SpotKind
import com.elect.riderange.trips.Sample
import com.elect.riderange.trips.TripStats
import kotlinx.coroutines.flow.Flow
import com.elect.riderange.core.json.JSONObject

enum class UploadState { OFF, QUEUED, UPLOADED, FAILED }

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long = 0,
    val active: Boolean = true,
    val serial: String? = null,
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val movingS: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val climbM: Double = 0.0,
    val descentM: Double = 0.0,
    val whUsed: Double? = null,
    val regenWh: Double? = null,
    val energySource: String? = null,
    val batteryStart: Double? = null,
    val batteryEnd: Double? = null,
    val avgTempC: Double? = null,
    val weatherTempC: Double? = null,
    val predictedWh: Double? = null,
    val modelLabel: String? = null,
    val modelCoef: String? = null,
    val demCorrected: Boolean = false,
    val uploadState: UploadState = UploadState.OFF,
    val uploadMessage: String? = null,
    val uploadPath: String? = null,
    /** Garage vehicle this trip was ridden on (added in DB v2; 1.0.x trips are assigned to the migrated Max G2). */
    val vehicleId: String? = null,
) {
    val stats: TripStats get() = TripStats(distanceM, durationS, movingS, avgSpeed, maxSpeed, climbM, descentM, whUsed, regenWh,
        energySource, batteryStart, batteryEnd, avgTempC)
    val errorPct: Double? get() = if (predictedWh != null && whUsed != null && whUsed > 1) (predictedWh - whUsed) / whUsed * 100 else null

    fun withStats(s: TripStats) = copy(distanceM = s.distanceM, durationS = s.durationS, movingS = s.movingS, avgSpeed = s.avgSpeed,
        maxSpeed = s.maxSpeed, climbM = s.climbM, descentM = s.descentM, whUsed = s.whUsed, regenWh = s.regenWh,
        energySource = s.energySource, batteryStart = s.batteryStart, batteryEnd = s.batteryEnd, avgTempC = s.avgTempC)
}

@Entity(tableName = "samples", indices = [Index("tripId")])
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val gpsAlt: Double?,
    val ele: Double?,
    val gpsSpeed: Double?,
    val scooterSpeed: Double?,
    val voltage: Double?,
    val current: Double?,
    val batteryPct: Double?,
    val tempC: Double?,
    val odometerM: Double?,
    val accuracy: Double?,
) {
    fun toSample() = Sample(t, lat, lon, gpsAlt, ele, gpsSpeed, scooterSpeed, voltage, current, batteryPct, tempC, odometerM, accuracy)

    companion object {
        fun of(tripId: Long, s: Sample) = SampleEntity(0, tripId, s.t, s.lat, s.lon, s.gpsAlt, s.ele, s.gpsSpeed, s.scooterSpeed,
            s.voltage, s.current, s.batteryPct, s.tempC, s.odometerM, s.accuracy)
    }
}

@Entity(tableName = "parking", indices = [Index("cell")])
data class ParkingEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val type: String?,
    val capacity: Int?,
    val covered: Boolean?,
    val fee: String?,
    val access: String?,
    val tags: String,
    val cell: String,
) {
    fun toSpot() = ParkingSpot(id, SpotKind.valueOf(kind), lat, lon, name, type, capacity, covered, fee, access,
        try { JSONObject(tags).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (_: Exception) { emptyMap() })

    companion object {
        fun of(s: ParkingSpot, cell: String) = ParkingEntity(s.id, s.kind.name, s.lat, s.lon, s.name, s.type, s.capacity, s.covered,
            s.fee, s.access, JSONObject(s.tags as Map<*, *>).toString(), cell)
    }
}

@Entity(tableName = "parking_cells")
data class ParkingCellEntity(@PrimaryKey val key: String, val fetchedMs: Long, val layers: String)

@Dao
interface TripDao {
    @Insert suspend fun insert(t: TripEntity): Long
    @Update suspend fun update(t: TripEntity)
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun get(id: Long): TripEntity?
    @Query("SELECT * FROM trips WHERE id = :id") fun observe(id: Long): Flow<TripEntity?>
    @Query("SELECT * FROM trips WHERE active = 0 ORDER BY startMs DESC") fun finished(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE active = 0 ORDER BY startMs DESC") suspend fun finishedList(): List<TripEntity>
    @Query("SELECT * FROM trips WHERE active = 1") suspend fun activeTrips(): List<TripEntity>
    @Query("SELECT * FROM trips WHERE active = 0 AND vehicleId = :vehicleId ORDER BY startMs DESC") fun finishedFor(vehicleId: String): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE active = 0 AND vehicleId = :vehicleId ORDER BY startMs DESC") suspend fun finishedListFor(vehicleId: String): List<TripEntity>
    @Query("SELECT COUNT(*) FROM trips") suspend fun count(): Int
    @Query("UPDATE trips SET vehicleId = :vehicleId WHERE vehicleId IS NULL") suspend fun assignUnowned(vehicleId: String): Int
    @Query("SELECT * FROM trips WHERE active = 0 AND uploadState IN ('QUEUED','FAILED') ORDER BY startMs") suspend fun pendingUploads(): List<TripEntity>
    @Query("DELETE FROM trips WHERE id = :id") suspend fun delete(id: Long)

    @Insert suspend fun insertSample(s: SampleEntity)
    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY t") suspend fun samples(tripId: Long): List<SampleEntity>
    @Query("SELECT COUNT(*) FROM samples WHERE tripId = :tripId") suspend fun sampleCount(tripId: Long): Int
    @Query("DELETE FROM samples WHERE tripId = :tripId") suspend fun deleteSamples(tripId: Long)
    @Insert suspend fun insertSamples(s: List<SampleEntity>)
}

@Dao
interface ParkingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(list: List<ParkingEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCell(c: ParkingCellEntity)
    @Query("SELECT * FROM parking_cells WHERE `key` = :key") suspend fun cell(key: String): ParkingCellEntity?
    @Query("DELETE FROM parking WHERE cell = :cell") suspend fun clearCell(cell: String)
    @Query("SELECT * FROM parking WHERE lat BETWEEN :s AND :n AND lon BETWEEN :w AND :e LIMIT 2000")
    suspend fun inBox(s: Double, w: Double, n: Double, e: Double): List<ParkingEntity>
}

/**
 * Schema version 2 is unchanged since 1.1 (shared/schemas/.../2.json): Android 1.x databases open as they are.
 * The Android builder (androidMain RideDbs) keeps the 1 -> 2 migration; iPhone installs start at version 2.
 */
@Database(entities = [TripEntity::class, SampleEntity::class, ParkingEntity::class, ParkingCellEntity::class], version = 2, exportSchema = true)
@ConstructedBy(RideDbConstructor::class)
abstract class RideDb : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun parking(): ParkingDao

    companion object {
        const val FILE_NAME = "riderange.db"
    }
}

/** Generated by Room for every target. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object RideDbConstructor : RoomDatabaseConstructor<RideDb> {
    override fun initialize(): RideDb
}

/** SQL kept as constants so the migration can be checked in unit tests. */
object DbMigrations {
    const val ADD_VEHICLE_ID = "ALTER TABLE trips ADD COLUMN vehicleId TEXT"
}
