package com.elect.riderange

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.elect.riderange.core.AppInfo
import com.elect.riderange.service.RideService
import com.elect.riderange.upload.TripUploadWorker
import com.elect.riderange.vehicle.vesc.SimulatedVescPort
import com.elect.riderange.vehicle.vesc.VescSimulator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    companion object {
        val services: Services get() = Services.instance

        fun init(context: Context): Services {
            val app = context.applicationContext
            if (BuildConfig.DEBUG) installDebugSimulator()
            return Services.init {
                AndroidPlatform(
                    app,
                    AppInfo(BuildConfig.VERSION_NAME, BuildConfig.DEBUG, "Android", ".apk"),
                    startRideService = {
                        try {
                            ContextCompat.startForegroundService(app, Intent(app, RideService::class.java))
                        } catch (_: Exception) {
                            // Background start not allowed right now; the screen keeps things running while open.
                        }
                    },
                    scheduleUpload = { wifiOnly -> TripUploadWorker.schedule(app, wifiOnly) },
                )
            }
        }

        /** The simulated VESC lives in src/debug only (release builds don't contain the class). */
        private fun installDebugSimulator() {
            VescSimulator.install(true) { dual, onBytes ->
                Class.forName("com.elect.riderange.debugsim.SimulatedVesc")
                    .getConstructor(Boolean::class.javaPrimitiveType, Function1::class.java)
                    .newInstance(dual, onBytes) as SimulatedVescPort
            }
        }
    }
}
