package com.elect.riderange.parking

import com.elect.riderange.core.Http
import com.elect.riderange.data.ParkingCellEntity
import com.elect.riderange.data.ParkingDao
import com.elect.riderange.data.ParkingEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ParkingState(val spots: List<ParkingSpot> = emptyList(), val loading: Boolean = false, val error: String? = null)

/**
 * Bike parking from Overpass, cached in Room by ~2 km cells for a day. The map asks for the visible area
 * (debounced by the caller); cells already cached aren't fetched again.
 */
class ParkingRepository(
    private val dao: ParkingDao,
    http: Http,
    endpoints: () -> List<String> = { com.elect.riderange.core.ServiceUrls.DEFAULT_OVERPASS },
    private val client: OverpassClient = OverpassClient(http, endpoints),
) {
    private val _state = MutableStateFlow(ParkingState())
    val state: StateFlow<ParkingState> = _state.asStateFlow()
    private val lock = Mutex()

    companion object {
        const val TTL_MS = 24 * 3600 * 1000L
        fun layersKey(repair: Boolean, charging: Boolean) = "p" + (if (repair) "r" else "") + (if (charging) "c" else "")
    }

    suspend fun load(s: Double, w: Double, n: Double, e: Double, repair: Boolean, charging: Boolean) = lock.withLock {
        val layers = layersKey(repair, charging)
        val now = System.currentTimeMillis()
        val cells = Cell.cover(s, w, n, e)
        val missing = cells.filter { c ->
            val cached = dao.cell(c.key)
            cached == null || now - cached.fetchedMs > TTL_MS || !cached.layers.containsAll(layers)
        }
        _state.value = _state.value.copy(spots = query(s, w, n, e, repair, charging), loading = missing.isNotEmpty(), error = null)
        if (missing.isEmpty()) return@withLock
        // One Overpass call for the bounding box of the missing cells.
        val ms = missing.minOf { it.south }; val mw = missing.minOf { it.west }
        val mn = missing.maxOf { it.north }; val me = missing.maxOf { it.east }
        try {
            val spots = client.fetch(ms, mw, mn, me, repair, charging)
            for (c in missing) {
                dao.clearCell(c.key)
                dao.upsert(spots.filter { Cell.of(it.lat, it.lon) == c }.map { ParkingEntity.of(it, c.key) })
                dao.putCell(ParkingCellEntity(c.key, now, layers))
            }
            _state.value = ParkingState(query(s, w, n, e, repair, charging), false, null)
        } catch (ex: Exception) {
            _state.value = _state.value.copy(loading = false, error = ex.message ?: "Couldn't load bike parking")
        }
    }

    private fun String.containsAll(other: String) = other.all { it in this }

    private suspend fun query(s: Double, w: Double, n: Double, e: Double, repair: Boolean, charging: Boolean): List<ParkingSpot> =
        dao.inBox(s, w, n, e).map { it.toSpot() }.filter {
            it.kind == SpotKind.PARKING || (it.kind == SpotKind.REPAIR && repair) || (it.kind == SpotKind.CHARGING && charging)
        }
}
