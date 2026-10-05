package com.elect.riderange.rules

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.search.NominatimClient
import com.elect.riderange.search.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class RulesState(val region: Region? = null, val detecting: Boolean = false, val manualState: String? = null, val error: String? = null)

/** Detects state/city (Android Geocoder, falling back to Nominatim reverse) and serves the bundled dataset. */
class RulesRepository(private val context: Context, private val nominatim: NominatimClient, private val scope: CoroutineScope) {
    val regulations: Regulations by lazy {
        Regulations.parse(context.assets.open("regulations.json").bufferedReader().use { it.readText() })
    }
    private val _state = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = _state.asStateFlow()
    private var lastAt: LatLon? = null

    private val stateCodes: Map<String, String> by lazy { regulations.states.associate { it.name.lowercase() to it.code } }

    fun setManualState(code: String?) { _state.value = _state.value.copy(manualState = code) }

    fun onLocation(p: LatLon) {
        val last = lastAt
        if (last != null && Geo.distance(last, p) < 2000) return
        lastAt = p
        scope.launch {
            _state.value = _state.value.copy(detecting = true)
            val r = geocoder(p) ?: try { nominatim.reverse(p) } catch (e: Exception) { null }
            _state.value = _state.value.copy(region = r ?: _state.value.region, detecting = false,
                error = if (r == null) "Couldn't detect your state (offline?). Pick one below." else null)
        }
    }

    private suspend fun geocoder(p: LatLon): Region? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, Locale.US)
        val addr = try {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { c ->
                    g.getFromLocation(p.lat, p.lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(list: MutableList<android.location.Address>) { c.resume(list.firstOrNull()) }
                        override fun onError(errorMessage: String?) { c.resume(null) }
                    })
                }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                g.getFromLocation(p.lat, p.lon, 1)?.firstOrNull()
            }
        } catch (_: Exception) { null } ?: return null
        val stateName = addr.adminArea ?: return null
        val code = stateCodes[stateName.lowercase()] ?: stateName.takeIf { it.length == 2 }?.uppercase()
        return Region(addr.countryCode, code, stateName, addr.locality ?: addr.subAdminArea)
    }
}
