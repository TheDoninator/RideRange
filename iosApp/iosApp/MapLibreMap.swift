import UIKit
import MapLibre
import Shared

/// Creates the MapLibre map for the shared Compose UI (Kotlin's NativeMapFactory).
final class MapLibreFactory: NSObject, NativeMapFactory {
    func create(styleUrl: String, lat: Double, lon: Double, zoom: Double, listener: NativeMapListener) -> NativeMapView {
        return MapLibreMap(styleUrl: styleUrl, lat: lat, lon: lon, zoom: zoom, listener: listener)
    }
}

/// MapLibre iOS with RideRange's layers, styled like the Android MapController: bike paths, range circles with labels,
/// trip track, route, parking pins, destination and the rider's dot. Kotlin sends every overlay as GeoJSON.
final class MapLibreMap: NSObject, NativeMapView, MLNMapViewDelegate {
    private let map: MLNMapView
    private let listener: NativeMapListener
    private var sources: [String: MLNShapeSource] = [:]
    private var pending: [String: String] = [:]
    private var styleReady = false

    private static let oneWay = UIColor(rgb: 0x2F80ED)
    private static let roundTrip = UIColor(rgb: 0x16B888)
    private static let route = UIColor(rgb: 0x7B3FE4)
    private static let sourceIds = ["rr-range", "rr-range-label", "rr-route", "rr-parking", "rr-me", "rr-dest", "rr-track"]

    init(styleUrl: String, lat: Double, lon: Double, zoom: Double, listener: NativeMapListener) {
        self.listener = listener
        map = MLNMapView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), styleURL: URL(string: styleUrl))
        super.init()
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.delegate = self
        map.logoView.isHidden = true
        map.showsUserLocation = false
        map.isPitchEnabled = false
        // RideRange sets the insets for its panels itself.
        map.automaticallyAdjustsContentInset = false
        map.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: false)

        let long = UILongPressGestureRecognizer(target: self, action: #selector(onLongPress(_:)))
        map.addGestureRecognizer(long)
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        for r in map.gestureRecognizers ?? [] {
            if let t = r as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { tap.require(toFail: t) }
        }
        map.addGestureRecognizer(tap)
    }

    // MARK: NativeMapView

    func view() -> UIView { map }

    func setSource(sourceId: String, geoJson: String) {
        guard styleReady, let src = sources[sourceId] else { pending[sourceId] = geoJson; return }
        if let data = geoJson.data(using: .utf8), let shape = try? MLNShape(data: data, encoding: String.Encoding.utf8.rawValue) {
            src.shape = shape
        }
    }

    func moveTo(lat: Double, lon: Double, zoom: Double, bearing: Double, animate: Bool) {
        let cam = map.camera
        cam.centerCoordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        if !bearing.isNaN { cam.heading = bearing }
        if !zoom.isNaN {
            // MLNMapCamera has no zoom level; set it through the map so the altitude matches.
            map.setCenter(cam.centerCoordinate, zoomLevel: zoom, direction: bearing.isNaN ? map.direction : bearing, animated: animate)
            return
        }
        map.setCamera(cam, withDuration: animate ? 0.6 : 0, animationTimingFunction: nil)
    }

    func fitBounds(south: Double, west: Double, north: Double, east: Double, top: Double, left: Double, bottom: Double, right: Double) {
        // Not laid out yet: try again on the next run loop turns (Compose adds the view before its first layout).
        if map.bounds.height < 100 || map.bounds.width < 100 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.fitBounds(south: south, west: west, north: north, east: east, top: top, left: left, bottom: bottom, right: right)
            }
            return
        }
        let b = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: south, longitude: west),
                                    ne: CLLocationCoordinate2D(latitude: north, longitude: east))
        // The edge padding comes on top of the content inset (the panels); keep at least a 120 pt window for the shape.
        let inset = map.contentInset
        let h = map.bounds.height, w = map.bounds.width
        var t = top, bt = bottom, l = left, r = right
        let freeV = h - inset.top - inset.bottom - t - bt
        if freeV < 120 { let k = max(0, (h - inset.top - inset.bottom - 120) / max(1, t + bt)); t *= k; bt *= k }
        let freeH = w - l - r
        if freeH < 120 { let k = max(0, (w - 120) / max(1, l + r)); l *= k; r *= k }
        map.setVisibleCoordinateBounds(b, edgePadding: UIEdgeInsets(top: t, left: l, bottom: bt, right: r), animated: true, completionHandler: nil)
    }

    func setInsets(top: Double, bottom: Double) {
        map.setContentInset(UIEdgeInsets(top: top, left: 0, bottom: bottom, right: 0), animated: false, completionHandler: nil)
    }

    func visibleSouth() -> Double { map.visibleCoordinateBounds.sw.latitude }
    func visibleWest() -> Double { map.visibleCoordinateBounds.sw.longitude }
    func visibleNorth() -> Double { map.visibleCoordinateBounds.ne.latitude }
    func visibleEast() -> Double { map.visibleCoordinateBounds.ne.longitude }
    func zoom() -> Double { map.zoomLevel }
    func resetBearing() { if map.direction != 0 { map.setDirection(0, animated: true) } }

    // MARK: gestures

    @objc private func onLongPress(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        let c = map.convert(g.location(in: map), toCoordinateFrom: map)
        listener.onLongPress(lat: c.latitude, lon: c.longitude)
    }

    @objc private func onTap(_ g: UITapGestureRecognizer) {
        let p = g.location(in: map)
        let box = CGRect(x: p.x - 12, y: p.y - 12, width: 24, height: 24)
        let ids: Set<String> = ["rr-parking-parking", "rr-parking-repair", "rr-parking-charging"]
        if let f = map.visibleFeatures(in: box, styleLayerIdentifiers: ids).first, let id = f.attribute(forKey: "id") as? String {
            listener.onParkingTap(parkingId: id)
        }
    }

    // MARK: MLNMapViewDelegate

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        let gestures: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom]
        if !reason.isDisjoint(with: gestures) { listener.onUserGesture() }
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
        listener.onCameraIdle()
    }

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        for id in Self.sourceIds {
            let s = MLNShapeSource(identifier: id, shape: nil, options: nil)
            style.addSource(s)
            sources[id] = s
        }
        addLayers(style)
        styleReady = true
        let queued = pending
        pending = [:]
        for (id, json) in queued { setSource(sourceId: id, geoJson: json) }
        listener.onReady()
    }

    // MARK: layers

    private func add(_ layer: MLNStyleLayer, to style: MLNStyle) { style.addLayer(layer) }

    private func addLayers(_ style: MLNStyle) {
        // Bike infrastructure from the OpenMapTiles transportation layer, under the path labels.
        if let omt = style.source(withIdentifier: "openmaptiles") {
            let bike = MLNLineStyleLayer(identifier: "rr-bikeways", source: omt)
            bike.sourceLayerIdentifier = "transportation"
            bike.minimumZoomLevel = 11
            bike.predicate = NSPredicate(format: "class == 'path' AND (subclass == 'cycleway' OR bicycle == 'designated' OR bicycle == 'yes')")
            bike.lineColor = NSExpression(forConstantValue: UIColor(rgb: 0x12A150))
            bike.lineWidth = NSExpression(forConstantValue: 3)
            bike.lineOpacity = NSExpression(forConstantValue: 0.85)
            bike.lineCap = NSExpression(forConstantValue: "round")
            if let below = style.layer(withIdentifier: "highway-name-path") { style.insertLayer(bike, below: below) } else { style.addLayer(bike) }
        }
        guard let range = sources["rr-range"], let rangeLabel = sources["rr-range-label"], let route = sources["rr-route"],
              let parking = sources["rr-parking"], let me = sources["rr-me"], let dest = sources["rr-dest"], let track = sources["rr-track"] else { return }

        for (kind, color, text) in [("oneway", Self.oneWay, UIColor(rgb: 0x1B5FC4)), ("round", Self.roundTrip, UIColor(rgb: 0x0C8A63))] {
            let fill = MLNFillStyleLayer(identifier: "rr-range-fill-\(kind)", source: range)
            fill.predicate = NSPredicate(format: "kind == %@", kind)
            fill.fillColor = NSExpression(forConstantValue: color)
            fill.fillOpacity = NSExpression(forConstantValue: 0.10)
            add(fill, to: style)
            let line = MLNLineStyleLayer(identifier: "rr-range-line-\(kind)", source: range)
            line.predicate = NSPredicate(format: "kind == %@", kind)
            line.lineColor = NSExpression(forConstantValue: color)
            line.lineWidth = NSExpression(forConstantValue: 3)
            line.lineDashPattern = NSExpression(forConstantValue: [2, 1.2])
            add(line, to: style)
            let label = MLNSymbolStyleLayer(identifier: "rr-range-label-\(kind)", source: rangeLabel)
            label.predicate = NSPredicate(format: "kind == %@", kind)
            label.text = NSExpression(forKeyPath: "label")
            label.textFontNames = NSExpression(forConstantValue: ["Noto Sans Bold"])
            label.textFontSize = NSExpression(forConstantValue: 14)
            label.textColor = NSExpression(forConstantValue: text)
            label.textHaloColor = NSExpression(forConstantValue: UIColor.white)
            label.textHaloWidth = NSExpression(forConstantValue: 2)
            label.textAllowsOverlap = NSExpression(forConstantValue: true)
            add(label, to: style)
        }

        let trackLine = MLNLineStyleLayer(identifier: "rr-track", source: track)
        trackLine.lineColor = NSExpression(forKeyPath: "color")
        trackLine.lineWidth = NSExpression(forConstantValue: 6)
        trackLine.lineCap = NSExpression(forConstantValue: "round")
        trackLine.lineJoin = NSExpression(forConstantValue: "round")
        add(trackLine, to: style)

        let casing = MLNLineStyleLayer(identifier: "rr-route-casing", source: route)
        casing.lineColor = NSExpression(forConstantValue: UIColor.white)
        casing.lineWidth = NSExpression(forConstantValue: 10)
        casing.lineCap = NSExpression(forConstantValue: "round")
        casing.lineJoin = NSExpression(forConstantValue: "round")
        add(casing, to: style)
        let routeLine = MLNLineStyleLayer(identifier: "rr-route-line", source: route)
        routeLine.lineColor = NSExpression(forConstantValue: Self.route)
        routeLine.lineWidth = NSExpression(forConstantValue: 6)
        routeLine.lineCap = NSExpression(forConstantValue: "round")
        routeLine.lineJoin = NSExpression(forConstantValue: "round")
        add(routeLine, to: style)

        for (kind, color) in [("PARKING", UIColor(rgb: 0x1565C0)), ("REPAIR", UIColor(rgb: 0xF2994A)), ("CHARGING", UIColor(rgb: 0xEB5757))] {
            let c = MLNCircleStyleLayer(identifier: "rr-parking-\(kind.lowercased())", source: parking)
            c.predicate = NSPredicate(format: "kind == %@", kind)
            c.circleRadius = NSExpression(forConstantValue: 7)
            c.circleColor = NSExpression(forConstantValue: color)
            c.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            c.circleStrokeWidth = NSExpression(forConstantValue: 2)
            add(c, to: style)
        }
        let glyph = MLNSymbolStyleLayer(identifier: "rr-parking-label", source: parking)
        glyph.text = NSExpression(forKeyPath: "glyph")
        glyph.textFontNames = NSExpression(forConstantValue: ["Noto Sans Bold"])
        glyph.textFontSize = NSExpression(forConstantValue: 10)
        glyph.textColor = NSExpression(forConstantValue: UIColor.white)
        glyph.textAllowsOverlap = NSExpression(forConstantValue: true)
        glyph.minimumZoomLevel = 14
        add(glyph, to: style)

        let destDot = MLNCircleStyleLayer(identifier: "rr-dest", source: dest)
        destDot.circleRadius = NSExpression(forConstantValue: 9)
        destDot.circleColor = NSExpression(forConstantValue: UIColor(rgb: 0xD32F2F))
        destDot.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        destDot.circleStrokeWidth = NSExpression(forConstantValue: 3)
        add(destDot, to: style)

        let halo = MLNCircleStyleLayer(identifier: "rr-me-halo", source: me)
        halo.circleRadius = NSExpression(forConstantValue: 18)
        halo.circleColor = NSExpression(forConstantValue: Self.oneWay)
        halo.circleOpacity = NSExpression(forConstantValue: 0.18)
        add(halo, to: style)
        let dot = MLNCircleStyleLayer(identifier: "rr-me", source: me)
        dot.circleRadius = NSExpression(forConstantValue: 9)
        dot.circleColor = NSExpression(forConstantValue: Self.oneWay)
        dot.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        dot.circleStrokeWidth = NSExpression(forConstantValue: 3)
        add(dot, to: style)
    }
}

extension UIColor {
    convenience init(rgb: Int) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
