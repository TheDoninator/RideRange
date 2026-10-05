package com.elect.riderange

import com.elect.riderange.core.Platform
import com.elect.riderange.core.ServiceUrls
import com.elect.riderange.data.AppSettings
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.nav.NavController
import com.elect.riderange.parking.ParkingRepository
import com.elect.riderange.routing.BRouterClient
import com.elect.riderange.routing.RoutePlans
import com.elect.riderange.rules.RulesRepository
import com.elect.riderange.search.NominatimClient
import com.elect.riderange.service.RideAlerts
import com.elect.riderange.service.RideHub
import com.elect.riderange.trips.TripRepository
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.link.VehicleConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-wide singletons (one process, one vehicle link, one ride), shared by Android and iOS. */
class Services(val platform: Platform) {
    companion object {
        private var s: Services? = null
        val instance: Services get() = s ?: error("Services not initialised")
        val isInitialised: Boolean get() = s != null

        /** Creates the services once (the Android Application / iOS app start calls this). */
        fun init(platform: () -> Platform): Services = s ?: Services(platform()).also { s = it; it.start() }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val info = platform.info
    val http = platform.http
    val settings = SettingsStore(platform.dataStore, platform.secrets)
    /** Settings as state (everything reads this; the garage's active vehicle included). */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val activeVehicle: StateFlow<Vehicle?> = settingsState.map { it.vehicle }.stateIn(scope, SharingStarted.Eagerly, null)
    /** Server base URLs (configurable for a public release). */
    private val urls: ServiceUrls get() = settingsState.value.urls
    /** True once the garage migration has run and settings can be trusted (gates onboarding). */
    val ready = MutableStateFlow(false)
    val location = platform.location
    val scooter = VehicleConnector(platform.ble, settings, scope, activeVehicle)
    val trips = TripRepository(platform.db.trips(), settings, http, platform.flags, platform.uploads, info.versionName,
        openMeteoBase = { urls.openMeteoUrl })
    val nominatim = NominatimClient(http, baseUrl = { urls.nominatimUrl })
    val brouter = BRouterClient(http, profileText = { name -> RoutePlans.asset(name)?.let { platform.readAsset(it) } },
        baseUrl = { urls.brouterUrl })
    val parking = ParkingRepository(platform.db.parking(), http, endpoints = { urls.overpassUrls })
    val rules = RulesRepository({ platform.readAsset("regulations.json") ?: error("regulations.json missing") },
        platform.geocoder, nominatim, scope)
    val ride = RideHub(this)
    val nav = NavController(this)
    val alerts = RideAlerts(this)

    fun start() {
        platform.onStart(this)
        scope.launch {
            // 1.0.x installs: settings, key, model and trips move into a first "Max G2" vehicle before anything reads them.
            trips.migrateToGarage()
            trips.recomputeStatsIfNeeded()
            ready.value = true
            ride.start()
            alerts.start()
        }
    }
}
