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
import java.util.concurrent.TimeUnit

/** WorkManager queue for trip uploads: network constraint (or Wi-Fi only), exponential backoff. */
class TripUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        App.init(applicationContext)
        return if (TripUploads.drain()) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK = "trip-upload"

        fun schedule(context: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<TripUploadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
