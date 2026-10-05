package com.elect.riderange.vehicle.vesc

/** A warning worth telling the rider about while riding a VESC vehicle. Ordered by priority (first = most urgent). */
enum class RideWarning(val label: String, val speech: String, val vibration: LongArray) {
    PUSHBACK_DUTY("Pushback: duty cycle", "Pushback. Duty cycle.", longArrayOf(0, 400, 150, 400, 150, 400)),
    PUSHBACK_LOW_VOLTAGE("Pushback: low battery", "Pushback. Low battery.", longArrayOf(0, 400, 150, 400, 150, 400)),
    PUSHBACK_HIGH_VOLTAGE("Pushback: high voltage", "Pushback. High voltage.", longArrayOf(0, 400, 150, 400, 150, 400)),
    PUSHBACK_TEMP("Pushback: temperature", "Pushback. Temperature.", longArrayOf(0, 400, 150, 400, 150, 400)),
    DUTY("High duty cycle", "Duty %d percent.", longArrayOf(0, 150, 100, 150)),
}

/** One alert to play: speech text and a vibration pattern. */
data class RideAlert(val warning: RideWarning, val speech: String, val vibration: LongArray) {
    override fun equals(other: Any?) = other is RideAlert && other.warning == warning && other.speech == speech
    override fun hashCode() = warning.hashCode() * 31 + speech.hashCode()
}

/**
 * Turns VESC data into duty-cycle / pushback warnings and rate-limits the alerts (pure, unit-tested):
 *  - a warning alerts as soon as it starts, then again every [repeatMs] while it lasts;
 *  - never two alerts closer than [minGapMs] (the most urgent waiting one goes next);
 *  - the duty warning switches on at the threshold and off 5 points below it (no flapping at the edge).
 */
class RideAlertLimiter(private val repeatMs: Long = 10_000, private val minGapMs: Long = 3_000, private val hysteresis: Double = 0.05) {
    private val lastFired = HashMap<RideWarning, Long>()
    private var lastAny = Long.MIN_VALUE / 2
    private var dutyOn = false

    /** Warnings active right now. [dutyThreshold] is 0..1. */
    fun active(snapshot: VescSnapshot?, now: Long, dutyThreshold: Double): Set<RideWarning> {
        if (snapshot == null) { dutyOn = false; return emptySet() }
        val out = LinkedHashSet<RideWarning>()
        when (snapshot.freshFloat(now)?.setpointAdjust) {
            FloatPackage.Setpoint.TILTBACK_DUTY -> out += RideWarning.PUSHBACK_DUTY
            FloatPackage.Setpoint.TILTBACK_LV -> out += RideWarning.PUSHBACK_LOW_VOLTAGE
            FloatPackage.Setpoint.TILTBACK_HV -> out += RideWarning.PUSHBACK_HIGH_VOLTAGE
            FloatPackage.Setpoint.TILTBACK_TEMP -> out += RideWarning.PUSHBACK_TEMP
            else -> {}
        }
        val duty = snapshot.maxDuty(now)
        dutyOn = when {
            duty == null -> false
            duty >= dutyThreshold -> true
            duty < dutyThreshold - hysteresis -> false
            else -> dutyOn
        }
        if (dutyOn) out += RideWarning.DUTY
        return out
    }

    /** The alert to play now for [active] warnings, or null (nothing new, or too soon). Call about once a second. */
    fun next(active: Set<RideWarning>, now: Long, dutyPct: Int?): RideAlert? {
        lastFired.keys.retainAll(active)               // ended warnings alert again as soon as they come back
        if (now - lastAny < minGapMs) return null
        val due = RideWarning.entries.firstOrNull { it in active && (lastFired[it]?.let { t -> now - t >= repeatMs } ?: true) } ?: return null
        lastFired[due] = now
        lastAny = now
        val speech = if (due == RideWarning.DUTY) due.speech.format(dutyPct ?: 0) else due.speech
        return RideAlert(due, speech, due.vibration)
    }
}
