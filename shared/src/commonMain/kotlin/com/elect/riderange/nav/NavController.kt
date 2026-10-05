package com.elect.riderange.nav

import com.elect.riderange.Services
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.Units
import com.elect.riderange.range.EnergyModel
import com.elect.riderange.routing.BatterySaver
import com.elect.riderange.routing.Route
import com.elect.riderange.routing.RouteMode
import com.elect.riderange.routing.RouteCache
import com.elect.riderange.routing.RoutePlans
import com.elect.riderange.search.Place
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.elect.riderange.core.currentTimeMillis

/** What the Route screen shows about a computed route. */
data class RouteSummary(
    val route: Route,
    val wh: Double,
    val pctUsed: Double,
    val pctOnArrival: Double,
    /** Reachable one way without using the reserve. */
    val reachable: Boolean,
    val roundTripPossible: Boolean,
    /** Battery saver: Wh saved vs the fastest route. */
    val savesWh: Double?,
    val bikeShare: Double,
)

data class PlanState(
    val destination: Place? = null,
    val mode: RouteMode = RouteMode.TRAILS,
    val loading: Boolean = false,
    val error: String? = null,
    val summary: RouteSummary? = null,
    val fromCache: Boolean = false,
)

/** Route planning (BRouter + energy model) and turn-by-turn navigation with voice and reroute. */
class NavController(private val s: Services) {
    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan.asStateFlow()
    private val _nav = MutableStateFlow<NavState?>(null)
    val nav: StateFlow<NavState?> = _nav.asStateFlow()
    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()
    private val _rerouting = MutableStateFlow(false)
    val rerouting: StateFlow<Boolean> = _rerouting.asStateFlow()

    private var navigator: Navigator? = null
    private var planJob: Job? = null
    private var navJob: Job? = null
    private val tts by lazy { s.platform.newSpeech() }

    fun setDestination(p: Place?) {
        _plan.update { it.copy(destination = p, summary = null, error = null) }
        if (p != null) compute()
    }

    fun setMode(m: RouteMode) {
        _plan.update { it.copy(mode = m) }
        if (_plan.value.destination != null) compute()
    }

    fun compute(from: LatLon? = null, quiet: Boolean = false) {
        val dest = _plan.value.destination ?: return
        val start = from ?: s.location.fix.value?.pos ?: run {
            _plan.update { it.copy(error = "Waiting for your location…") }
            return
        }
        planJob?.cancel()
        planJob = s.scope.launch {
            if (!quiet) _plan.update { it.copy(loading = true, error = null) }
            try {
                val sum = plan(start, dest.pos, _plan.value.mode)
                _plan.update { it.copy(loading = false, summary = sum, error = null, fromCache = false) }
                s.settings.saveLastRoute(RouteCache.toJson(sum.route, dest.pos.lat, dest.pos.lon, dest.name))
                navigator?.let { if (_navigating.value) navigator = Navigator(sum.route, s.ride.units.value).also { n -> n.update(start, currentTimeMillis()) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline: fall back to the last route to the same place, if there is one.
                val cached = s.settings.lastRoute()?.let { RouteCache.fromJson(it) }
                if (cached != null && com.elect.riderange.core.Geo.distance(cached.dest, dest.pos) < 100) {
                    val sum = summarize(cached.route, null)
                    _plan.update { it.copy(loading = false, summary = sum, fromCache = true, error = "Offline: showing your last saved route. (${e.message})") }
                } else {
                    _plan.update { it.copy(loading = false, error = e.message ?: "Routing failed") }
                }
            }
        }
    }

    private suspend fun plan(from: LatLon, to: LatLon, mode: RouteMode): RouteSummary {
        val model = s.ride.model.value
        val p = s.ride.params
        val est = s.ride.estimator()
        val battery = s.ride.battery.value.pct
        val scored = ArrayList<BatterySaver.Scored>()
        var lastError: Exception? = null
        val vClass = s.ride.vehicle.value?.type?.vehicleClass ?: com.elect.riderange.vehicle.VehicleClass.KICK_SCOOTER
        for (req in RoutePlans.requests(mode, vClass)) {
            try {
                val r = s.brouter.route(from, to, req)
                scored += BatterySaver.Scored(r, EnergyModel.routeWh(r.points, r.elevations, r.realTurns, model, p))
            } catch (e: Exception) {
                lastError = e
                if (mode != RouteMode.BATTERY) throw e
            }
        }
        if (scored.isEmpty()) throw lastError ?: IllegalStateException("No route")
        val choice = if (mode == RouteMode.BATTERY) BatterySaver.choose(scored)!! else null
        val best = choice?.best ?: scored.first()
        return summarize(best.route, choice?.savesWh)
    }

    private fun summarize(route: Route, savesWh: Double?): RouteSummary {
        val model = s.ride.model.value
        val p = s.ride.params
        val est = s.ride.estimator()
        val battery = s.ride.battery.value.pct
        val wh = EnergyModel.routeWh(route.points, route.elevations, route.realTurns, model, p)
        val best = BatterySaver.Scored(route, wh)
        // Round trip: back the same way (descents become climbs), roughly.
        val back = EnergyModel.routeWh(best.route.points.reversed(), best.route.elevations.reversed(), best.route.realTurns, model, p)
        return RouteSummary(
            route = best.route, wh = wh, pctUsed = est.pctFor(wh), pctOnArrival = est.pctAfter(battery, wh),
            reachable = est.reachable(battery, wh), roundTripPossible = est.reachable(battery, wh + back),
            savesWh = savesWh, bikeShare = best.route.bikeInfraShare,
        )
    }

    fun clear() {
        stop()
        planJob?.cancel()
        _plan.value = PlanState(mode = _plan.value.mode)
    }

    // ---- navigation ----
    fun start() {
        val sum = _plan.value.summary ?: return
        navigator = Navigator(sum.route, s.ride.units.value)
        _navigating.value = true
        ensureTts()
        navJob?.cancel()
        navJob = s.scope.launch {
            s.location.fix.collect { f ->
                val n = navigator ?: return@collect
                if (f == null) return@collect
                val now = currentTimeMillis()
                val st = n.update(f.pos, now, f.accuracy)
                _nav.value = st
                st.speak.forEach { speak(it) }
                if (n.shouldReroute(st, now)) reroute(f.pos)
                if (st.arrived) {
                    kotlinx.coroutines.delay(4000)
                    stop()
                }
            }
        }
    }

    private fun reroute(from: LatLon) {
        _rerouting.value = true
        speak("Rerouting.")
        compute(from, quiet = true)
        s.scope.launch {
            planJob?.join()
            _rerouting.value = false
        }
    }

    fun stop() {
        navJob?.cancel()
        navJob = null
        navigator = null
        _nav.value = null
        _navigating.value = false
        tts.stop()
    }

    private fun ensureTts() = tts.warmUp()

    fun speak(text: String) {
        if (s.ride.settings.value.voiceMuted) return
        tts.speak(text)
    }

    fun units(): Units = s.ride.units.value
}
