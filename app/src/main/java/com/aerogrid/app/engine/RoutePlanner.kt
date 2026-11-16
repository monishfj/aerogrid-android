package com.aerogrid.app.engine

import com.aerogrid.app.model.AltitudeMode
import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.CaptureStrategy
import com.aerogrid.app.model.FlightPattern
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.Waypoint
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurve
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PlanResult(val waypoints: List<Waypoint>, val lineCount: Int, val bearingDeg: Double, val warnings: List<String>, val nextId: Long)

/** Geometry context for a project in local metres. */
class SurveyGeometry(val project: Project) {
    val proj: LocalProjection = LocalProjection.around(project.boundaries.flatten() + project.corridor + project.waypoints.map { it.point })
    val boundary: Geometry? = project.boundaries.mapNotNull { proj.polygon(it) }.takeIf { it.isNotEmpty() }?.let { GF.buildGeometry(it).union() }
    val exclusion: Geometry? = project.exclusions.mapNotNull { proj.polygon(it) }.takeIf { it.isNotEmpty() }?.let { GF.buildGeometry(it).union() }
    val area: Geometry? = boundary?.let { b ->
        val buffered = if (project.settings.bufferM != 0.0) b.buffer(project.settings.bufferM) else b
        exclusion?.let { buffered.difference(it) } ?: buffered
    }

    /** Free space for transit: everything except exclusions (bounded). */
    val transitSpace: Geometry by lazy {
        val all = GF.buildGeometry(listOfNotNull(boundary, exclusion, if (project.corridor.size >= 2) proj.line(project.corridor) else null))
        val env = all.envelope.buffer(300.0)
        exclusion?.let { env.difference(it) } ?: env
    }
    fun xy(p: GeoPoint) = proj.toXY(p)
    fun geo(c: Coordinate) = proj.toGeo(c)
}

/** Shortest path inside free space using a visibility graph over (slightly inset) free-space vertices. */
class SafeRouter(free: Geometry) {
    private val checkSpace = free.buffer(0.75)
    private val nodes: List<Coordinate>

    init {
        var inset = free.buffer(-1.0)
        if (inset.isEmpty) inset = free
        if (inset.numPoints > 400) inset = DouglasPeuckerSimplifier.simplify(inset, 2.0)
        nodes = inset.coordinates.distinctBy { (it.x * 10).toLong() to (it.y * 10).toLong() }
    }

    fun visible(a: Coordinate, b: Coordinate) = a.equals2D(b) || checkSpace.covers(GF.createLineString(arrayOf(a, b)))

    /** Returns intermediate coordinates (excluding a and b), empty if direct, or null if no path exists. */
    fun route(a: Coordinate, b: Coordinate): List<Coordinate>? {
        if (visible(a, b)) return emptyList()
        val all = listOf(a, b) + nodes
        val n = all.size
        val dist = DoubleArray(n) { Double.MAX_VALUE }; val prev = IntArray(n) { -1 }
        dist[0] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }); pq.add(0.0 to 0)
        val done = BooleanArray(n)
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()!!
            if (done[u]) continue; done[u] = true
            if (u == 1) break
            for (v in 1 until n) {
                if (done[v] || v == u) continue
                val nd = d + all[u].distance(all[v])
                if (nd < dist[v] && visible(all[u], all[v])) { dist[v] = nd; prev[v] = u; pq.add(nd to v) }
            }
        }
        if (prev[1] == -1) return null
        val path = ArrayList<Coordinate>()
        var c = prev[1]
        while (c > 0) { path.add(0, all[c]); c = prev[c] }
        return path
    }
}

object RoutePlanner {
    private class Seg(val line: Int, var a: Coordinate, var b: Coordinate)

    fun autoBearing(g: Geometry): Double {
        val rect = MinimumDiameter(g).minimumRectangle
        val cs = rect.coordinates
        if (cs.size < 4) return 0.0
        val e1 = cs[0].distance(cs[1]); val e2 = cs[1].distance(cs[2])
        val (p, q) = if (e1 >= e2) cs[0] to cs[1] else cs[1] to cs[2]
        val brg = Math.toDegrees(atan2(q.x - p.x, q.y - p.y))
        return ((brg % 180) + 180) % 180
    }

    fun generate(project: Project, dem: ElevationSource? = null): PlanResult {
        val s = project.settings
        val g = SurveyGeometry(project)
        val warnings = ArrayList<String>()
        val spacing = Photogrammetry.lineSpacing(project.camera, s)
        val photoSpacing = Photogrammetry.photoSpacing(project.camera, s)
        var bearing = s.bearingDeg ?: 0.0
        // Each "pass" is an ordered list of flight lines (local coordinates).
        val lines: List<Seg> = when (s.pattern) {
            FlightPattern.MANUAL -> return PlanResult(project.waypoints, project.waypoints.map { it.lineIndex }.distinct().count { it >= 0 }, bearing, emptyList(), project.nextId)
            FlightPattern.CORRIDOR -> {
                if (project.corridor.size < 2) return PlanResult(emptyList(), 0, bearing, listOf("Draw a corridor centre line first"), project.nextId)
                corridorLines(g, spacing, s.corridorWidthM)
            }
            FlightPattern.PERIMETER -> {
                val area = g.area ?: return PlanResult(emptyList(), 0, bearing, listOf("Draw a survey boundary first"), project.nextId)
                perimeterLines(area, spacing)
            }
            FlightPattern.LAWNMOWER, FlightPattern.CROSSHATCH -> {
                val area = g.area ?: return PlanResult(emptyList(), 0, bearing, listOf("Draw a survey boundary first"), project.nextId)
                if (area.isEmpty || area.area < 1.0) return PlanResult(emptyList(), 0, bearing, listOf("Survey area is empty after exclusions/buffer"), project.nextId)
                bearing = s.bearingDeg ?: autoBearing(area)
                val first = gridForArea(g, area, bearing, spacing, s.lineExtensionM, 0, project)
                if (s.pattern == FlightPattern.CROSSHATCH) first + gridForArea(g, area, (bearing + 90) % 180, spacing, s.lineExtensionM, first.size, project) else first
            }
        }
        if (lines.isEmpty()) return PlanResult(emptyList(), 0, bearing, listOf("No flight lines fit inside the boundary — reduce height/overlap or enlarge the area"), project.nextId)
        if (s.bufferM > 0) warnings += "Survey buffer extends ${s.bufferM.toInt()} m beyond the drawn boundary"
        if (s.lineExtensionM > 0) warnings += "Flight lines extend ${s.lineExtensionM.toInt()} m past boundary edges (exclusion zones still respected)"

        // Build waypoint list with safe connectors.
        val router = SafeRouter(g.transitSpace)
        val interior = g.area?.let { a -> SafeRouter(g.exclusion?.let { a.union(g.boundary!!).difference(it) } ?: a.union(g.boundary!!)) }
        var id = project.nextId
        val out = ArrayList<Waypoint>()
        var last: Coordinate? = null
        var unsafe = 0
        val densifyTerrain = dem != null && s.altitudeMode == AltitudeMode.TERRAIN_AGL
        for (seg in lines) {
            val prev = last
            if (prev != null && !prev.equals2D(seg.a)) {
                val via = interior?.route(prev, seg.a) ?: router.route(prev, seg.a)
                if (via == null) { unsafe++ } else via.forEach { c -> val p = g.geo(c); out += Waypoint(id++, p.lat, p.lon, transit = true) }
            }
            val len = seg.a.distance(seg.b)
            val step = when {
                s.capture == CaptureStrategy.WAYPOINT_PHOTO -> photoSpacing
                densifyTerrain -> s.terrainSampleM
                else -> Double.MAX_VALUE
            }
            val n = if (step == Double.MAX_VALUE || len <= step) 1 else ceil(len / step).toInt()
            val kStart = if (prev != null && prev.equals2D(seg.a)) 1 else 0
            for (k in kStart..n) {
                val f = k.toDouble() / n
                val p = g.geo(Coordinate(seg.a.x + (seg.b.x - seg.a.x) * f, seg.a.y + (seg.b.y - seg.a.y) * f))
                out += Waypoint(id++, p.lat, p.lon, lineIndex = seg.line,
                    action = if (s.capture == CaptureStrategy.WAYPOINT_PHOTO) CameraAction.TAKE_PHOTO else CameraAction.NONE)
            }
            last = seg.b
        }
        if (unsafe > 0) warnings += "$unsafe connection(s) could not be routed around exclusion zones — review the route"
        return PlanResult(out, lines.map { it.line }.distinct().size, bearing, warnings, id)
    }

    private fun gridForArea(g: SurveyGeometry, area: Geometry, bearing: Double, spacing: Double, ext: Double, lineOffset: Int, project: Project): List<Seg> {
        // Rotate so flight direction aligns with +x.
        val theta = Math.toRadians(90.0 - bearing)
        val rot = AffineTransformation.rotationInstance(-theta)
        val inv = AffineTransformation.rotationInstance(theta)
        val result = ArrayList<Seg>()
        val start = project.takeoff?.let { g.xy(it) }
        // Handle each polygon component separately (multi-polygon mapping).
        val comps = (0 until area.numGeometries).map { area.getGeometryN(it) }.filter { it is Polygon && it.area > 1 }
            .sortedBy { c -> start?.let { c.centroid.coordinate.distance(it) } ?: c.centroid.x }
        var lineNo = lineOffset
        val exclRot = g.exclusion?.let { rot.transform(it) }
        for (comp in comps) {
            val r = rot.transform(comp)
            val env = r.envelopeInternal
            val h = env.height
            val n = max(1, ceil(h / spacing).toInt())
            val y0 = env.minY + (h - (n - 1) * spacing) / 2
            val rows = ArrayList<List<Seg>>()
            for (k in 0 until n) {
                val y = y0 + k * spacing
                val probe = GF.createLineString(arrayOf(Coordinate(env.minX - 10, y), Coordinate(env.maxX + 10, y)))
                val inter = probe.intersection(r)
                val segs = ArrayList<Seg>()
                for (i in 0 until inter.numGeometries) {
                    val ls = inter.getGeometryN(i) as? LineString ?: continue
                    if (ls.length < 1.0) continue
                    val c0 = ls.coordinates.minBy { it.x }; val c1 = ls.coordinates.maxBy { it.x }
                    var a = Coordinate(c0.x - ext, y); var b = Coordinate(c1.x + ext, y)
                    if (ext > 0 && exclRot != null) {
                        val clipped = GF.createLineString(arrayOf(a, b)).difference(exclRot)
                        val keep = (0 until clipped.numGeometries).map { clipped.getGeometryN(it) }.filterIsInstance<LineString>()
                            .firstOrNull { it.intersects(ls) } ?: ls
                        a = Coordinate(keep.coordinates.minOf { it.x }, y); b = Coordinate(keep.coordinates.maxOf { it.x }, y)
                    }
                    segs += Seg(k, a, b)
                }
                rows += segs.sortedBy { it.a.x }
            }
            // Boustrophedon cell decomposition: chain segments of consecutive rows that overlap in x.
            val chains = ArrayList<MutableList<Seg>>()
            val open = ArrayList<MutableList<Seg>>()
            for ((k, row) in rows.withIndex()) {
                val nextOpen = ArrayList<MutableList<Seg>>()
                val used = HashSet<MutableList<Seg>>()
                for (seg in row) {
                    val ch = open.firstOrNull { c -> c !in used && c.last().line == k - 1 && overlap(c.last(), seg) > spacing * 0.3 }
                    if (ch != null) { ch += seg; used += ch; nextOpen += ch } else { val c = mutableListOf(seg); chains += c; nextOpen += c }
                }
                open.clear(); open.addAll(nextOpen)
            }
            // Order chains greedily by proximity; alternate directions inside chains.
            var cursor: Coordinate? = start?.let { rot.transform(GF.createPoint(it)).coordinate }
            val remaining = chains.toMutableList()
            while (remaining.isNotEmpty()) {
                val cur = cursor
                val (chain, fromTop) = if (cur == null) remaining.first() to false else remaining.flatMap { c ->
                    listOf(c to false, c to true)
                }.minBy { (c, top) -> val s0 = if (top) c.last() else c.first(); min(s0.a.distance(cur), s0.b.distance(cur)) }
                remaining.remove(chain)
                val ordered = if (fromTop) chain.reversed() else chain
                for (seg in ordered) {
                    val c = cursor
                    val flip = c != null && seg.b.distance(c) < seg.a.distance(c)
                    val a = if (flip) seg.b else seg.a; val b = if (flip) seg.a else seg.b
                    result += Seg(lineNo++, inv.transform(GF.createPoint(a)).coordinate, inv.transform(GF.createPoint(b)).coordinate)
                    cursor = b
                }
            }
        }
        return result
    }

    private fun overlap(a: Seg, b: Seg): Double {
        val a0 = min(a.a.x, a.b.x); val a1 = max(a.a.x, a.b.x); val b0 = min(b.a.x, b.b.x); val b1 = max(b.a.x, b.b.x)
        return min(a1, b1) - max(a0, b0)
    }

    private fun corridorLines(g: SurveyGeometry, spacing: Double, width: Double): List<Seg> {
        val center = g.proj.line(g.project.corridor)
        val n = if (width <= spacing) 1 else ceil(width / spacing).toInt() + 1
        val step = if (n == 1) 0.0 else width / (n - 1)
        val result = ArrayList<Seg>()
        var lineNo = 0
        for (i in 0 until n) {
            val off = (i - (n - 1) / 2.0) * step
            var lane: Geometry = if (abs(off) < 0.01) center else OffsetCurve.getCurve(center, off)
            g.exclusion?.let { lane = lane.difference(it) }
            val parts = (0 until lane.numGeometries).map { lane.getGeometryN(it) }.filterIsInstance<LineString>().filter { it.length > 1 }
            val ordered = if (i % 2 == 0) parts else parts.reversed().map { it.reverse() as LineString }
            for (ls in ordered) {
                val cs = ls.coordinates
                for (j in 0 until cs.size - 1) result += Seg(lineNo, cs[j], cs[j + 1])
                lineNo++
            }
        }
        return result
    }

    private fun perimeterLines(area: Geometry, spacing: Double): List<Seg> {
        val result = ArrayList<Seg>()
        var lineNo = 0
        for (i in 0 until area.numGeometries) {
            val poly = area.getGeometryN(i) as? Polygon ?: continue
            var ring = poly.buffer(-spacing / 2, 8, BufferParameters.CAP_FLAT)
            if (ring.isEmpty) ring = poly
            val shell = (ring.getGeometryN(0) as Polygon).exteriorRing
            val cs = DouglasPeuckerSimplifier.simplify(shell, 1.0).coordinates
            for (j in 0 until cs.size - 1) result += Seg(lineNo, cs[j], cs[j + 1])
            lineNo++
        }
        return result
    }
}
