package com.elect.riderange.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.elect.riderange.App
import com.elect.riderange.MainActivity
import com.elect.riderange.R
import com.elect.riderange.scooter.ScooterPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps GPS, the scooter connection, trip logging and voice navigation alive with the screen off.
 * Runs while a trip is recording, navigation is active, or the scooter is connected; stops itself otherwise.
 */
class RideService : LifecycleService() {
    companion object {
        const val CHANNEL = "ride"
        const val ID = 41
    }

    private var looping = false
    private var lastShown = ""

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Speed, battery and navigation while riding"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                (if (App.services.scooter.bluetoothPermitted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } else 0
        try {
            ServiceCompat.startForeground(this, ID, build("RideRange", "Starting…"), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        App.services.location.start()
        if (!looping) {
            looping = true
            lifecycleScope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val s = App.services
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val rec = s.ride.recording.value
            val navOn = s.nav.navigating.value
            val sc = s.scooter.state.value
            if (!rec.active && !navOn && sc.phase != ScooterPhase.CONNECTED) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                looping = false
                stopSelf()
                return
            }
            val u = s.ride.units.value
            val speed = s.ride.speed.value?.let { "${u.speed(it)} ${u.speedUnit}" } ?: "-"
            val batt = "%.0f%%".format(s.ride.battery.value.pct)
            val title = "$speed · $batt" + if (rec.active) " · trip ${u.range(rec.distanceM)}" else ""
            val text = s.nav.nav.value?.let { n -> n.next?.let { "${it.text} in ${u.distance(n.distToNextM)}" } ?: "Navigating" }
                ?: if (sc.phase == ScooterPhase.CONNECTED) "Connected to ${sc.name ?: "scooter"}" else "Recording trip"
            if (title + text != lastShown) {
                lastShown = title + text
                nm.notify(ID, build(title, text))
            }
            delay(2000)
        }
    }

    private fun build(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_ride)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
}
