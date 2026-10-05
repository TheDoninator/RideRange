package com.elect.riderange.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect.riderange.Services
import com.elect.riderange.core.LatLon
import com.elect.riderange.map.MapSurface
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.search.Place
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.elect.riderange.core.currentTimeMillis
import com.elect.riderange.core.format

enum class Tab(val label: String) { RIDE("Ride"), ROUTE("Route"), PARKING("Parking"), RULES("Rules"), SCOOTER("Vehicle") }

data class SearchState(val query: String = "", val results: List<Place> = emptyList(), val loading: Boolean = false, val error: String? = null)

class MainViewModel : ViewModel() {
    val s = Services.instance

    private val _tab = MutableStateFlow(Tab.RIDE)
    val tab: StateFlow<Tab> = _tab.asStateFlow()
    /** Map follows the rider until they pan it. */
    val follow = MutableStateFlow(true)
    val selectedSpot = MutableStateFlow<ParkingSpot?>(null)
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    /** Trip detail open on the Scooter tab. */
    val tripDetail = MutableStateFlow<Long?>(null)
    val trackByPower = MutableStateFlow(false)
    /** Vehicle tab: 0 Garage, 1 Connect, 2 Trips, 3 Settings. */
    val vehicleSection = MutableStateFlow(0)
    val editingVehicle = MutableStateFlow<EditorState?>(null)

    fun addVehicle() {
        val id = kotlin.uuid.Uuid.random().toString()
        editingVehicle.value = EditorState(Vehicle.create(VehicleType.GENERIC, id, nowMs = currentTimeMillis()), isNew = true, pickingType = true)
    }

    fun editVehicle(v: Vehicle) { editingVehicle.value = EditorState(v, isNew = false, pickingType = false) }
    fun closeEditor() { editingVehicle.value = null }

    fun restartOnboarding() = viewModelScope.launch { s.settings.update { it.copy(onboarded = false) } }
    var map: MapSurface? = null
    private var parkingJob: Job? = null
    private var searchJob: Job? = null

    fun setTab(t: Tab) {
        _tab.value = t
        applyPadding()
        if (t != Tab.SCOOTER) { tripDetail.value = null; editingVehicle.value = null }
        if (t == Tab.PARKING || t == Tab.RIDE) onCameraIdle()
    }

    fun search(q: String) {
        _search.value = _search.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 3) { _search.value = SearchState(q); return }
        searchJob = viewModelScope.launch {
            delay(700)                       // debounce typing (Nominatim: max 1 request/s)
            _search.value = _search.value.copy(loading = true, error = null)
            try {
                val r = s.nominatim.search(q, s.location.fix.value?.pos)
                _search.value = _search.value.copy(results = r, loading = false, error = if (r.isEmpty()) "No places found" else null)
            } catch (e: Exception) {
                _search.value = _search.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun choose(p: Place) {
        _search.value = SearchState()
        s.nav.setDestination(p)
        follow.value = false
    }

    fun onLongPress(p: LatLon) {
        val name = "Dropped pin %.5f, %.5f".format( p.lat, p.lon)
        s.nav.setDestination(Place("Dropped pin", name.removePrefix("Dropped pin "), p))
        follow.value = false
        _tab.value = Tab.ROUTE
    }

    fun navigateTo(spot: ParkingSpot) {
        s.nav.setDestination(Place(spot.name ?: spot.typeLabel, "Bike parking", spot.pos))
        selectedSpot.value = null
        _tab.value = Tab.ROUTE
    }

    fun onParkingTap(id: String) {
        selectedSpot.value = s.parking.state.value.spots.firstOrNull { it.id == id }
    }

    /** Camera stopped: load parking for the visible area (debounced, only when zoomed in enough). */
    fun onCameraIdle() {
        val m = map ?: return
        val st = s.ride.settings.value
        val wanted = _tab.value == Tab.PARKING || (_tab.value in setOf(Tab.RIDE, Tab.ROUTE) && st.showParkingOnRide)
        if (!wanted || m.zoom < 12.0) return
        parkingJob?.cancel()
        parkingJob = viewModelScope.launch {
            delay(800)
            val b = m.visibleBounds()
            s.parking.load(b[0], b[1], b[2], b[3], st.showRepair, st.showCharging)
        }
    }

    /** Follow the rider again; on the Ride tab, zoom to show the whole one-way range circle. */
    fun recenter() {
        follow.value = true
        val p = s.location.fix.value?.pos ?: return
        if (_tab.value == Tab.RIDE) showRange() else map?.moveTo(p, zoom = 15.0, bearing = 0.0)
    }

    /** The Ride tab's panels cover the top of the map; the Parking list covers the bottom. */
    fun applyPadding() {
        val m = map ?: return
        when (_tab.value) {
            Tab.RIDE -> m.setPadding(720, 160)
            Tab.ROUTE -> m.setPadding(300, 0)
            Tab.PARKING -> m.setPadding(120, 900)
            else -> m.setPadding(0, 0)
        }
    }

    fun showRange() {
        val p = s.location.fix.value?.pos ?: return
        val m = map ?: return
        val r = s.ride.range.value.oneWayRadiusM
        if (r < 100) m.moveTo(p, zoom = 14.0, bearing = 0.0)
        else m.fitCircle(p, r, top = 780, bottom = 200, side = 40)
    }
}
