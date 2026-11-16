package com.aerogrid.app.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.aerogrid.app.data.AppSettings
import com.aerogrid.app.engine.Photogrammetry
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What a touch landed on. kind: 0 boundary, 1 exclusion, 2 corridor. */
sealed interface Hit {
    data class Wp(val id: Long) : Hit
    data class Vertex(val kind: Int, val ring: Int, val v: Int) : Hit
    data class Mid(val kind: Int, val ring: Int, val afterV: Int) : Hit
}

interface MapCallbacks {
    fun onTap(pt: GeoPoint, hit: Hit?)
    fun onLongPress(pt: GeoPoint, hit: Hit?)
    fun onDragStart(hit: Hit, pt: GeoPoint)
    fun onDrag(hit: Hit, pt: GeoPoint)
    fun onDragEnd(hit: Hit, pt: GeoPoint)
    fun onFreehand(points: List<GeoPoint>)
}

/** Everything the map needs to draw; built by the view model. */
data class MapScene(
    val project: Project,
    val draft: List<GeoPoint> = emptyList(),
    val draftClosed: Boolean = true,
    val selected: Set<Long> = emptySet(),
    val hitWaypoints: Boolean = false,
    val hitShapes: Boolean = false,
    val freehand: Boolean = false,
    val drone: Pair<GeoPoint, Double>? = null,
    val capturedPhotos: List<GeoPoint> = emptyList(),
    val showFootprints: Boolean = true,
    val myLocation: GeoPoint? = null,
    val highlightBlock: Int? = null,
    val threeD: Boolean = false,
)

@SuppressLint("ClickableViewAccessibility")
class MapController(context: Context) {
    val view = MapView(context)
    var map: MapLibreMap? = null; private set
    var callbacks: MapCallbacks? = null
    private var style: Style? = null
    private var scene: MapScene? = null
    private var styleKey: String? = null
    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val pending = ArrayList<(MapLibreMap) -> Unit>()

    // touch state
    private var dragHit: Hit? = null
    private var dragStarted = false
    private var downX = 0f; private var downY = 0f
    private val freehandPts = ArrayList<GeoPoint>()
    private var freehandActive = false

    init {
        view.onCreate(null)
        view.setMaximumFps(60)
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(80L * 1024 * 1024, null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassMargins(0, (110 * density).toInt(), (16 * density).toInt(), 0)
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.prefetchesTiles = false // only fetch tiles that are actually visible
            m.addOnMapClickListener { ll -> callbacks?.onTap(GeoPoint(ll.latitude, ll.longitude), hitTest(m.projection.toScreenLocation(ll))); true }
            m.addOnMapLongClickListener { ll ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                callbacks?.onLongPress(GeoPoint(ll.latitude, ll.longitude), hitTest(m.projection.toScreenLocation(ll))); true
            }
            pending.forEach { it(m) }; pending.clear()
        }
        view.setOnTouchListener { _, e -> handleTouch(e) }
    }

    private fun withMap(f: (MapLibreMap) -> Unit) { map?.let(f) ?: pending.add(f) }

    fun setStyle(s: AppSettings) {
        val key = "${s.mapStyle}|${s.streetStyleUrl}|${s.satelliteTilesUrl}|${s.labelsTilesUrl}"
        if (key == styleKey) return
        styleKey = key
        withMap { m ->
            val builder = when (s.mapStyle) {
                0 -> Style.Builder().fromUri(s.streetStyleUrl)
                else -> Style.Builder().fromJson(rasterStyle(s, hybrid = s.mapStyle == 2))
            }
            m.setStyle(builder) { st -> style = st; installLayers(st); scene?.let { render(it, force = true) } }
        }
    }

    private fun rasterStyle(s: AppSettings, hybrid: Boolean): String {
        val attr = s.satelliteAttribution.replace("\"", "'")
        val labels = if (hybrid) ""","labels":{"type":"raster","tiles":["${s.labelsTilesUrl}"],"tileSize":256,"maxzoom":19}""" else ""
        val labelLayer = if (hybrid) """,{"id":"labels","type":"raster","source":"labels"}""" else ""
        return """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "sources":{"sat":{"type":"raster","tiles":["${s.satelliteTilesUrl}"],"tileSize":256,"maxzoom":19,"attribution":"$attr"}$labels},
            "layers":[{"id":"bg","type":"background","paint":{"background-color":"#10161c"}},{"id":"sat","type":"raster","source":"sat"}$labelLayer]}"""
    }

    private fun installLayers(st: Style) {
        listOf("ag-areas", "ag-foot", "ag-route", "ag-draft", "ag-handles", "ag-wps", "ag-markers", "ag-drone", "ag-loc").forEach { st.addSource(GeoJsonSource(it)) }
        st.addImage("ag-arrow", arrowBitmap(Color.WHITE, 14))
        st.addImage("ag-drone", droneBitmap())
        val kind = get("kind")
        st.addLayer(FillLayer("ag-areas-fill", "ag-areas").withProperties(
            fillColor(Expression.match(kind, Expression.color(Color.parseColor("#00C8FF")), Expression.stop("exclusion", Expression.color(Color.parseColor("#FF3B30"))), Expression.stop("corridor", Expression.color(Color.parseColor("#B388FF"))))),
            fillOpacity(Expression.match(kind, literal(0.22f), Expression.stop("exclusion", literal(0.35f))))).withFilter(Expression.neq(kind, literal("corridorline"))))
        st.addLayer(LineLayer("ag-areas-line", "ag-areas").withProperties(
            lineColor(Expression.match(kind, Expression.color(Color.parseColor("#00C8FF")), Expression.stop("exclusion", Expression.color(Color.parseColor("#FF3B30"))), Expression.stop("corridorline", Expression.color(Color.parseColor("#B388FF"))), Expression.stop("corridor", Expression.color(Color.parseColor("#7C4DFF"))))),
            lineWidth(2.5f)))
        st.addLayer(FillLayer("ag-foot-fill", "ag-foot").withProperties(fillColor(Color.parseColor("#FFD60A")), fillOpacity(0.10f)).also { it.minZoom = 14f })
        st.addLayer(LineLayer("ag-foot-line", "ag-foot").withProperties(lineColor(Color.parseColor("#FFD60A")), lineWidth(0.8f), lineOpacity(0.6f)).also { it.minZoom = 14f })
        val blockColor = Expression.match(Expression.toString(Expression.get("block")), Expression.color(Color.parseColor("#FFB300")),
            Expression.stop("1", Expression.color(Color.parseColor("#00E5FF"))), Expression.stop("2", Expression.color(Color.parseColor("#76FF03"))),
            Expression.stop("3", Expression.color(Color.parseColor("#FF4081"))), Expression.stop("4", Expression.color(Color.parseColor("#E040FB"))))
        st.addLayer(LineLayer("ag-route-transit", "ag-route").withProperties(lineColor(Color.parseColor("#E0E0E0")), lineWidth(1.5f), lineOpacity(0.8f))
            .withFilter(Expression.eq(get("transit"), literal(true))))
        st.addLayer(LineLayer("ag-route-line", "ag-route").withProperties(lineColor(blockColor), lineWidth(Expression.switchCase(Expression.eq(get("hl"), literal(true)), literal(5f), literal(3f))),
            lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND)).withFilter(Expression.eq(get("transit"), literal(false))))
        st.addLayer(SymbolLayer("ag-route-arrows", "ag-route").withProperties(symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(90f),
            iconImage("ag-arrow"), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(1f)).also { it.minZoom = 13f })
        st.addLayer(LineLayer("ag-draft-line", "ag-draft").withProperties(lineColor(Color.parseColor("#FFEB3B")), lineWidth(2.5f), lineOpacity(0.9f)))
        st.addLayer(CircleLayer("ag-draft-pts", "ag-draft").withProperties(circleRadius(6f), circleColor(Color.parseColor("#FFEB3B")), circleStrokeColor(Color.BLACK), circleStrokeWidth(1.5f))
            .withFilter(Expression.eq(Expression.geometryType(), literal("Point"))))
        st.addLayer(CircleLayer("ag-handles-c", "ag-handles").withProperties(
            circleRadius(Expression.switchCase(Expression.eq(get("mid"), literal(true)), literal(5f), literal(8f))),
            circleColor(Expression.switchCase(Expression.eq(get("mid"), literal(true)), Expression.color(Color.parseColor("#80FFFFFF")), Expression.color(Color.WHITE))),
            circleStrokeColor(Expression.match(kind, Expression.color(Color.parseColor("#00A0D0")), Expression.stop("exclusion", Expression.color(Color.parseColor("#FF3B30"))), Expression.stop("corridor", Expression.color(Color.parseColor("#7C4DFF"))))),
            circleStrokeWidth(2.5f)))
        val wpColor = Expression.switchCase(
            Expression.eq(get("sel"), literal(true)), Expression.color(Color.parseColor("#FFEB3B")),
            Expression.eq(get("first"), literal(true)), Expression.color(Color.parseColor("#00C853")),
            Expression.eq(get("last"), literal(true)), Expression.color(Color.parseColor("#FF1744")),
            Expression.eq(get("transit"), literal(true)), Expression.color(Color.parseColor("#9E9E9E")),
            Expression.eq(get("ovr"), literal(true)), Expression.color(Color.parseColor("#FF9100")),
            Expression.color(Color.parseColor("#1E88E5")))
        st.addLayer(CircleLayer("ag-wps-c", "ag-wps").withProperties(
            circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 3f), Expression.stop(16, 7f), Expression.stop(19, 11f))),
            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP), circleColor(wpColor), circleStrokeColor(Color.WHITE), circleStrokeWidth(Expression.switchCase(Expression.eq(get("sel"), literal(true)), literal(3f), literal(1.5f)))))
        st.addLayer(SymbolLayer("ag-wps-t", "ag-wps").withProperties(textField(get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(11f),
            textColor(Color.WHITE), textHaloColor(Color.BLACK), textHaloWidth(1.4f), textOffset(arrayOf(0f, -1.4f)), textAllowOverlap(false), textOptional(true)).also { it.minZoom = 15f })
        st.addLayer(CircleLayer("ag-markers-c", "ag-markers").withProperties(circleRadius(11f), circleColor(Expression.match(kind, Expression.color(Color.parseColor("#2E7D32")), Expression.stop("L", Expression.color(Color.parseColor("#C62828"))))), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
        st.addLayer(SymbolLayer("ag-markers-t", "ag-markers").withProperties(textField(kind), textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor(Color.WHITE), textAllowOverlap(true), textIgnorePlacement(true)))
        listOf("ag-plan-foot", "ag-3d-walls", "ag-3d-solid", "ag-3d-cone").forEach { st.addSource(GeoJsonSource(it)) }
        st.addLayer(FillLayer("ag-plan-foot-fill", "ag-plan-foot").withProperties(fillColor(Color.parseColor("#FFD60A")), fillOpacity(0.07f)))
        st.addLayer(LineLayer("ag-plan-foot-line", "ag-plan-foot").withProperties(lineColor(Color.parseColor("#FFE082")), lineWidth(0.6f), lineOpacity(0.35f)))
        val extColor = Expression.toColor(get("color"))
        st.addLayer(FillExtrusionLayer("ag-3d-walls-x", "ag-3d-walls").withProperties(fillExtrusionColor(extColor), fillExtrusionBase(get("base")), fillExtrusionHeight(get("top")), fillExtrusionOpacity(0.28f)))
        st.addLayer(FillExtrusionLayer("ag-3d-solid-x", "ag-3d-solid").withProperties(fillExtrusionColor(extColor), fillExtrusionBase(get("base")), fillExtrusionHeight(get("top")), fillExtrusionOpacity(0.95f), fillExtrusionVerticalGradient(true)))
        st.addLayer(FillExtrusionLayer("ag-3d-cone-x", "ag-3d-cone").withProperties(fillExtrusionColor(extColor), fillExtrusionBase(get("base")), fillExtrusionHeight(get("top")), fillExtrusionOpacity(0.32f)))
        st.addLayer(CircleLayer("ag-loc-c", "ag-loc").withProperties(circleRadius(8f), circleColor(Color.parseColor("#2979FF")), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)))
        st.addLayer(SymbolLayer("ag-drone-s", "ag-drone").withProperties(iconImage("ag-drone"), iconRotate(get("brg")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    private var last: MapScene? = null

    fun render(s: MapScene, force: Boolean = false) {
        scene = s
        val st = style ?: return
        val prev = if (force) null else last
        last = s
        val p = s.project
        fun src(id: String) = st.getSourceAs<GeoJsonSource>(id)
        if (prev == null || prev.project.boundaries !== p.boundaries || prev.project.exclusions !== p.exclusions || prev.project.corridor !== p.corridor || prev.project.settings.corridorWidthM != p.settings.corridorWidthM) {
            val fs = ArrayList<Feature>()
            p.boundaries.forEach { r -> if (r.size >= 3) fs += Feature.fromGeometry(poly(r)).apply { addStringProperty("kind", "boundary") } }
            p.exclusions.forEach { r -> if (r.size >= 3) fs += Feature.fromGeometry(poly(r)).apply { addStringProperty("kind", "exclusion") } }
            if (p.corridor.size >= 2) fs += Feature.fromGeometry(LineString.fromLngLats(p.corridor.map { pt(it) })).apply { addStringProperty("kind", "corridorline") }
            src("ag-areas")?.setGeoJson(FeatureCollection.fromFeatures(fs))
        }
        if (prev == null || prev.project.waypoints !== p.waypoints || prev.selected != s.selected || prev.project.blocks !== p.blocks || prev.highlightBlock != s.highlightBlock || prev.project.settings !== p.settings) {
            val w = p.waypoints
            val route = ArrayList<Feature>()
            for (i in 0 until w.size - 1) {
                val bi = p.blocks.indexOfFirst { i >= it.start && i + 1 <= it.end }.coerceAtLeast(0)
                route += Feature.fromGeometry(LineString.fromLngLats(listOf(pt(w[i].point), pt(w[i + 1].point)))).apply {
                    addBooleanProperty("transit", w[i].transit || w[i + 1].transit); addNumberProperty("block", bi % 5); addBooleanProperty("hl", s.highlightBlock == bi)
                }
            }
            src("ag-route")?.setGeoJson(FeatureCollection.fromFeatures(route))
            src("ag-wps")?.setGeoJson(FeatureCollection.fromFeatures(w.mapIndexed { i, wp ->
                Feature.fromGeometry(pt(wp.point)).apply {
                    addStringProperty("label", "${i + 1}${if (wp.heightOverride != null) " · ${wp.heightOverride.toInt()}m" else ""}")
                    addBooleanProperty("sel", wp.id in s.selected); addBooleanProperty("first", i == 0); addBooleanProperty("last", i == w.lastIndex)
                    addBooleanProperty("transit", wp.transit); addBooleanProperty("ovr", wp.heightOverride != null)
                }
            }))
        }
        if (prev == null || prev.draft !== s.draft || prev.draftClosed != s.draftClosed) {
            val fs = ArrayList<Feature>()
            if (s.draft.size >= 2) fs += Feature.fromGeometry(LineString.fromLngLats((if (s.draftClosed && s.draft.size >= 3) s.draft + s.draft.first() else s.draft).map { pt(it) }))
            s.draft.forEach { fs += Feature.fromGeometry(pt(it)) }
            src("ag-draft")?.setGeoJson(FeatureCollection.fromFeatures(fs))
        }
        if (prev == null || prev.hitShapes != s.hitShapes || prev.project.boundaries !== p.boundaries || prev.project.exclusions !== p.exclusions || prev.project.corridor !== p.corridor) {
            val fs = ArrayList<Feature>()
            if (s.hitShapes) shapeHandles(p).forEach { (h, g) ->
                fs += Feature.fromGeometry(pt(g)).apply {
                    val k = when (h) { is Hit.Vertex -> h.kind; is Hit.Mid -> h.kind; else -> 0 }
                    addStringProperty("kind", listOf("boundary", "exclusion", "corridor")[k]); addBooleanProperty("mid", h is Hit.Mid)
                }
            }
            src("ag-handles")?.setGeoJson(FeatureCollection.fromFeatures(fs))
        }
        if (prev == null || prev.project.takeoff != p.takeoff || prev.project.landing != p.landing) {
            val fs = ArrayList<Feature>()
            p.takeoff?.let { fs += Feature.fromGeometry(pt(it)).apply { addStringProperty("kind", "H") } }
            p.landing?.let { fs += Feature.fromGeometry(pt(it)).apply { addStringProperty("kind", "L") } }
            src("ag-markers")?.setGeoJson(FeatureCollection.fromFeatures(fs))
        }
        if (prev == null || prev.drone != s.drone) {
            src("ag-drone")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(s.drone?.let { (g, b) -> Feature.fromGeometry(pt(g)).apply { addNumberProperty("brg", b) } })))
        }
        if (prev == null || prev.capturedPhotos !== s.capturedPhotos || prev.showFootprints != s.showFootprints || prev.drone != s.drone) {
            val fs = ArrayList<Feature>()
            if (s.showFootprints && s.capturedPhotos.isNotEmpty()) {
                val fp = Photogrammetry.footprint(p.camera, p.settings.heightM, p.settings.zoom)
                val brg = s.drone?.second ?: 0.0
                // Level of detail: only the most recent footprints are drawn.
                s.capturedPhotos.takeLast(400).forEach { c -> fs += Feature.fromGeometry(footprint(c, fp.widthM, fp.heightM, brg)) }
            }
            src("ag-foot")?.setGeoJson(FeatureCollection.fromFeatures(fs))
        }
        // ---- 3D: rebuilt only when relevant inputs change; during simulation only the frustum moves ----
        val empty = FeatureCollection.fromFeatures(emptyList())
        if (prev == null || prev.threeD != s.threeD || prev.project.waypoints !== p.waypoints || prev.project.settings !== p.settings || prev.selected != s.selected ||
            prev.project.boundaries !== p.boundaries || prev.project.exclusions !== p.exclusions || prev.project.blocks !== p.blocks) {
            src("ag-3d-solid")?.setGeoJson(if (s.threeD) FeatureCollection.fromFeatures(Scene3D.solids(p, s.selected)) else empty)
            src("ag-3d-walls")?.setGeoJson(if (s.threeD) FeatureCollection.fromFeatures(Scene3D.walls(p)) else empty)
            st.getLayer("ag-route-line")?.setProperties(lineOpacity(if (s.threeD) 0.35f else 1f))
            st.getLayer("ag-route-arrows")?.setProperties(visibility(if (s.threeD) Property.NONE else Property.VISIBLE))
            st.getLayer("ag-wps-t")?.setProperties(visibility(if (s.threeD) Property.NONE else Property.VISIBLE))
        }
        if (prev == null || prev.threeD != s.threeD || prev.drone != s.drone || prev.project.waypoints !== p.waypoints || prev.selected != s.selected || prev.project.settings !== p.settings) {
            val drone = s.drone
            val focus = Scene3D.focus(p, s.selected)
            val cone = when {
                !s.threeD -> emptyList()
                drone != null -> Scene3D.frustum(p, drone.first, drone.second, p.waypoints.getOrNull(focus?.first ?: 0)?.let { p.heightOf(it) } ?: p.settings.heightM)
                focus != null -> p.waypoints[focus.first].let { Scene3D.frustum(p, it.point, focus.second, p.heightOf(it)) }
                else -> emptyList()
            }
            src("ag-3d-cone")?.setGeoJson(FeatureCollection.fromFeatures(cone))
            if (prev == null || prev.threeD != s.threeD || prev.project.waypoints !== p.waypoints || prev.selected != s.selected || prev.project.settings !== p.settings || (prev.drone == null) != (drone == null))
                src("ag-plan-foot")?.setGeoJson(if (s.threeD && drone == null && focus != null) FeatureCollection.fromFeatures(Scene3D.plannedFootprints(p, focus.first)) else empty)
        }
        if (prev == null || prev.myLocation != s.myLocation) src("ag-loc")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(s.myLocation?.let { Feature.fromGeometry(pt(it)) })))
    }

    private fun pt(g: GeoPoint) = Point.fromLngLat(g.lon, g.lat)
    private fun poly(r: List<GeoPoint>) = Polygon.fromLngLats(listOf((r + r.first()).map { pt(it) }))

    private fun footprint(c: GeoPoint, w: Double, h: Double, brgDeg: Double): Polygon {
        val b = Math.toRadians(brgDeg)
        val mLat = 111320.0; val mLon = 111320.0 * cos(Math.toRadians(c.lat))
        val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * w / 2; val y = sy * h / 2 // x across-track, y along-track
            val e = x * cos(b) + y * sin(b); val n = -x * sin(b) + y * cos(b)
            Point.fromLngLat(c.lon + e / mLon, c.lat + n / mLat)
        }
        return Polygon.fromLngLats(listOf(corners + corners.first()))
    }

    private fun shapeHandles(p: Project): List<Pair<Hit, GeoPoint>> {
        val out = ArrayList<Pair<Hit, GeoPoint>>()
        listOf(p.boundaries, p.exclusions, if (p.corridor.isEmpty()) emptyList() else listOf(p.corridor)).forEachIndexed { kind, rings ->
            rings.forEachIndexed { ri, r ->
                r.forEachIndexed { vi, v -> out += Hit.Vertex(kind, ri, vi) to v }
                val closed = kind != 2
                val n = if (closed) r.size else r.size - 1
                for (vi in 0 until n) { val a = r[vi]; val b = r[(vi + 1) % r.size]; out += Hit.Mid(kind, ri, vi) to GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2) }
            }
        }
        return out
    }

    fun hitTest(sp: PointF): Hit? {
        val m = map ?: return null; val s = scene ?: return null
        val radius = 26 * density
        var best: Hit? = null; var bestD = radius
        if (s.hitShapes) for ((h, g) in shapeHandles(s.project)) {
            val q = m.projection.toScreenLocation(LatLng(g.lat, g.lon))
            val d = hypot(q.x - sp.x, q.y - sp.y) + if (h is Hit.Mid) 4 * density else 0f
            if (d < bestD) { bestD = d; best = h }
        }
        if (s.hitWaypoints && best == null) for (w in s.project.waypoints) {
            val q = m.projection.toScreenLocation(LatLng(w.lat, w.lon))
            val d = hypot(q.x - sp.x, q.y - sp.y)
            if (d < bestD) { bestD = d; best = Hit.Wp(w.id) }
        }
        return best
    }

    private fun toGeo(x: Float, y: Float): GeoPoint? = map?.projection?.fromScreenLocation(PointF(x, y))?.let { GeoPoint(it.latitude, it.longitude) }

    private fun handleTouch(e: MotionEvent): Boolean {
        val s = scene ?: return false
        if (s.freehand) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { freehandPts.clear(); freehandActive = true; toGeo(e.x, e.y)?.let { freehandPts += it } }
                MotionEvent.ACTION_MOVE -> if (freehandActive) {
                    toGeo(e.x, e.y)?.let { freehandPts += it }
                    if (freehandPts.size % 4 == 0) render(s.copy(draft = freehandPts.toList(), draftClosed = false))
                }
                MotionEvent.ACTION_UP -> { freehandActive = false; callbacks?.onFreehand(freehandPts.toList()) }
                MotionEvent.ACTION_CANCEL -> freehandActive = false
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!s.hitShapes && !s.hitWaypoints) return false
                val h = hitTest(PointF(e.x, e.y)) ?: return false
                dragHit = h; dragStarted = false; downX = e.x; downY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val h = dragHit ?: return false
                if (e.pointerCount > 1) return true
                if (!dragStarted && hypot(e.x - downX, e.y - downY) > slop) {
                    dragStarted = true; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    toGeo(downX, downY)?.let { callbacks?.onDragStart(h, it) }
                }
                if (dragStarted) toGeo(e.x, e.y)?.let { callbacks?.onDrag(h, it) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val h = dragHit ?: return false
                dragHit = null
                val g = toGeo(e.x, e.y) ?: return true
                if (dragStarted) callbacks?.onDragEnd(h, g)
                else if (e.eventTime - e.downTime > ViewConfiguration.getLongPressTimeout()) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); callbacks?.onLongPress(g, h) }
                else callbacks?.onTap(g, h)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { val h = dragHit; dragHit = null; if (h != null && dragStarted) toGeo(e.x, e.y)?.let { callbacks?.onDragEnd(h, it) }; return h != null }
        }
        return dragHit != null
    }

    // ---- camera ----
    fun flyTo(g: GeoPoint, zoom: Double = 15.5) = withMap { it.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.lat, g.lon), zoom), 900) }
    fun fit(points: List<GeoPoint>, animate: Boolean = true) = withMap { m ->
        if (points.isEmpty()) return@withMap
        if (points.size == 1) { flyTo(points[0]); return@withMap }
        val b = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        val sidePad = (48 * density).toInt(); val h = view.height.takeIf { it > 0 } ?: (800 * density).toInt()
        val u = CameraUpdateFactory.newLatLngBounds(b, sidePad + (40 * density).toInt(), (170 * density).toInt(), sidePad + (40 * density).toInt(), (h * 0.42).toInt())
        if (animate) m.animateCamera(u, 700) else m.moveCamera(u)
    }
    /** Tilt into a 3D oblique view framed on [points] (above the bottom panel), or flatten back to 2D. */
    fun setThreeD(on: Boolean, points: List<GeoPoint>) = withMap { m ->
        if (!on || points.size < 2) { m.animateCamera(CameraUpdateFactory.tiltTo(if (on) 60.0 else 0.0), 650); return@withMap }
        val h = view.height.takeIf { it > 0 } ?: (800 * density).toInt()
        val pad = intArrayOf((40 * density).toInt(), (150 * density).toInt(), (70 * density).toInt(), (h * 0.40).toInt())
        val b = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        val cam = m.getCameraForLatLngBounds(b, pad) ?: return@withMap
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(cam.target).zoom((cam.zoom) + 0.35).tilt(60.0).bearing(m.cameraPosition.bearing)
            .padding(pad[0].toDouble(), pad[1].toDouble(), pad[2].toDouble(), pad[3].toDouble()).build()), 800)
    }
    fun resetNorth() = withMap { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) }
    fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
    fun zoomBy(d: Double) = withMap { it.animateCamera(CameraUpdateFactory.zoomBy(d)) }
    fun snapshot(cb: (Bitmap) -> Unit) = withMap { it.snapshot { b -> cb(b) } }

    fun downloadOffline(ctx: Context, styleUrl: String, onProgress: (String) -> Unit) = withMap { m ->
        val bounds = m.projection.visibleRegion.latLngBounds
        val def = OfflineTilePyramidRegionDefinition(styleUrl, bounds, m.cameraPosition.zoom.coerceAtMost(12.0), 17.0, ctx.resources.displayMetrics.density)
        OfflineManager.getInstance(ctx).createOfflineRegion(def, "AeroGrid ${System.currentTimeMillis()}".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) { onProgress("Offline map ready: ${status.completedResourceCount} resources (${status.completedResourceSize / 1_048_576} MB)"); offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                        else if (status.requiredResourceCount > 0 && status.completedResourceCount % 50 == 0L) onProgress("Downloading offline map ${100 * status.completedResourceCount / status.requiredResourceCount}%")
                    }
                    override fun onError(error: OfflineRegionError) { onProgress("Offline download error: ${error.message}") }
                    override fun mapboxTileCountLimitExceeded(limit: Long) { onProgress("Offline tile limit ($limit) exceeded — zoom in and retry"); offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                onProgress("Offline download started")
            }
            override fun onError(error: String) { onProgress("Offline download failed: $error") }
        })
    }

    // ---- lifecycle ----
    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onDestroy() = view.onDestroy()
    fun onLowMemory() = view.onLowMemory()

    private fun arrowBitmap(color: Int, sizeDp: Int): Bitmap {
        val s = (sizeDp * density).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL; setShadowLayer(2f, 0f, 0f, Color.BLACK) }
        val path = Path().apply { moveTo(s * 0.2f, s * 0.2f); lineTo(s * 0.85f, s * 0.5f); lineTo(s * 0.2f, s * 0.8f); lineTo(s * 0.38f, s * 0.5f); close() }
        c.drawPath(path, p); return b
    }

    private fun droneBitmap(): Bitmap {
        val s = (40 * density).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3 * density; style = Paint.Style.STROKE }
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6D00"); style = Paint.Style.FILL }
        val m = s / 2f; val r = s * 0.32f
        c.drawLine(m - r, m - r, m + r, m + r, p); c.drawLine(m - r, m + r, m + r, m - r, p)
        listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).forEach { (x, y) -> c.drawCircle(m + x * r, m + y * r, s * 0.12f, p) }
        val path = Path().apply { moveTo(m, m - s * 0.22f); lineTo(m + s * 0.12f, m + s * 0.1f); lineTo(m - s * 0.12f, m + s * 0.1f); close() }
        c.drawPath(path, f); return b
    }
}
