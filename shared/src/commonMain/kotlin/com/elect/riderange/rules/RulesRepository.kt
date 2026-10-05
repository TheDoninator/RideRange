package com.elect.riderange.rules

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.search.NominatimClient
import com.elect.riderange.search.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RulesState(val region: Region? = null, val detecting: Boolean = false, val manualState: String? = null, val error: String? = null)

/** Detects state/city (platform geocoder, falling back to Nominatim reverse) and serves the bundled dataset. */
class RulesRepository(
    private val dataset: () -> String,
    private val geocoderImpl: com.elect.riderange.core.Geocoder,
    private val nominatim: NominatimClient,
    private val scope: CoroutineScope,
) {
    val regulations: Regulations by lazy {
        Regulations.parse(dataset())
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

    /** The platform geocoder gives the state's name; the dataset maps it to a code ("Utah" -> "UT"). */
    private suspend fun geocoder(p: LatLon): Region? {
        val r = try { geocoderImpl.region(p) } catch (_: Exception) { null } ?: return null
        if (r.stateCode != null) return r
        val name = r.stateName ?: return null
        return r.copy(stateCode = stateCodes[name.lowercase()] ?: name.takeIf { it.length == 2 }?.uppercase())
    }
}
