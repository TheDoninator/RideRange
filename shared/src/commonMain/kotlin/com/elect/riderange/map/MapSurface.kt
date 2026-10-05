package com.elect.riderange.map

import com.elect.riderange.core.LatLon
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.range.RangeResult
import com.elect.riderange.routing.Route

/**
 * The map with RideRange's overlays (range circles, route, parking pins, rider, destination, trip track) on top of
 * OpenFreeMap tiles: MapLibre Android on Android, MapLibre iOS on iPhone. Padding and fit insets are in Android
 * pixels (as 1.x used them); the iOS side scales them to points.
 */
interface MapSurface {
    fun setRange(center: LatLon?, r: RangeResult?, label: (Double) -> String)
    fun setMe(p: LatLon?)
    fun setDestination(p: LatLon?)
    fun setRoute(r: Route?)
    fun setParking(spots: List<ParkingSpot>)
    /** Track segments, each with its own colour (speed or power). */
    fun setTrack(points: List<LatLon>, colors: List<String>)
    fun moveTo(p: LatLon, zoom: Double? = null, bearing: Double? = null, animate: Boolean = true)
    fun fit(points: List<LatLon>, paddingPx: Int, top: Int = paddingPx, bottom: Int = paddingPx)
    /** Zoom so a circle of [radiusM] around [center] fits below the top panels. */
    fun fitCircle(center: LatLon, radiusM: Double, top: Int, bottom: Int, side: Int)
    /** Screen area hidden by overlays, so "centre" means the visible part of the map. */
    fun setPadding(top: Int, bottom: Int)
    /** [south, west, north, east]. */
    fun visibleBounds(): DoubleArray
    val zoom: Double
    /** North up again (after navigation rotated the map). */
    fun resetBearing()
}
