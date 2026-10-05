package com.elect.riderange

import android.app.Application
import android.content.Context
import com.elect.riderange.core.UrlHttp
import com.elect.riderange.data.RideDb
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.location.LocationSource
import com.elect.riderange.nav.NavController
import com.elect.riderange.parking.ParkingRepository
import com.elect.riderange.routing.BRouterClient
import com.elect.riderange.routing.RoutePlans
import com.elect.riderange.rules.RulesRepository
import com.elect.riderange.search.NominatimClient
import com.elect.riderange.service.RideHub
import com.elect.riderange.trips.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.elect.riderange.core.ServiceUrls
import com.elect.riderange.data.AppSettings
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.link.VehicleConnector
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    companion object {
        @Volatile private var s: Services? = null
        val services: Services get() = s ?: error("App not initialised")

        fun init(context: Context): Services = s ?: synchronized(this) {
            s ?: Services(context.applicationContext).also { s = it; it.start() }
        }
    }
}

/** App-wide singletons (one process, one scooter, one ride). */
class Services(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = UrlHttp()
    val settings = SettingsStore(context)
    /** Settings as state (everything reads this; the garage's active vehicle included). */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val activeVehicle: StateFlow<Vehicle?> = settingsState.map { it.vehicle }.stateIn(scope, SharingStarted.Eagerly, null)
    /** Server base URLs (configurable for a public release). */
    private val urls: ServiceUrls get() = settingsState.value.urls
    /** True once the garage migration has run and settings can be trusted (gates onboarding). */
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val location = LocationSource(context)
    val scooter = VehicleConnector(context, settings, scope, activeVehicle)
    val trips = TripRepository(context, settings, http, openMeteoBase = { urls.openMeteoUrl })
    val nominatim = NominatimClient(http, baseUrl = { urls.nominatimUrl })
    val brouter = BRouterClient(http, profileText = { name ->
        RoutePlans.asset(name)?.let { f -> try { context.assets.open(f).bufferedReader().use { it.readText() } } catch (_: Exception) { null } }
    }, baseUrl = { urls.brouterUrl })
    val parking = ParkingRepository(RideDb.get(context).parking(), http, endpoints = { urls.overpassUrls })
    val rules = RulesRepository(context, nominatim, scope)
    val ride = RideHub(this)
    val nav = NavController(this)

    fun start() {
        MapLibre.getInstance(context)
        watchConnectivity()
        scope.launch {
            // 1.0.x installs: settings, key, model and trips move into a first "Max G2" vehicle before anything reads them.
            trips.migrateToGarage()
            ready.value = true
            ride.start()
        }
    }

    /**
     * MapLibre's own connectivity check can report "offline" when a VPN without an underlying network is the
     * default (e.g. a local-only VPN), and then never loads tiles. Tell it about any validated internet network.
     */
    private fun watchConnectivity() {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val valid = java.util.concurrent.ConcurrentHashMap.newKeySet<android.net.Network>()
        val req = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        try {
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { valid += network; MapLibre.setConnected(true) }
                override fun onLost(network: android.net.Network) { valid -= network; MapLibre.setConnected(valid.isNotEmpty()) }
            })
        } catch (_: Exception) {
        }
    }
}
