package com.elect.riderange.trips

import android.content.Context
import com.elect.riderange.BuildConfig
import com.elect.riderange.core.Http
import com.elect.riderange.data.RideDb
import com.elect.riderange.data.SampleEntity
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.data.toLearned
import com.elect.riderange.vehicle.ModelSnapshot
import com.elect.riderange.data.TripEntity
import com.elect.riderange.data.UploadState
import com.elect.riderange.range.ConsumptionModel
import com.elect.riderange.range.EnergyModel
import com.elect.riderange.range.LearnedModel
import com.elect.riderange.range.ModelFitter
import com.elect.riderange.range.PhysicsModel
import com.elect.riderange.range.RideParams
import com.elect.riderange.range.Segment
import com.elect.riderange.upload.TripUploads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Learned-model facts the UI shows and the range circles use. */
data class ModelInfo(
    val learned: LearnedModel?,
    val measuredMiles: Double,
    val measuredAvgWhPerMi: Double?,
    val typicalSpeed: Double?,
    val typicalGrade: Double?,
    val accuracyErrors: List<Double>,
    /** Rolling mass estimated from rides with scooter power data (null until a trip has enough hills/accelerations). */
    val mass: com.elect.riderange.range.MassEstimate? = null,
    /** Vehicle this info belongs to. */
    val vehicleId: String? = null,
)

/** Trips in Room: finishing a trip (DEM, stats, prediction vs actual), refitting the model, queueing uploads. */
class TripRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val http: Http,
    openMeteoBase: () -> String = { com.elect.riderange.core.ServiceUrls.DEFAULT_OPEN_METEO },
) {
    private val dao = RideDb.get(context).trips()
    private val meteo = OpenMeteo(http, openMeteoBase)
    private val _info = MutableStateFlow(ModelInfo(null, 0.0, null, null, null, emptyList()))
    val info: StateFlow<ModelInfo> = _info.asStateFlow()

    val trips: Flow<List<TripEntity>> = dao.finished()
    /** One vehicle's trip history. */
    fun tripsFor(vehicleId: String): Flow<List<TripEntity>> = dao.finishedFor(vehicleId)

    /**
     * 1.0.x -> 1.1: settings become the first garage vehicle, and every trip without a vehicle is assigned to it
     * (also repairs trips left without one, e.g. by an interrupted migration).
     */
    suspend fun migrateToGarage() {
        val assign = settings.migrateToGarage(dao.count(), java.util.UUID.randomUUID().toString()) ?: settings.firstVehicleId()
        if (assign != null) dao.assignUnowned(assign)
    }
    fun observe(id: Long) = dao.observe(id)
    suspend fun samples(id: Long): List<Sample> = dao.samples(id).map { it.toSample() }

    suspend fun startTrip(startMs: Long, serial: String?, pre: List<Sample>, vehicleId: String?): Long {
        val id = dao.insert(TripEntity(startMs = startMs, serial = serial, vehicleId = vehicleId))
        if (pre.isNotEmpty()) dao.insertSamples(pre.map { SampleEntity.of(id, it) })
        return id
    }

    suspend fun addSample(tripId: Long, s: Sample) = dao.insertSample(SampleEntity.of(tripId, s))

    suspend fun setSerial(tripId: Long, serial: String) {
        dao.get(tripId)?.let { if (it.serial == null) dao.update(it.copy(serial = serial)) }
    }

    /** A trip left "active" by an app kill is finished on the next start. */
    suspend fun recoverActive(model: () -> ConsumptionModel) {
        for (t in dao.activeTrips()) finish(t.id, model())
    }

    /**
     * Ends a trip: DEM-corrects elevation (if online), computes stats, records what the model predicted for
     * this exact ride (before learning from it), then refits the model and queues the upload.
     * Trips under 200 m are discarded.
     */
    suspend fun finish(tripId: Long, modelBefore: ConsumptionModel): TripEntity? {
        val trip = dao.get(tripId) ?: return null
        var samples = dao.samples(tripId).map { it.toSample() }
        val cfg = settings.current()
        val vehicle = cfg.vehicles.firstOrNull { it.id == trip.vehicleId } ?: cfg.vehicle
        val packWh = vehicle?.packWh ?: cfg.range.packWh
        var stats = TripMath.stats(samples, packWh)
        if (stats.distanceM < 200 || samples.size < 10) {
            dao.deleteSamples(tripId); dao.delete(tripId)
            return null
        }
        var dem = false
        val idx = DemCorrection.pickIndices(samples.size, 300)
        meteo.elevations(idx.map { samples[it].pos })?.let { e ->
            samples = DemCorrection.apply(samples, idx, e)
            dao.deleteSamples(tripId)
            dao.insertSamples(samples.map { SampleEntity.of(tripId, it) })
            dem = true
            stats = TripMath.stats(samples, packWh)
        }
        val weather = meteo.temperature(samples[samples.size / 2].pos)
        val temp = stats.avgTempC ?: weather
        val predicted = EnergyModel.segmentsWh(TripMath.modelSegments(samples, tempC = temp), modelBefore)
        val coef = (modelBefore as? LearnedModel)?.coef?.joinToString(",")
        val token = settings.token()
        val done = trip.withStats(stats).copy(
            endMs = samples.last().t, active = false, weatherTempC = weather, predictedWh = predicted,
            modelLabel = modelBefore.label, modelCoef = coef, demCorrected = dem,
            uploadState = if (token.isNullOrBlank() || cfg.uploadRepo.isBlank()) UploadState.OFF else UploadState.QUEUED,
            vehicleId = trip.vehicleId ?: vehicle?.id,
        )
        dao.update(done)
        refit()
        if (done.uploadState == UploadState.QUEUED) TripUploads.schedule(context, cfg.uploadWifiOnly)
        return done
    }

    suspend fun delete(id: Long) { dao.deleteSamples(id); dao.delete(id) }

    /** Refit the active vehicle's learned model from its last 50 trips with measured power, and refresh [info]. */
    suspend fun refit() {
        val cfg = settings.current()
        val vehicle = cfg.vehicle
        if (vehicle == null) {
            _info.value = ModelInfo(null, 0.0, null, null, null, emptyList())
            return
        }
        val trips = dao.finishedListFor(vehicle.id).take(50)
        val measured = ArrayList<com.elect.riderange.range.MeasuredSegment>()
        val modelSegs = ArrayList<Segment>()
        var measuredMiles = 0.0; var measuredWh = 0.0
        val samplesById = HashMap<Long, List<Sample>>()
        for (t in trips) {
            val s = dao.samples(t.id).map { it.toSample() }
            samplesById[t.id] = s
            val temp = t.avgTempC ?: t.weatherTempC
            measured += TripMath.measuredSegments(s, tempC = temp)
            modelSegs += TripMath.modelSegments(s, tempC = temp)
            if (t.whUsed != null && t.distanceM > 0) { measuredMiles += t.distanceM / 1609.344; measuredWh += t.whUsed }
        }
        // Mass from rides (independent of the mass setting: only eff, CdA and air density enter).
        val basic = vehicle.params(cfg.enteredMassKg)
        val massTrips = trips.mapNotNull { t -> com.elect.riderange.range.MassEstimator.estimateTrip(samplesById[t.id].orEmpty(), basic) }
        val massPre = com.elect.riderange.range.MassEstimator.combine(massTrips, p = basic)
        val params = vehicle.params(com.elect.riderange.range.MassEstimator.effectiveMass(cfg.riderLbOrDefault, cfg.cargoKg, massPre,
            cfg.useEstimatedMass, vehicle.weightKg))
        val prior = PhysicsModel(params, if (measuredMiles >= 3) (measuredWh / measuredMiles).coerceIn(6.0, 60.0) else vehicle.defaultWhPerMi).priorCoefficients()
        val fit = ModelFitter.fit(measured, prior)
        fit.model?.let { settings.saveModel(vehicle.id, ModelSnapshot(it.coef, it.miles, it.rmse, System.currentTimeMillis())) }
        val learned = fit.model ?: vehicle.model?.toLearned()
        _info.value = ModelInfo(
            learned = learned,
            measuredMiles = measuredMiles,
            measuredAvgWhPerMi = if (measuredMiles > 0) measuredWh / measuredMiles else null,
            typicalSpeed = TripMath.typicalSpeed(modelSegs),
            typicalGrade = TripMath.typicalGrade(modelSegs),
            accuracyErrors = trips.reversed().mapNotNull { it.errorPct },
            mass = com.elect.riderange.range.MassEstimator.combine(massTrips, learned, basic),
            vehicleId = vehicle.id,
        )
    }

    suspend fun record(id: Long): TripRecord? {
        val t = dao.get(id) ?: return null
        val smp = samples(id)
        val cfg = settings.current()
        val mass = _info.value.mass
        return TripRecord(t.id, t.startMs, t.endMs, t.serial, t.stats, t.predictedWh, t.modelLabel,
            t.modelCoef?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.toDoubleArray(), t.weatherTempC, smp,
            tripMass = com.elect.riderange.range.MassEstimator.estimateTrip(smp),
            combinedMass = mass,
            massUsedKg = com.elect.riderange.range.MassEstimator.effectiveMass(cfg.riderLbOrDefault, cfg.cargoKg, mass, cfg.useEstimatedMass,
                (cfg.vehicles.firstOrNull { it.id == t.vehicleId } ?: cfg.vehicle)?.weightKg ?: com.elect.riderange.range.MassEstimator.SCOOTER_KG),
            vehicleName = (cfg.vehicles.firstOrNull { it.id == t.vehicleId })?.name,
            vehicleType = (cfg.vehicles.firstOrNull { it.id == t.vehicleId })?.type?.name)
    }

    suspend fun markUpload(id: Long, state: UploadState, message: String?, path: String?) {
        dao.get(id)?.let { dao.update(it.copy(uploadState = state, uploadMessage = message, uploadPath = path ?: it.uploadPath)) }
    }

    suspend fun queueAllPending(): Int {
        var n = 0
        for (t in dao.finishedList()) if (t.uploadState != UploadState.UPLOADED) {
            dao.update(t.copy(uploadState = UploadState.QUEUED, uploadMessage = null)); n++
        }
        return n
    }

    suspend fun pendingUploads() = dao.pendingUploads()

    fun appVersion(): String = BuildConfig.VERSION_NAME
}
