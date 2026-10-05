package com.elect.riderange.nav

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.Units
import com.elect.riderange.routing.Instruction
import com.elect.riderange.routing.Route
import com.elect.riderange.routing.Turn
import kotlin.math.max

data class NavState(
    val snapped: LatLon,
    /** Metres travelled along the route. */
    val alongM: Double,
    /** Distance from the route line, metres. */
    val offsetM: Double,
    val next: Instruction?,
    val distToNextM: Double,
    val following: Instruction?,
    val remainingM: Double,
    val bearing: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Sentences to speak now (may be empty). */
    val speak: List<String>,
)

/**
 * Turn-by-turn logic, independent of Android: snaps fixes to the route, finds the next manoeuvre, decides
 * when to announce it (≈500 ft, ≈150 ft, at the turn), and detects going off route (> [offRouteM] away for
 * [offRouteSeconds]) and arrival. Reroutes are rate-limited to one per [rerouteIntervalMs].
 */
class Navigator(
    val route: Route,
    private val units: Units = Units(),
    private val offRouteM: Double = 40.0,
    private val offRouteSeconds: Double = 5.0,
    private val rerouteIntervalMs: Long = 10_000,
    private val arriveM: Double = 20.0,
) {
    companion object {
        const val FAR_M = 152.4   // 500 ft
        const val NEAR_M = 45.7   // 150 ft
        const val NOW_M = 15.0
    }

    private var segIdx = 0
    private var offSinceMs: Long? = null
    private var lastRerouteMs: Long = Long.MIN_VALUE / 2
    private val spoken = HashMap<Int, Int>()    // instruction position -> highest stage spoken (1 far, 2 near, 3 now)
    private var arrivedSpoken = false
    var started = false
        private set

    private fun nearestSegment(p: LatLon): Pair<Int, Geo.Projection> {
        val pts = route.points
        fun search(range: IntRange): Pair<Int, Geo.Projection>? {
            var best: Pair<Int, Geo.Projection>? = null
            for (i in range) {
                if (i < 0 || i >= pts.size - 1) continue
                val pr = Geo.projectOnSegment(p, pts[i], pts[i + 1])
                if (best == null || pr.distanceM < best.second.distanceM) best = i to pr
            }
            return best
        }
        // Prefer staying near where we were (no jumping to a parallel part of the route), else search all.
        val local = search((segIdx - 3)..(segIdx + 80))
        if (local != null && local.second.distanceM <= offRouteM) return local
        val global = search(0 until pts.size - 1)
        return if (global != null && (local == null || global.second.distanceM < local.second.distanceM - 5)) global else local ?: (0 to Geo.projectOnSegment(p, pts[0], pts[0]))
    }

    fun update(p: LatLon, nowMs: Long, accuracyM: Double = 10.0): NavState {
        val pts = route.points
        if (pts.size < 2) {
            return NavState(p, 0.0, 0.0, null, 0.0, null, 0.0, 0.0, false, true, emptyList())
        }
        val (i, pr) = nearestSegment(p)
        if (pr.distanceM <= offRouteM) segIdx = i
        val along = route.cum[i] + (route.cum[i + 1] - route.cum[i]) * pr.t
        val remaining = max(0.0, route.cum.last() - along)
        val bearing = Geo.bearing(pts[i], pts[i + 1])

        val threshold = max(offRouteM, accuracyM * 1.5)
        val off = pr.distanceM > threshold
        offSinceMs = if (off) (offSinceMs ?: nowMs) else null
        val offRoute = off && nowMs - offSinceMs!! >= offRouteSeconds * 1000

        val nextPos = route.instructions.indexOfFirst { it.atM > along + 2 }
        val next = route.instructions.getOrNull(nextPos)
        val following = if (nextPos >= 0) route.instructions.getOrNull(nextPos + 1) else null
        val dist = next?.let { max(0.0, it.atM - along) } ?: remaining
        val arrived = remaining <= arriveM

        val speak = ArrayList<String>()
        if (!started) {
            started = true
            speak += "Starting route. ${units.spoken(route.lengthM)} to go."
        }
        if (arrived) {
            if (!arrivedSpoken) { arrivedSpoken = true; speak += "You have arrived." }
        } else if (!offRoute && next != null && next.turn != Turn.ARRIVE) {
            val stage = when {
                dist <= NOW_M -> 3
                dist <= NEAR_M -> 2
                dist <= FAR_M -> 1
                else -> 0
            }
            val done = spoken[nextPos] ?: 0
            if (stage > done) {
                spoken[nextPos] = stage
                speak += when (stage) {
                    3 -> next.text + "."
                    else -> "In ${units.spoken(dist)}, ${next.text.replaceFirstChar { it.lowercase() }}."
                }
            }
        }
        return NavState(pr.point, along, pr.distanceM, next, dist, following, remaining, bearing, offRoute, arrived, speak)
    }

    /** True when a reroute should be requested now (off route, and none in the last 10 s). */
    fun shouldReroute(state: NavState, nowMs: Long): Boolean {
        if (!state.offRoute || state.arrived) return false
        if (nowMs - lastRerouteMs < rerouteIntervalMs) return false
        lastRerouteMs = nowMs
        return true
    }
}
