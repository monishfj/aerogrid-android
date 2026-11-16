package com.aerogrid.app.engine

import com.aerogrid.app.model.GeoPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

val GF = GeometryFactory()

/**
 * Local tangent-plane projection (east/north metres) around an origin using WGS84 meridional and
 * prime-vertical radii at the origin latitude. Accurate to ~1e-4 relative over survey-sized areas (< 10 km).
 */
class LocalProjection(val origin: GeoPoint) {
    private val a = 6378137.0
    private val e2 = 6.69437999014e-3
    private val phi = Math.toRadians(origin.lat)
    private val sinPhi = sin(phi)
    private val w = sqrt(1 - e2 * sinPhi * sinPhi)
    private val mRadius = a * (1 - e2) / (w * w * w)
    private val nRadius = a / w
    private val kx = nRadius * cos(phi) * PI / 180.0
    private val ky = mRadius * PI / 180.0

    fun toXY(p: GeoPoint) = Coordinate((p.lon - origin.lon) * kx, (p.lat - origin.lat) * ky)
    fun toXY(lat: Double, lon: Double) = Coordinate((lon - origin.lon) * kx, (lat - origin.lat) * ky)
    fun toGeo(c: Coordinate) = GeoPoint(origin.lat + c.y / ky, origin.lon + c.x / kx)

    fun polygon(ring: List<GeoPoint>): Geometry? {
        if (ring.size < 3) return null
        val cs = ring.map { toXY(it) }.toMutableList()
        if (!cs.first().equals2D(cs.last())) cs.add(Coordinate(cs.first()))
        if (cs.size < 4) return null
        val poly: Polygon = GF.createPolygon(cs.toTypedArray())
        return if (poly.isValid) poly else GeometryFixer.fix(poly)
    }

    fun line(pts: List<GeoPoint>) = GF.createLineString(pts.map { toXY(it) }.toTypedArray())

    companion object {
        fun around(points: List<GeoPoint>): LocalProjection {
            if (points.isEmpty()) return LocalProjection(GeoPoint(0.0, 0.0))
            return LocalProjection(GeoPoint(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size))
        }
    }
}

object GeoMath {
    private const val R = 6371008.8
    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }
    /** Initial bearing in degrees clockwise from north, 0..360. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    fun interpolate(a: GeoPoint, b: GeoPoint, f: Double) = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)

    /** Geodesic polygon area (m²) via local projection; handles concave rings. */
    fun area(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        val proj = LocalProjection.around(ring)
        return proj.polygon(ring)?.area ?: 0.0
    }
    fun pathLength(pts: List<GeoPoint>) = pts.zipWithNext().sumOf { (a, b) -> distance(a, b) }
    fun normalize180(d: Double): Double { var x = d % 360; if (x > 180) x -= 360; if (x <= -180) x += 360; return x }
}

fun formatArea(m2: Double): String = when {
    m2 >= 1_000_000 -> "%.3f km²".format(m2 / 1_000_000)
    m2 >= 10_000 -> "%.2f ha".format(m2 / 10_000)
    else -> "%.0f m²".format(m2)
}
fun formatDuration(sec: Double): String { val s = sec.toLong(); return if (s >= 3600) "%dh %02dm".format(s / 3600, (s % 3600) / 60) else "%dm %02ds".format(s / 60, s % 60) }
fun formatDistance(m: Double) = if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)
