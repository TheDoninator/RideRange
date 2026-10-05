package com.elect.riderange.service

import com.elect.riderange.Services
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.vehicle.vesc.RideAlertLimiter
import com.elect.riderange.vehicle.vesc.RideWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.elect.riderange.core.currentTimeMillis

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
    private val tts by lazy { s.platform.newSpeech() }

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
        val now = currentTimeMillis()
        val act = limiter.active(snap, now, settings.dutyAlertPct / 100.0)
        _active.value = act
        val alert = limiter.next(act, now, snap?.maxDuty(now)?.let { (it * 100).roundToInt() }) ?: return
        if (!settings.rideAlerts) return
        vibrate(alert.vibration)
        speak(alert.speech)
    }

    private fun vibrate(pattern: LongArray) {
        try { s.platform.haptics.vibrate(pattern) } catch (_: Exception) {}
    }

    /** Short spoken status (automatic trip start/stop), so the rider knows without looking. Follows the voice-alert switch. */
    fun announce(text: String) {
        if (s.settingsState.value.rideAlerts) speak(text)
    }

    private fun speak(text: String) = tts.speak(text)
}
