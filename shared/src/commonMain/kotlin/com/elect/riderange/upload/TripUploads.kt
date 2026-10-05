package com.elect.riderange.upload

import com.elect.riderange.Services
import com.elect.riderange.data.UploadState
import com.elect.riderange.trips.TripExport
import com.elect.riderange.core.json.JSONArray
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.currentTimeMillis

/** The upload pass shared by Android's WorkManager worker and the iOS in-app queue. */
object TripUploads {
    /** One pass over queued/failed trips. Returns true if something should be retried later. */
    suspend fun drain(): Boolean {
        val s = Services.instance
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
            up.uploadModel(currentTimeMillis(), json.toString())
        }
        return retry
    }
}
