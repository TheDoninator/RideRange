package com.elect.riderange.map

import android.graphics.Color
import android.graphics.PointF
import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.parking.SpotKind
import com.elect.riderange.range.RangeResult
import com.elect.riderange.routing.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Map styling + overlays on top of OpenFreeMap (free vector tiles, no key). */
class MapController(val map: MapLibreMap, val style: Style) : MapSurface {
    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val ONE_WAY = "#2F80ED"
        const val ROUND_TRIP = "#16B888"
        const val ROUTE = "#7B3FE4"
        const val PARKING_LAYER = "parking-circles"
    }

    private fun src(id: String) = GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())).also { style.addSource(it) }

    private val rangeSrc = src("rr-range")
    private val rangeLabelSrc = src("rr-range-label")
    private val routeSrc = src("rr-route")
    private val parkingSrc = src("rr-parking")
    private val meSrc = src("rr-me")
    private val destSrc = src("rr-dest")
    private val trackSrc = src("rr-track")

    init {
        val labelsBelow = style.layers.firstOrNull { it.id == "highway-name-path" }?.id
        // Highlight bike infrastructure from the OpenMapTiles transportation layer.
        val bike = LineLayer("rr-bikeways", "openmaptiles").apply {
            sourceLayer = "transportation"
            minZoom = 11f
            setFilter(Expression.all(
                Expression.eq(Expression.get("class"), Expression.literal("path")),
                Expression.any(
                    Expression.eq(Expression.get("subclass"), Expression.literal("cycleway")),
                    Expression.eq(Expression.get("bicycle"), Expression.literal("designated")),
                    Expression.eq(Expression.get("bicycle"), Expression.literal("yes")),
                ),
            ))
            setProperties(
                PropertyFactory.lineColor("#12A150"),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(11, 1.2f), Expression.stop(14, 3f), Expression.stop(18, 7f))),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        }
        if (labelsBelow != null) style.addLayerBelow(bike, labelsBelow) else style.addLayer(bike)

        style.addLayer(FillLayer("rr-range-fill", "rr-range").withProperties(
            PropertyFactory.fillColor(Expression.match(Expression.get("kind"), Expression.literal("oneway"),
                Expression.color(Color.parseColor(ONE_WAY)), Expression.color(Color.parseColor(ROUND_TRIP)))),
            PropertyFactory.fillOpacity(0.10f),
        ))
        style.addLayer(LineLayer("rr-range-line", "rr-range").withProperties(
            PropertyFactory.lineColor(Expression.match(Expression.get("kind"), Expression.literal("oneway"),
                Expression.color(Color.parseColor(ONE_WAY)), Expression.color(Color.parseColor(ROUND_TRIP)))),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.2f)),
        ))
        style.addLayer(SymbolLayer("rr-range-label", "rr-range-label").withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(14f),
            PropertyFactory.textColor(Expression.match(Expression.get("kind"), Expression.literal("oneway"),
                Expression.color(Color.parseColor("#1B5FC4")), Expression.color(Color.parseColor("#0C8A63")))),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textAllowOverlap(true),
        ))
        style.addLayer(LineLayer("rr-track", "rr-track").withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer("rr-route-casing", "rr-route").withProperties(
            PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer("rr-route-line", "rr-route").withProperties(
            PropertyFactory.lineColor(ROUTE), PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(CircleLayer(PARKING_LAYER, "rr-parking").withProperties(
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 3f), Expression.stop(16, 9f))),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"),
                Expression.literal(SpotKind.REPAIR.name), Expression.color(Color.parseColor("#F2994A")),
                Expression.literal(SpotKind.CHARGING.name), Expression.color(Color.parseColor("#EB5757")),
                Expression.color(Color.parseColor("#1565C0")))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        style.addLayer(SymbolLayer("rr-parking-label", "rr-parking").withProperties(
            PropertyFactory.textField(Expression.get("glyph")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(13, 0f), Expression.stop(16, 11f))),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
        ).also { it.minZoom = 14f })
        style.addLayer(CircleLayer("rr-dest", "rr-dest").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#D32F2F"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
        ))
        style.addLayer(CircleLayer("rr-me-halo", "rr-me").withProperties(
            PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(ONE_WAY), PropertyFactory.circleOpacity(0.18f),
        ))
        style.addLayer(CircleLayer("rr-me", "rr-me").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(ONE_WAY),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
        ))
    }

    private fun pt(p: LatLon) = Point.fromLngLat(p.lon, p.lat)
    private fun ll(p: LatLon) = LatLng(p.lat, p.lon)

    override fun setRange(center: LatLon?, r: RangeResult?, label: (Double) -> String) {
        if (center == null || r == null || r.oneWayRadiusM < 1) {
            rangeSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            rangeLabelSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        fun ring(radius: Double, kind: String) = Feature.fromGeometry(Polygon.fromLngLats(listOf(Geo.circle(center, radius).map(::pt))))
            .also { it.addStringProperty("kind", kind) }
        rangeSrc.setGeoJson(FeatureCollection.fromFeatures(listOf(ring(r.oneWayRadiusM, "oneway"), ring(r.roundTripRadiusM, "round"))))
        fun lbl(radius: Double, kind: String, text: String) = Feature.fromGeometry(pt(Geo.destination(center, 0.0, radius)))
            .also { it.addStringProperty("kind", kind); it.addStringProperty("label", text) }
        rangeLabelSrc.setGeoJson(FeatureCollection.fromFeatures(listOf(
            lbl(r.oneWayRadiusM, "oneway", "One-way ${label(r.oneWayRadiusM)}"),
            lbl(r.roundTripRadiusM, "round", "Round trip ${label(r.roundTripRadiusM)}"),
        )))
    }

    override fun setMe(p: LatLon?) {
        meSrc.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(p?.let { Feature.fromGeometry(pt(it)) })))
    }

    override fun setDestination(p: LatLon?) {
        destSrc.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(p?.let { Feature.fromGeometry(pt(it)) })))
    }

    override fun setRoute(r: Route?) {
        routeSrc.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(r?.let {
            Feature.fromGeometry(LineString.fromLngLats(it.points.map(::pt)))
        })))
    }

    override fun setParking(spots: List<ParkingSpot>) {
        parkingSrc.setGeoJson(FeatureCollection.fromFeatures(spots.map { s ->
            Feature.fromGeometry(pt(s.pos)).also {
                it.addStringProperty("id", s.id)
                it.addStringProperty("kind", s.kind.name)
                it.addStringProperty("glyph", when (s.kind) { SpotKind.PARKING -> "P"; SpotKind.REPAIR -> "R"; SpotKind.CHARGING -> "C" })
            }
        }))
    }

    /** Track segments, each with its own colour (speed or power). */
    override fun setTrack(points: List<LatLon>, colors: List<String>) {
        if (points.size < 2) { trackSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val feats = (1 until points.size).map { i ->
            Feature.fromGeometry(LineString.fromLngLats(listOf(pt(points[i - 1]), pt(points[i])))).also {
                it.addStringProperty("color", colors.getOrElse(i) { "#2F80ED" })
            }
        }
        trackSrc.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    fun parkingAt(x: Float, y: Float): String? =
        map.queryRenderedFeatures(PointF(x, y), PARKING_LAYER).firstOrNull()?.getStringProperty("id")

    override fun moveTo(p: LatLon, zoom: Double?, bearing: Double?, animate: Boolean) {
        val b = CameraPosition.Builder().target(ll(p))
        zoom?.let { b.zoom(it) }
        bearing?.let { b.bearing(it) }
        val u = CameraUpdateFactory.newCameraPosition(b.build())
        if (animate) map.easeCamera(u, 600) else map.moveCamera(u)
    }

    override fun fit(points: List<LatLon>, paddingPx: Int, top: Int, bottom: Int) {
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(points.map(::ll)).build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx, top, paddingPx, bottom), 700)
    }

    /** Zoom so a circle of [radiusM] around [center] fits below the top panels. */
    override fun fitCircle(center: LatLon, radiusM: Double, top: Int, bottom: Int, side: Int) {
        if (radiusM < 50) return
        val pts = listOf(0.0, 90.0, 180.0, 270.0).map { ll(Geo.destination(center, it, radiusM)) }
        val bounds = LatLngBounds.Builder().includes(pts).build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, top, side, bottom), 700)
    }

    /** Screen area hidden by overlays, so "centre" means the visible part of the map. */
    @Suppress("DEPRECATION")
    override fun setPadding(top: Int, bottom: Int) = map.setPadding(0, top, 0, bottom)

    override fun visibleBounds(): DoubleArray {
        val b = map.projection.visibleRegion.latLngBounds
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    override val zoom: Double get() = map.cameraPosition.zoom

    override fun resetBearing() {
        if (map.cameraPosition.bearing != 0.0) map.easeCamera(CameraUpdateFactory.bearingTo(0.0))
    }
}
