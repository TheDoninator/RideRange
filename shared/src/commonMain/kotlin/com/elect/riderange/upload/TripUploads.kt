package com.elect.riderange.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.elect.riderange.App
import com.elect.riderange.data.UploadState
import com.elect.riderange.trips.TripExport
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WorkManager queue for trip uploads: network constraint (or Wi-Fi only), exponential backoff. */
object TripUploads {
    private const val WORK = "trip-upload"

    fun schedule(context: Context, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<TripUploadWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** One pass over queued/failed trips. Returns true if something should be retried later. */
    suspend fun drain(): Boolean {
        val s = App.services
        val token = s.settings.token()
        val cfg = s.settings.current()
        // Opt-in only: no repository set up = nothing to do (there is no default repository).
        val (owner, repo) = UploadPaths.parseRepo(cfg.uploadRepo) ?: return false
        val up = GitHubUploader(s.http, owner, repo, token)
        var uploaded = 0
        var retry = false
        for (t in s.trips.pendingUploads()) {
            val rec = s.trips.record(t.id) ?: continue
            val r = up.uploadTrip(t.startMs, t.serial, TripExport.json(rec, s.trips.appVersion()), t.id.toString())
            if (r.needsToken) {
                s.trips.markUpload(t.id, UploadState.OFF, r.message, null)
                continue
            }
            s.trips.markUpload(t.id, if (r.ok) UploadState.UPLOADED else UploadState.FAILED, r.message, r.path)
            if (r.ok) uploaded++ else { retry = r.retryable; break }
        }
        if (uploaded > 0) {
            val m = cfg.vehicle?.model
            val info = s.trips.info.value
            val json = JSONObject()
                .put("schema", "riderange/model/1")
                .put("app_version", s.trips.appVersion())
                .put("formula", "Wh/mi = a + b*v^2 + c*grade_up% - d*grade_down% + e*max(0, 15C - T); v in m/s")
                .put("coefficients", m?.let { JSONArray().apply { it.coef.forEach { c -> put(c) } } } ?: JSONObject.NULL)
                .put("fit_miles", m?.miles ?: JSONObject.NULL).put("rmse_wh_per_mi", m?.rmse ?: JSONObject.NULL)
                .put("measured_miles", info.measuredMiles)
                .put("measured_avg_wh_per_mi", info.measuredAvgWhPerMi ?: JSONObject.NULL)
                .put("recent_error_pct", JSONArray().apply { info.accuracyErrors.takeLast(20).forEach { put(it) } })
            up.uploadModel(System.currentTimeMillis(), json.toString())
        }
        return retry
    }
}

class TripUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        App.init(applicationContext)
        return if (TripUploads.drain()) Result.retry() else Result.success()
    }
}
