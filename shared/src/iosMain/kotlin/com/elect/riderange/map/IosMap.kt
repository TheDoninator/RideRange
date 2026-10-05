package com.elect.riderange.map

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.parking.SpotKind
import com.elect.riderange.range.RangeResult
import com.elect.riderange.routing.Route
import platform.UIKit.UIView

/**
 * The MapLibre iOS map, implemented in Swift (iosApp/iosApp/MapLibreMap.swift) because MapLibre iOS is a Swift
 * package. Kotlin builds every overlay as GeoJSON and the Swift side only draws the layers (styled like the Android
 * MapController) and moves the camera. Insets are in points.
 */
interface NativeMapView {
    fun view(): UIView
    /** Replaces the GeoJSON of one of the RideRange sources ("rr-range", "rr-route", ...). */
    fun setSource(sourceId: String, geoJson: String)
    /** NaN = keep the current zoom / bearing. */
    fun moveTo(lat: Double, lon: Double, zoom: Double, bearing: Double, animate: Boolean)
    fun fitBounds(south: Double, west: Double, north: Double, east: Double, top: Double, left: Double, bottom: Double, right: Double)
    fun setInsets(top: Double, bottom: Double)
    fun visibleSouth(): Double
    fun visibleWest(): Double
    fun visibleNorth(): Double
    fun visibleEast(): Double
    fun zoom(): Double
    fun resetBearing()
}

interface NativeMapListener {
    /** The style finished loading and the RideRange layers exist. */
    fun onReady()
    fun onLongPress(lat: Double, lon: Double)
    fun onParkingTap(parkingId: String)
    fun onCameraIdle()
    fun onUserGesture()
}

interface NativeMapFactory {
    fun create(styleUrl: String, lat: Double, lon: Double, zoom: Double, listener: NativeMapListener): NativeMapView
}

/** Set by MainViewController before the UI starts. */
object IosMapBridge {
    var factory: NativeMapFactory? = null
}

/** [MapSurface] on top of the Swift map: GeoJSON for the overlays, Android pixel insets scaled to points. */
class IosMapSurface(private val map: NativeMapView) : MapSurface {
    private fun pt(px: Int): Double = px / ANDROID_DENSITY

    private fun point(p: LatLon, props: String = "{}") = """{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
    private fun line(pts: List<LatLon>, props: String = "{}") =
        """{"type":"Feature","properties":$props,"geometry":{"type":"LineString","coordinates":[${pts.joinToString(",") { "[${it.lon},${it.lat}]" }}]}}"""
    private fun polygon(pts: List<LatLon>, props: String) =
        """{"type":"Feature","properties":$props,"geometry":{"type":"Polygon","coordinates":[[${pts.joinToString(",") { "[${it.lon},${it.lat}]" }}]]}}"""
    private fun collection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    private fun props(vararg kv: Pair<String, String>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString()

    override fun setRange(center: LatLon?, r: RangeResult?, label: (Double) -> String) {
        if (center == null || r == null || r.oneWayRadiusM < 1) {
            map.setSource("rr-range", collection(emptyList()))
            map.setSource("rr-range-label", collection(emptyList()))
            return
        }
        map.setSource("rr-range", collection(listOf(
            polygon(Geo.circle(center, r.oneWayRadiusM), props("kind" to "oneway")),
            polygon(Geo.circle(center, r.roundTripRadiusM), props("kind" to "round")),
        )))
        map.setSource("rr-range-label", collection(listOf(
            point(Geo.destination(center, 0.0, r.oneWayRadiusM), props("kind" to "oneway", "label" to "One-way ${label(r.oneWayRadiusM)}")),
            point(Geo.destination(center, 0.0, r.roundTripRadiusM), props("kind" to "round", "label" to "Round trip ${label(r.roundTripRadiusM)}")),
        )))
    }

    override fun setMe(p: LatLon?) = map.setSource("rr-me", collection(listOfNotNull(p?.let { point(it) })))
    override fun setDestination(p: LatLon?) = map.setSource("rr-dest", collection(listOfNotNull(p?.let { point(it) })))
    override fun setRoute(r: Route?) = map.setSource("rr-route", collection(listOfNotNull(r?.let { line(it.points) })))

    override fun setParking(spots: List<ParkingSpot>) = map.setSource("rr-parking", collection(spots.map { s ->
        point(s.pos, props("id" to s.id, "kind" to s.kind.name,
            "glyph" to when (s.kind) { SpotKind.PARKING -> "P"; SpotKind.REPAIR -> "R"; SpotKind.CHARGING -> "C" }))
    }))

    override fun setTrack(points: List<LatLon>, colors: List<String>) {
        if (points.size < 2) { map.setSource("rr-track", collection(emptyList())); return }
        map.setSource("rr-track", collection((1 until points.size).map { i ->
            line(listOf(points[i - 1], points[i]), props("color" to colors.getOrElse(i) { "#2F80ED" }))
        }))
    }

    override fun moveTo(p: LatLon, zoom: Double?, bearing: Double?, animate: Boolean) =
        map.moveTo(p.lat, p.lon, zoom ?: Double.NaN, bearing ?: Double.NaN, animate)

    override fun fit(points: List<LatLon>, paddingPx: Int, top: Int, bottom: Int) {
        if (points.size < 2) return
        map.fitBounds(points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon },
            pt(top), pt(paddingPx), pt(bottom), pt(paddingPx))
    }

    override fun fitCircle(center: LatLon, radiusM: Double, top: Int, bottom: Int, side: Int) {
        if (radiusM < 50) return
        val pts = listOf(0.0, 90.0, 180.0, 270.0).map { Geo.destination(center, it, radiusM) }
        map.fitBounds(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon }, pt(top), pt(side), pt(bottom), pt(side))
    }

    override fun setPadding(top: Int, bottom: Int) = map.setInsets(pt(top), pt(bottom))

    override fun visibleBounds(): DoubleArray = doubleArrayOf(map.visibleSouth(), map.visibleWest(), map.visibleNorth(), map.visibleEast())

    override val zoom: Double get() = map.zoom()

    override fun resetBearing() = map.resetBearing()

    companion object {
        /** RideRange's insets were tuned in Android pixels on a 420 dpi phone (2.625 px per point). */
        const val ANDROID_DENSITY = 2.625
    }
}
