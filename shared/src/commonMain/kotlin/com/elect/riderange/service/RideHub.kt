package com.elect.riderange.service

import com.elect.riderange.Services
import com.elect.riderange.core.Units
import com.elect.riderange.data.AppSettings
import com.elect.riderange.location.Fix
import com.elect.riderange.range.ConsumptionModel
import com.elect.riderange.range.ModelChooser
import com.elect.riderange.range.PhysicsModel
import com.elect.riderange.range.RangeEstimator
import com.elect.riderange.range.RangeResult
import com.elect.riderange.range.RideParams
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.trips.ElevationFilter
import com.elect.riderange.trips.Sample
import com.elect.riderange.trips.TripDetector
import com.elect.riderange.trips.VehicleOffDetector
import com.elect.riderange.trips.TripMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.elect.riderange.core.currentTimeMillis

data class BatteryInfo(val pct: Double, val fromScooter: Boolean)

data class RecordingState(val active: Boolean = false, val tripId: Long? = null, val startMs: Long = 0, val distanceM: Double = 0.0, val manual: Boolean = false)

/**
 * Live ride state shared by every screen and the foreground service: settings, position, speed (scooter
 * if connected, else GPS), battery (scooter, else the manual slider), the consumption model and the range
 * circles, plus automatic trip recording at 1 Hz.
 */
class RideHub(private val s: Services) {
    private val scope = s.scope
    val settings: StateFlow<AppSettings> = s.settingsState
    /** The garage's active vehicle (null only before the first vehicle is added). */
    val vehicle: StateFlow<com.elect.riderange.vehicle.Vehicle?> = s.activeVehicle
    val units: StateFlow<Units> = settings.map { Units(it.metric) }.stateIn(scope, SharingStarted.Eagerly, Units())
    /** Physics constants with the rider's mass: entered weight, or the estimate from rides once confident. */
    val paramsFlow: StateFlow<RideParams> = combine(settings, s.trips.info) { st, info ->
        val v = st.vehicle
        val est = info.mass.takeIf { info.vehicleId == v?.id }
        val mass = com.elect.riderange.range.MassEstimator.effectiveMass(st.riderLbOrDefault, st.cargoKg, est, st.useEstimatedMass, st.vehicleKg)
        v?.params(mass) ?: RideParams(massKg = mass)
    }.stateIn(scope, SharingStarted.Eagerly, RideParams())
    val params: RideParams get() = paramsFlow.value

    val model: StateFlow<ConsumptionModel> = combine(settings, s.trips.info, paramsFlow) { st, info, p ->
        if (info.vehicleId != null && info.vehicleId != st.vehicle?.id) PhysicsModel(p, st.range.defaultWhPerMi)
        else ModelChooser.choose(st.range, p, info.learned, info.measuredAvgWhPerMi, info.measuredMiles)
    }.stateIn(scope, SharingStarted.Eagerly, PhysicsModel(RideParams()))

    val fix: StateFlow<Fix?> get() = s.location.fix

    val battery: StateFlow<BatteryInfo> = combine(settings, s.scooter.state) { st, sc ->
        val p = sc.telemetry?.batteryPct
        if (sc.phase == ScooterPhase.CONNECTED && p != null) BatteryInfo(p.toDouble(), true) else BatteryInfo(st.manualBattery, false)
    }.stateIn(scope, SharingStarted.Eagerly, BatteryInfo(80.0, false))

    /** m/s: scooter speed when connected and fresh, else GPS. */
    val speed: StateFlow<Double?> = combine(s.location.fix, s.scooter.state) { f, sc ->
        val t = sc.telemetry
        if (sc.phase == ScooterPhase.CONNECTED && t?.speedMps != null && currentTimeMillis() - t.updatedMs < 3000) t.speedMps
        else f?.speedMps
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val speedFromScooter: StateFlow<Boolean> = s.scooter.state.map { it.phase == ScooterPhase.CONNECTED && it.telemetry?.speedKmh != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val range: StateFlow<RangeResult> = combine(settings, battery, model, s.trips.info) { st, b, m, info ->
        RangeEstimator(st.range).estimate(b.pct, m, info.typicalSpeed ?: params.cruiseMps, info.typicalGrade ?: 0.02)
    }.stateIn(scope, SharingStarted.Eagerly, RangeEstimator().estimate(80.0, 16.0))

    fun estimator(): RangeEstimator = RangeEstimator(settings.value.range)

    // ---- trips ----
    private val detector = TripDetector()
    private val _rec = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _rec.asStateFlow()
    private val preBuffer = ArrayDeque<Sample>()
    private var elevation = ElevationFilter()
    private var ticker: Job? = null
    private var lastSample: Sample? = null
    private val vehicleOff = VehicleOffDetector()

    fun start() {
        scope.launch {
            s.trips.recoverActive { model.value }
            s.trips.refit()
        }
        // The learned model's prior depends on the mass: refit when the weight settings change.
        scope.launch {
            settings.map { Triple(it.riderLb, it.cargoKg, it.useEstimatedMass) }.distinctUntilChanged().drop(1).collect { s.trips.refit() }
        }
        // Each vehicle has its own learned model, mass estimate and trips: refit when the active one changes.
        scope.launch {
            vehicle.map { it?.id to it?.weightKg }.distinctUntilChanged().drop(1).collect { s.trips.refit() }
        }
        ticker = scope.launch {
            while (true) {
                tick()
                delay(1000)
            }
        }
        // Keep the foreground service (iOS: background location) in step with what needs it.
        scope.launch {
            combine(recording, s.nav.navigating, s.scooter.state) { r, n, sc -> r.active || n || sc.phase == ScooterPhase.CONNECTED }
                .distinctUntilChanged()
                .collect { needed -> s.platform.keeper.update(needed) }
        }
    }

    private suspend fun tick() {
        val f = s.location.fix.value ?: return
        s.rules.onLocation(f.pos)
        val now = currentTimeMillis()
        if (now - f.timeMs > 10_000) return            // stale fix: don't log
        val t = s.scooter.state.value.takeIf { it.phase == ScooterPhase.CONNECTED }?.telemetry
        val ele = elevation.update(now, f.altitude, f.vAccuracy, s.location.baroAlt.value)
        val sample = Sample(
            t = now, lat = f.pos.lat, lon = f.pos.lon, gpsAlt = f.altitude, ele = ele,
            gpsSpeed = f.speedMps, scooterSpeed = t?.speedMps,
            voltage = t?.voltage, current = t?.current, batteryPct = t?.batteryPct?.toDouble(),
            tempC = t?.batteryTempC?.toDouble() ?: t?.scooterTempC, odometerM = t?.odometerM?.toDouble(), accuracy = f.accuracy,
        )
        val v = sample.speed ?: 0.0
        val rec = _rec.value
        if (!rec.active) {
            preBuffer.addLast(sample)
            while (preBuffer.size > 35) preBuffer.removeFirst()
            if (settings.value.autoTrips && detector.onSpeed(v, now) == TripDetector.Event.START) {
                beginTrip(manual = false)
                s.alerts.announce("Trip recording started")
            }
            return
        }
        val id = rec.tripId ?: return
        s.trips.addSample(id, sample)
        val d = lastSample?.let { TripMath.stepDistance(it, sample) } ?: 0.0
        lastSample = sample
        _rec.value = rec.copy(distanceM = rec.distanceM + d)
        s.scooter.state.value.serial?.let { serial -> s.trips.setSerial(id, serial) }
        val off = vehicleOff.onSample(sample.scooterSpeed != null || sample.voltage != null, sample.gpsSpeed, now)
        if (off || detector.onSpeed(v, now) == TripDetector.Event.STOP) {
            if (off) detector.manualStop()
            val dist = _rec.value.distanceM
            endTrip()
            s.alerts.announce("Trip saved, " + com.elect.riderange.core.Units(settings.value.metric).range(dist).replace(" mi", " miles").replace(" km", " kilometers"))
        }
    }

    private suspend fun beginTrip(manual: Boolean) {
        val pre = preBuffer.toList()
        preBuffer.clear()
        val startMs = pre.firstOrNull()?.t ?: currentTimeMillis()
        val id = s.trips.startTrip(startMs, s.scooter.state.value.serial, pre, vehicle.value?.id)
        lastSample = pre.lastOrNull()
        vehicleOff.reset()
        val dist = pre.zipWithNext().sumOf { (a, b) -> TripMath.stepDistance(a, b) }
        _rec.value = RecordingState(true, id, startMs, dist, manual)
    }

    fun manualStart() = scope.launch {
        if (_rec.value.active) return@launch
        detector.manualStart()
        beginTrip(manual = true)
    }

    fun manualStop() = scope.launch {
        detector.manualStop()
        endTrip()
    }

    private suspend fun endTrip() {
        val id = _rec.value.tripId ?: return
        _rec.value = RecordingState()
        lastSample = null
        elevation = ElevationFilter()
        s.trips.finish(id, model.value)
    }
}
