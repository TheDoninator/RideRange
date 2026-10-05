package com.elect.riderange.rules

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.elect.riderange.core.LatLon
import com.elect.riderange.search.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Android Geocoder (state/city for the Rules tab); RulesRepository turns the state name into its code. */
class AndroidGeocoder(private val context: Context) : com.elect.riderange.core.Geocoder {
    override suspend fun region(p: LatLon): Region? {
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
        return Region(addr.countryCode, null, stateName, addr.locality ?: addr.subAdminArea)
    }
}
