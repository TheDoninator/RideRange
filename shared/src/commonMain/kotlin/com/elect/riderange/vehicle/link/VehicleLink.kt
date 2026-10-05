package com.elect.riderange.vehicle.link

import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.FoundScooter
import com.elect.riderange.scooter.ScooterManager
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.scooter.ScooterState
import com.elect.riderange.vehicle.LinkKind
import com.elect.riderange.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A read-only connection to one kind of vehicle: scan, connect, and a state flow carrying live [com.elect.riderange.scooter.Telemetry]
 * (speed, battery %, voltage, current, temperatures, odometer). Implementations never write settings to a vehicle.
 */
interface VehicleLink {
    val state: StateFlow<ScooterState>
    val found: StateFlow<List<FoundScooter>>
    fun scan()
    fun connect(address: String, name: String? = null)
    fun disconnect()
}

/** For vehicles without a supported connection: always "manual mode". */
class ManualLink : VehicleLink {
    override val state: StateFlow<ScooterState> = MutableStateFlow(ScooterState(ScooterPhase.DISCONNECTED, manualMode = true,
        message = "No live connection for this vehicle: speed comes from GPS and battery from the slider on the Ride tab."))
    override val found: StateFlow<List<FoundScooter>> = MutableStateFlow(emptyList())
    override fun scan() {}
    override fun connect(address: String, name: String?) {}
    override fun disconnect() {}
}

/**
 * Picks the link implementation for the active vehicle (Ninebot, Future Motion, VESC or manual) and re-publishes
 * its state, so the rest of the app sees one connection. Switching vehicles disconnects the old link.
 */
class VehicleConnector(
    private val ble: BlePlatform,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    activeVehicle: StateFlow<Vehicle?>,
) {
    private val ninebot by lazy { ScooterManager(ble, settings, scope) }
    private val vesc by lazy { VescManager(ble, settings, scope, activeVehicle) }
    private val onewheel by lazy { ble.onewheel(settings, scope, activeVehicle) }
    private val manual = ManualLink()

    private val _state = MutableStateFlow(ScooterState())
    val state: StateFlow<ScooterState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundScooter>>(emptyList())
    val found: StateFlow<List<FoundScooter>> = _found.asStateFlow()
    private val _kind = MutableStateFlow(LinkKind.NONE)
    val kind: StateFlow<LinkKind> = _kind.asStateFlow()

    private var current: VehicleLink = manual
    private var jobs: List<Job> = emptyList()

    init {
        scope.launch {
            activeVehicle.map { it?.id to (it?.type?.link ?: LinkKind.NONE) }.distinctUntilChanged().collect { (_, k) -> select(k) }
        }
    }

    private fun select(k: LinkKind) {
        current.disconnect()
        jobs.forEach { it.cancel() }
        current = when (k) {
            LinkKind.NINEBOT -> ninebot
            LinkKind.VESC -> vesc
            LinkKind.FUTURE_MOTION -> onewheel
            LinkKind.NONE -> manual
        }
        _kind.value = k
        val c = current
        jobs = listOf(
            scope.launch { c.state.collect { _state.value = it } },
            scope.launch { c.found.collect { _found.value = it } },
        )
    }

    fun bluetoothPermitted(): Boolean = ble.permitted()
    fun scan() = current.scan()
    fun connect(address: String, name: String? = null) = current.connect(address, name)
    fun disconnect() = current.disconnect()
}
