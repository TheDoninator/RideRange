package com.elect.riderange.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.elect.riderange.Services
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.vehicle.vesc.RideAlertLimiter
import com.elect.riderange.vehicle.vesc.RideWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Duty-cycle / pushback alerts for VESC vehicles: once a second the live VESC data is checked
 * ([RideAlertLimiter]: alert when a warning starts, repeat every 10 s while it lasts, never closer than 3 s); each alert
 * vibrates and speaks unless muted (Settings or the Float panel's mute button). [active] drives the on-screen banner,
 * which shows even when muted.
 */
class RideAlerts(private val s: Services) {
    private val limiter = RideAlertLimiter()
    private val _active = MutableStateFlow<Set<RideWarning>>(emptySet())
    val active: StateFlow<Set<RideWarning>> = _active.asStateFlow()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun start() {
        s.scope.launch {
            while (true) {
                check()
                delay(1000)
            }
        }
    }

    private fun check() {
        val st = s.scooter.state.value
        val snap = st.vesc.takeIf { st.phase == ScooterPhase.CONNECTED }
        val settings = s.settingsState.value
        val now = System.currentTimeMillis()
        val act = limiter.active(snap, now, settings.dutyAlertPct / 100.0)
        _active.value = act
        val alert = limiter.next(act, now, snap?.maxDuty(now)?.let { (it * 100).roundToInt() }) ?: return
        if (!settings.rideAlerts) return
        vibrate(alert.vibration)
        speak(alert.speech)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) s.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") (s.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun speak(text: String) {
        val t = tts ?: TextToSpeech(s.context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.language = Locale.US; tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "alert") }
        }.also { tts = it }
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_ADD, null, "alert")
    }
}
