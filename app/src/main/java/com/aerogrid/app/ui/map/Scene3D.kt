package com.aerogrid.app.ui.map

import com.aerogrid.app.engine.GeoMath
import com.aerogrid.app.engine.Photogrammetry
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds GPU fill-extrusion geometry for the 3D view: elevated route ribbon, waypoint pillars,
 * boundary walls, a stepped camera-frustum pyramid and ground photo footprints that reveal overlap.
 * Heights are the planned flight heights over a flat basemap (no terrain relief).
 */
object Scene3D {
    private const val M = 111320.0
    private fun mLon(lat: Double) = M * cos(Math.toRadians(lat))

    /** Rectangle of [w] (across track) × [h] (along track) metres centred on [c], rotated to bearing [brgDeg]. */
    fun rect(c: GeoPoint, w: Double, h: Double, brgDeg: Double): Polygon {
        val b = Math.toRadians(brgDeg); val ml = mLon(c.lat)
        val pts = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * w / 2; val y = sy * h / 2
            Point.fromLngLat(c.lon + (x * cos(b) + y * sin(b)) / ml, c.lat + (-x * sin(b) + y * cos(b)) / M)
        }
        return Polygon.fromLngLats(listOf(pts + pts.first()))
    }

    /** Thin strip of half-width [hw] metres along a→b. */
    fun strip(a: GeoPoint, b: GeoPoint, hw: Double): Polygon? {
        val ml = mLon(a.lat)
        val dx = (b.lon - a.lon) * ml; val dy = (b.lat - a.lat) * M
        val len = hypot(dx, dy); if (len < 0.05) return null
        val nx = -dy / len * hw; val ny = dx / len * hw
        fun p(g: GeoPoint, s: Double) = Point.fromLngLat(g.lon + s * nx / ml, g.lat + s * ny / M)
        val ring = listOf(p(a, 1.0), p(b, 1.0), p(b, -1.0), p(a, -1.0))
        return Polygon.fromLngLats(listOf(ring + ring.first()))
    }

    private fun ext(g: Polygon, base: Double, top: Double, color: String) = Feature.fromGeometry(g).apply {
        addNumberProperty("base", base); addNumberProperty("top", max(top, base + 0.3)); addStringProperty("color", color)
    }

    private val BLOCK_COLORS = listOf("#FFB300", "#00E5FF", "#76FF03", "#FF4081", "#E040FB")

    /** Route ribbon + waypoint pillars (opaque layer). */
    /** Visual scale (m) so ribbons/caps stay readable on large and small missions alike. */
    private fun scale(p: Project) = (Photogrammetry.lineSpacing(p.camera, p.settings) * 0.09).coerceIn(1.2, 5.0)

    fun solids(p: Project, selected: Set<Long>): List<Feature> {
        val w = p.waypoints; val out = ArrayList<Feature>(w.size * 4); val k0 = scale(p)
        for (i in 0 until w.size - 1) {
            val a = w[i]; val b = w[i + 1]
            val ha = p.heightOf(a); val hb = p.heightOf(b)
            val bi = p.blocks.indexOfFirst { i >= it.start && i + 1 <= it.end }.coerceAtLeast(0)
            val color = if (a.transit || b.transit) "#E0E0E0" else BLOCK_COLORS[bi % BLOCK_COLORS.size]
            // Split sloped legs so the flat-topped extrusion follows the climb in ≤2 m steps.
            val k = max(1, ceil(abs(hb - ha) / 2.0).toInt())
            for (j in 0 until k) {
                val p0 = GeoMath.interpolate(a.point, b.point, j.toDouble() / k); val p1 = GeoMath.interpolate(a.point, b.point, (j + 1.0) / k)
                val h = ha + (hb - ha) * (j + 0.5) / k
                strip(p0, p1, k0)?.let { out += ext(it, h - k0 * 0.4, h + k0 * 0.4, color) }
            }
        }
        w.forEachIndexed { i, wp ->
            val h = p.heightOf(wp)
            out += ext(rect(wp.point, k0 * 0.5, k0 * 0.5, 0.0), 0.0, h, "#CFD8DC")
            val cap = when {
                wp.id in selected -> "#FFEB3B"; i == 0 -> "#00C853"; i == w.lastIndex -> "#FF1744"
                wp.transit -> "#9E9E9E"; wp.heightOverride != null -> "#FF9100"; else -> "#1E88E5"
            }
            out += ext(rect(wp.point, k0 * 3, k0 * 3, 0.0), h - k0, h + k0, cap)
        }
        return out
    }

    /** Translucent walls along survey boundaries (cyan) and exclusion zones (red). */
    fun walls(p: Project): List<Feature> {
        val out = ArrayList<Feature>()
        val top = p.waypoints.maxOfOrNull { p.heightOf(it) } ?: p.settings.heightM
        fun ring(r: List<GeoPoint>, h: Double, color: String) = r.indices.forEach { i -> strip(r[i], r[(i + 1) % r.size], 0.4)?.let { out += ext(it, 0.0, h, color) } }
        p.boundaries.forEach { ring(it, p.settings.heightM, "#00C8FF") }
        p.exclusions.forEach { ring(it, top + 20, "#FF3B30") }
        return out
    }

    /** Stepped pyramid from the camera at [pos]/[height] down to its ground footprint. */
    fun frustum(p: Project, pos: GeoPoint, brg: Double, height: Double, slices: Int = 10): List<Feature> {
        val fp = Photogrammetry.footprint(p.camera, height, p.settings.zoom)
        return (0 until slices).map { k ->
            val z0 = height * k / slices; val z1 = height * (k + 1) / slices
            val s = 1 - (k + 0.5) / slices
            ext(rect(pos, fp.widthM * s, fp.heightM * s, brg), z0, z1, "#FFD60A")
        }
    }

    /**
     * Ground footprints of planned photos on the focus line and its neighbours, so forward and side overlap
     * are visible as stacked translucency. Capped to keep rendering light.
     */
    fun plannedFootprints(p: Project, focusIndex: Int, cap: Int = 140): List<Feature> {
        val w = p.waypoints; if (w.size < 2) return emptyList()
        val li = w[focusIndex.coerceIn(0, w.lastIndex)].lineIndex
        val lines = if (li < 0) setOf(-1) else setOf(li - 1, li, li + 1)
        val fp = Photogrammetry.footprint(p.camera, p.settings.heightM, p.settings.zoom)
        val step = max(1.0, Photogrammetry.photoSpacing(p.camera, p.settings))
        val out = ArrayList<Feature>()
        for (i in 0 until w.size - 1) {
            val a = w[i]; val b = w[i + 1]
            if (a.transit || b.transit || a.lineIndex != b.lineIndex || a.lineIndex !in lines) continue
            val d = GeoMath.distance(a.point, b.point); val brg = GeoMath.bearing(a.point, b.point)
            var s = 0.0
            while (s <= d && out.size < cap) { out += Feature.fromGeometry(rect(GeoMath.interpolate(a.point, b.point, if (d > 0) s / d else 0.0), fp.widthM, fp.heightM, brg)); s += step }
        }
        return out
    }

    /** Index and bearing of the waypoint the frustum is shown at. */
    fun focus(p: Project, selected: Set<Long>): Pair<Int, Double>? {
        val w = p.waypoints; if (w.isEmpty()) return null
        val i = w.indexOfFirst { it.id in selected }.takeIf { it >= 0 } ?: (w.size / 2).let { m -> if (m < w.lastIndex && w[m].lineIndex != w[m + 1].lineIndex && m > 0) m - 1 else m }
        val brg = when { i < w.lastIndex -> GeoMath.bearing(w[i].point, w[i + 1].point); i > 0 -> GeoMath.bearing(w[i - 1].point, w[i].point); else -> 0.0 }
        return i to brg
    }

    @Suppress("unused") private fun clamp(v: Double) = min(max(v, 0.0), 1.0)
}
