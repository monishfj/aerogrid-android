package com.aerogrid.app.engine

import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.CaptureStrategy
import com.aerogrid.app.model.FlightPattern
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.MissionBlock
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.TurnMode
import com.aerogrid.app.model.Waypoint
import kotlin.math.ceil
import kotlin.math.max

object Timeline {
    const val ASCENT_MS = 5.0
    const val DESCENT_MS = 3.0

    fun missionSpeed(p: Project) = Photogrammetry.missionSpeed(p.camera, p.settings)
    fun legSpeed(p: Project, from: Waypoint): Double =
        (if (from.transit) p.settings.transitSpeedMs else from.speedOverride ?: missionSpeed(p)).coerceIn(0.5, p.camera.maxSpeed)

    fun stopTime(p: Project, wp: Waypoint): Double {
        val turn = when (wp.turnMode ?: p.settings.turnMode) { TurnMode.STOP_AT_POINT -> 3.0; TurnMode.COORDINATED_TURN -> 1.0; TurnMode.CURVED_PASS -> 0.3 }
        return turn + wp.hoverSec + if (wp.action == CameraAction.TAKE_PHOTO) 1.5 else 0.0
    }

    /** Flight time over a contiguous waypoint list, optionally including takeoff transit, climb and return. */
    fun duration(p: Project, wps: List<Waypoint>, includeTransit: Boolean = true): Double {
        if (wps.isEmpty()) return 0.0
        var t = 0.0
        for (i in wps.indices) {
            t += stopTime(p, wps[i])
            if (i < wps.lastIndex) t += GeoMath.distance(wps[i].point, wps[i + 1].point) / legSpeed(p, wps[i])
        }
        if (includeTransit) {
            val home = p.takeoff ?: wps.first().point
            val land = p.landing ?: home
            val h = p.heightOf(wps.first())
            t += h / ASCENT_MS + GeoMath.distance(home, wps.first().point) / p.settings.transitSpeedMs
            t += GeoMath.distance(wps.last().point, land) / p.settings.transitSpeedMs + p.heightOf(wps.last()) / DESCENT_MS
        }
        return t
    }
}

data class MissionStats(
    val areaM2: Double = 0.0, val waypointCount: Int = 0, val lineCount: Int = 0, val distanceM: Double = 0.0,
    val heightM: Double = 0.0, val gsdCm: Double = 0.0, val frontOverlap: Double = 0.0, val sideOverlap: Double = 0.0,
    val photoCount: Int = 0, val durationSec: Double = 0.0, val speedMs: Double = 0.0, val batteries: Int = 0,
    val missionCount: Int = 0, val camera: String = "", val footprintW: Double = 0.0, val footprintH: Double = 0.0,
    val lineSpacing: Double = 0.0, val photoSpacing: Double = 0.0, val storageMb: Double = 0.0, val overriddenHeights: Int = 0,
    val requiredSpeed: Double = 0.0, val usableBatterySec: Double = 0.0,
)

object Stats {
    fun compute(p: Project): MissionStats {
        val s = p.settings; val cam = p.camera
        val fp = Photogrammetry.footprint(cam, s.heightM, s.zoom)
        val speed = Timeline.missionSpeed(p)
        val wps = p.waypoints
        val dist = GeoMath.pathLength(wps.map { it.point })
        val lineDist = wps.zipWithNext().filter { (a, b) -> !a.transit && !b.transit && a.lineIndex >= 0 && a.lineIndex == b.lineIndex }
            .sumOf { (a, b) -> GeoMath.distance(a.point, b.point) }
        val photos = if (s.capture == CaptureStrategy.WAYPOINT_PHOTO || s.pattern == FlightPattern.MANUAL) wps.count { it.action == CameraAction.TAKE_PHOTO }
        else if (lineDist > 0) ceil(lineDist / (speed * s.intervalSec)).toInt() + wps.map { it.lineIndex }.distinct().count { it >= 0 } else 0
        val blocks = if (p.blocks.isEmpty()) listOf(MissionBlock(p.name, 0, max(0, wps.lastIndex))) else p.blocks
        val duration = if (wps.isEmpty()) 0.0 else blocks.sumOf { Timeline.duration(p, wps.subList(it.start, it.end + 1)) }
        val usable = usableBatterySec(p)
        val area = p.boundaries.sumOf { GeoMath.area(it) } - p.exclusions.sumOf { GeoMath.area(it) }.coerceAtMost(p.boundaries.sumOf { GeoMath.area(it) })
        return MissionStats(
            areaM2 = area, waypointCount = wps.size, lineCount = wps.map { it.lineIndex }.distinct().count { it >= 0 },
            distanceM = dist, heightM = s.heightM, gsdCm = Photogrammetry.gsdCm(cam, s.resolution, s.heightM, s.zoom),
            frontOverlap = if (s.capture == CaptureStrategy.TIMED_INTERVAL) Photogrammetry.actualFrontOverlap(cam, s, speed) else s.frontOverlap,
            sideOverlap = Photogrammetry.actualSideOverlap(cam, s), photoCount = photos, durationSec = duration, speedMs = speed,
            batteries = if (wps.isEmpty()) 0 else blocks.sumOf { b -> max(1, ceil(Timeline.duration(p, wps.subList(b.start, b.end + 1)) / usable).toInt()) },
            missionCount = if (wps.isEmpty()) 0 else blocks.size, camera = "${cam.name} · ${s.resolution.label} · ${"%.0f".format(cam.equivFocalMm)} mm eq",
            footprintW = fp.widthM, footprintH = fp.heightM, lineSpacing = Photogrammetry.lineSpacing(cam, s),
            photoSpacing = Photogrammetry.photoSpacing(cam, s),
            storageMb = photos * if (s.resolution.name == "MP50") cam.photoSizeMb50 else cam.photoSizeMb12,
            overriddenHeights = wps.count { it.heightOverride != null }, requiredSpeed = Photogrammetry.requiredSpeed(cam, s), usableBatterySec = usable,
        )
    }

    fun usableBatterySec(p: Project) = p.settings.batteryMinutes * 60 * (1 - p.settings.batteryReservePct / 100.0)
}

object MissionSplitter {
    /**
     * Splits the route into blocks honouring the waypoint limit and usable battery time. Splits prefer flight-line
     * boundaries; a line too long for one block is split mid-line and the next block starts on the shared waypoint,
     * so no survey strip is skipped. Coverage is never reduced.
     */
    fun split(p: Project, maxWp: Int = p.settings.maxWaypointsPerMission, maxSec: Double = Stats.usableBatterySec(p)): List<MissionBlock> {
        val wps = p.waypoints
        if (wps.isEmpty()) return emptyList()
        val limit = max(2, maxWp)
        val out = ArrayList<MissionBlock>()
        var start = 0
        val home = p.takeoff
        fun transit(a: Int, b: Int): Double {
            val h = home ?: wps[a].point; val land = p.landing ?: h
            return p.heightOf(wps[a]) / Timeline.ASCENT_MS + GeoMath.distance(h, wps[a].point) / p.settings.transitSpeedMs +
                GeoMath.distance(wps[b].point, land) / p.settings.transitSpeedMs + p.heightOf(wps[b]) / Timeline.DESCENT_MS
        }
        while (start <= wps.lastIndex) {
            var end = start
            var lastLineBreak = -1
            var inner = Timeline.stopTime(p, wps[start]) // running stops + legs for [start, end]
            while (end < wps.lastIndex) {
                val next = end + 1
                if (next - start + 1 > limit) break
                val add = GeoMath.distance(wps[end].point, wps[next].point) / Timeline.legSpeed(p, wps[end]) + Timeline.stopTime(p, wps[next])
                // Always keep at least one leg per block so splitting progresses; an over-long single leg is flagged by the validator.
                if (next - start >= 2 && inner + add + transit(start, next) > maxSec) break
                inner += add
                // a "line break" is after a line ends (next waypoint belongs to another line or is transit)
                if (wps[end].lineIndex != wps[next].lineIndex || wps[next].transit) lastLineBreak = end
                end = next
            }
            if (end < wps.lastIndex && lastLineBreak > start) {
                end = lastLineBreak
                // skip leading transit points of the next block? keep them — they are the safe route to the next strip.
            }
            out += MissionBlock(blockName(p.name, out.size + 1), start, end)
            if (end >= wps.lastIndex) break
            // Next block starts at the next waypoint if we broke at a line boundary, else shares the split waypoint.
            start = if (end == lastLineBreak) end + 1 else end
        }
        return out
    }

    fun blockName(project: String, n: Int) = "${project.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "Mission" }}_Block_%02d".format(n)

    fun blockWaypoints(p: Project, b: MissionBlock) = p.waypoints.subList(b.start.coerceAtLeast(0), (b.end + 1).coerceAtMost(p.waypoints.size))
}

enum class Severity { ERROR, WARNING, INFO }
data class Issue(val severity: Severity, val message: String, val fix: String = "")

object Validator {
    const val MAX_ALT_DJI = 500.0
    const val LEGAL_ALT = 120.0

    fun validate(p: Project, dem: ElevationSource?, stats: MissionStats = Stats.compute(p)): List<Issue> {
        val out = ArrayList<Issue>()
        val s = p.settings; val wps = p.waypoints
        val needsBoundary = s.pattern in setOf(FlightPattern.LAWNMOWER, FlightPattern.CROSSHATCH, FlightPattern.PERIMETER)
        if (needsBoundary && p.boundaries.isEmpty() && wps.isEmpty()) out += Issue(Severity.ERROR, "No survey boundary", "Tap Draw Boundary and outline the area")
        val geo = SurveyGeometry(p)
        p.boundaries.forEachIndexed { i, r ->
            if (r.size < 3) out += Issue(Severity.ERROR, "Boundary ${i + 1} has fewer than 3 vertices", "Add vertices or delete it")
            else {
                val raw = GF.createPolygon((r.map { geo.xy(it) } + geo.xy(r.first())).toTypedArray())
                if (!raw.isValid) out += Issue(Severity.WARNING, "Boundary ${i + 1} self-intersects; it was repaired automatically", "Move vertices so edges do not cross")
            }
            if (r.any { !it.isValid }) out += Issue(Severity.ERROR, "Boundary ${i + 1} has invalid coordinates", "Redraw the boundary")
        }
        if (wps.isEmpty()) { out += Issue(Severity.ERROR, "Mission has no waypoints", "Tap Generate Waypoints"); return out }
        if (wps.size < 2) out += Issue(Severity.ERROR, "DJI Fly missions need at least 2 waypoints", "Add another waypoint")
        if (wps.any { !it.point.isValid }) out += Issue(Severity.ERROR, "Invalid waypoint coordinates", "Delete or move the affected waypoints")

        // Exclusion zones: no waypoint inside, no leg crossing.
        geo.exclusion?.let { ex ->
            val core = ex.buffer(-0.5)
            val inside = wps.withIndex().filter { core.contains(GF.createPoint(geo.xy(it.value.point))) }.map { it.index + 1 }
            if (inside.isNotEmpty()) out += Issue(Severity.ERROR, "Waypoints inside an exclusion zone: ${inside.take(8).joinToString { "WP%03d".format(it) }}", "Move them out or regenerate the grid")
            val crossing = wps.zipWithNext().withIndex().filter { (_, ab) ->
                GF.createLineString(arrayOf(geo.xy(ab.first.point), geo.xy(ab.second.point))).intersects(core)
            }.map { it.index + 1 }
            if (crossing.isNotEmpty()) out += Issue(Severity.ERROR, "Route crosses an exclusion zone after WP%03d".format(crossing.first()) + if (crossing.size > 1) " (+${crossing.size - 1} more)" else "", "Regenerate or move waypoints so legs avoid the zone")
        }
        // Waypoints outside boundary
        geo.boundary?.let { b ->
            val allowed = b.buffer(max(0.0, s.bufferM) + s.lineExtensionM + 2.0)
            val outside = wps.count { !it.transit && !allowed.contains(GF.createPoint(geo.xy(it.point))) }
            if (outside > 0 && s.pattern != FlightPattern.MANUAL) out += Issue(Severity.WARNING, "$outside waypoint(s) lie outside the survey boundary", "Check manual edits or enlarge the boundary")
        }
        // Altitudes
        val heights = wps.map { Terrain.relativeHeight(p, it, dem) }
        if (heights.any { it < 2.0 }) out += Issue(Severity.ERROR, "Some waypoints are below 2 m relative to takeoff", "Raise those waypoint heights")
        if (heights.any { it > MAX_ALT_DJI }) out += Issue(Severity.ERROR, "Waypoint height exceeds DJI's 500 m limit", "Lower the flight height")
        else if (wps.any { p.heightOf(it) > LEGAL_ALT }) out += Issue(Severity.WARNING, "Height above 120 m AGL — exceeds the typical legal limit (Fiji CAAF/most regions)", "Lower the height unless you hold an authorisation")
        if (s.altitudeMode == com.aerogrid.app.model.AltitudeMode.TERRAIN_AGL && dem == null)
            out += Issue(Severity.WARNING, "No terrain data: heights are relative to takeoff, not true AGL", "Import a DEM (SRTM .hgt or .asc) in Advanced › Terrain, or fly over flat ground")
        if (dem != null) {
            val prof = Terrain.profile(p, dem, 25.0)
            if (prof.any { it.groundM == null }) out += Issue(Severity.WARNING, "DEM does not cover the whole route", "Import a DEM covering the survey area")
            Terrain.minClearance(prof)?.let { if (it < s.minClearanceM) out += Issue(Severity.WARNING, "Predicted terrain clearance as low as %.0f m".format(it), "Increase height or enable terrain-adjusted planning") }
        }
        // Waypoint limits and blocks
        val blocks = p.blocks
        if (blocks.isEmpty() && wps.size > s.maxWaypointsPerMission) out += Issue(Severity.ERROR, "${wps.size} waypoints exceed the ${s.maxWaypointsPerMission}-waypoint mission limit", "Split the mission into blocks")
        blocks.forEach { b ->
            val n = b.end - b.start + 1
            if (n > s.maxWaypointsPerMission) out += Issue(Severity.ERROR, "${b.name} has $n waypoints (limit ${s.maxWaypointsPerMission})", "Re-split the mission")
            if (n < 2) out += Issue(Severity.ERROR, "${b.name} has fewer than 2 waypoints", "Re-split the mission")
            val d = Timeline.duration(p, MissionSplitter.blockWaypoints(p, b))
            if (d > s.batteryMinutes * 60) out += Issue(Severity.WARNING, "${b.name} needs ${formatDuration(d)} — longer than one battery", "Reduce block size or battery limit")
        }
        // Speed and overlap
        val speed = stats.speedMs
        if (wps.any { (it.speedOverride ?: speed) > p.camera.maxSpeed }) out += Issue(Severity.ERROR, "Speed exceeds ${p.camera.maxSpeed} m/s aircraft waypoint limit", "Lower the speed")
        if (s.capture == CaptureStrategy.TIMED_INTERVAL && s.pattern != FlightPattern.MANUAL) {
            if (stats.frontOverlap < s.frontOverlap - 1) out += Issue(Severity.WARNING, "Speed %.1f m/s gives only %.0f%% forward overlap (target %.0f%%)".format(speed, stats.frontOverlap, s.frontOverlap), "Reduce speed to %.1f m/s or shorten the photo interval".format(stats.requiredSpeed))
            if (s.intervalSec !in p.camera.intervals(s.resolution)) out += Issue(Severity.WARNING, "${s.intervalSec} s timed shot is not a listed ${s.resolution.label} interval for this camera", "Choose one of ${p.camera.intervals(s.resolution).joinToString()} s")
            if (wps.any { it.speedOverride != null && it.speedOverride > stats.requiredSpeed + 0.05 }) out += Issue(Severity.WARNING, "Some waypoint speed overrides reduce forward overlap", "Keep line speeds ≤ %.1f m/s".format(stats.requiredSpeed))
            out += Issue(Severity.INFO, "Timed capture: set Photo › Timed Shot ${s.intervalSec} s (${s.resolution.label}) in DJI Fly and start it before the mission", "The KMZ does not start interval photography")
        }
        p.gridHeightM?.let { gh ->
            if (s.pattern != FlightPattern.MANUAL && s.lineSpacingM == null && gh > s.heightM + 0.5) {
                val used = Photogrammetry.footprint(p.camera, gh, s.zoom).widthM * (1 - s.sideOverlap / 100)
                val actual = (1 - used / Photogrammetry.footprint(p.camera, s.heightM, s.zoom).widthM) * 100
                out += Issue(Severity.WARNING, "Grid was generated for %.0f m; at %.0f m side overlap drops to %.0f%%".format(gh, s.heightM, actual), "Tap Generate Waypoints to re-space the flight lines")
            }
        }
        val side = Photogrammetry.actualSideOverlap(p.camera, s)
        if (s.pattern != FlightPattern.MANUAL && s.lineSpacingM != null && side < 0)
            out += Issue(Severity.WARNING, "Line spacing %.0f m is wider than the photo footprint (%.0f m) — there will be gaps between strips".format(Photogrammetry.lineSpacing(p.camera, s), stats.footprintW), "Reduce line spacing or fly higher")
        if (s.pattern != FlightPattern.MANUAL && side in 0.0..60.0)
            out += Issue(Severity.WARNING, "Side overlap only %.0f%% at %.0f m line spacing".format(side, Photogrammetry.lineSpacing(p.camera, s)), "Use ≤ %.0f m spacing for 70%% overlap".format(stats.footprintW * 0.3))
        if (s.pattern != FlightPattern.MANUAL && s.frontOverlap < 60) out += Issue(Severity.WARNING, "Low overlap may cause photogrammetry gaps", "Use ≥70% forward / ≥60% side overlap")
        if (s.gimbalPitch !in -90.0..35.0 || wps.any { it.gimbalPitch != null && it.gimbalPitch !in -90.0..35.0 }) out += Issue(Severity.ERROR, "Gimbal pitch outside −90°…+35°", "Adjust gimbal angle")
        val dup = wps.zipWithNext().count { (a, b) -> GeoMath.distance(a.point, b.point) < 0.5 }
        if (dup > 0) out += Issue(Severity.WARNING, "$dup pair(s) of waypoints are closer than 0.5 m", "Delete duplicate waypoints")
        if (p.takeoff == null) out += Issue(Severity.INFO, "Takeoff point not set — WP001 is assumed for altitude and transit estimates", "Set the takeoff marker in Advanced")
        out += Issue(Severity.INFO, "Routes are not guaranteed collision-free. Check obstacles, airspace and DJI Fly settings before flight.")
        return out
    }

    fun hasBlocking(issues: List<Issue>) = issues.any { it.severity == Severity.ERROR }
}

/** Pure editing operations on a project. Each returns a new immutable project. */
object MissionEditor {
    fun withWaypoints(p: Project, wps: List<Waypoint>, resplit: Boolean = true): Project {
        val np = p.copy(waypoints = wps, modified = System.currentTimeMillis())
        return if (resplit) np.copy(blocks = MissionSplitter.split(np)) else np
    }

    fun setGlobalHeight(p: Project, h: Double) = p.copy(settings = p.settings.copy(heightM = h.coerceIn(2.0, 500.0)))
    fun applyHeightToSelected(p: Project, ids: Set<Long>, h: Double) =
        withWaypoints(p, p.waypoints.map { if (it.id in ids) it.copy(heightOverride = h.coerceIn(2.0, 500.0)) else it }, false)
    fun applyHeightToAll(p: Project, h: Double) = setGlobalHeight(p, h)
    fun overwriteAllHeights(p: Project, h: Double) = withWaypoints(setGlobalHeight(p, h), p.waypoints.map { it.copy(heightOverride = null) }, false)
    fun resetAllHeights(p: Project) = withWaypoints(p, p.waypoints.map { it.copy(heightOverride = null) }, false)
    fun restoreDefaultHeight(p: Project) = setGlobalHeight(p, com.aerogrid.app.model.FlightSettings.DEFAULT.heightM)
    fun resetOverrides(p: Project, ids: Set<Long>) = withWaypoints(p, p.waypoints.map {
        if (it.id in ids) it.copy(heightOverride = null, speedOverride = null, gimbalPitch = null, zoom = null, hoverSec = 0.0,
            headingMode = com.aerogrid.app.model.HeadingMode.FOLLOW_ROUTE, turnMode = null) else it }, false)

    fun update(p: Project, ids: Set<Long>, f: (Waypoint) -> Waypoint) = withWaypoints(p, p.waypoints.map { if (it.id in ids) f(it) else it })

    fun moveWaypoints(p: Project, ids: Set<Long>, dLat: Double, dLon: Double) =
        update(p, ids) { it.copy(lat = it.lat + dLat, lon = it.lon + dLon, moved = true) }

    fun delete(p: Project, ids: Set<Long>) = withWaypoints(p, p.waypoints.filter { it.id !in ids })

    fun insertAfter(p: Project, index: Int, pt: GeoPoint): Project {
        val ref = p.waypoints.getOrNull(index)
        val wp = Waypoint(p.nextId, pt.lat, pt.lon, lineIndex = ref?.lineIndex ?: -1, action = if (p.settings.capture == CaptureStrategy.WAYPOINT_PHOTO) CameraAction.TAKE_PHOTO else CameraAction.NONE, moved = true)
        val l = p.waypoints.toMutableList(); l.add((index + 1).coerceIn(0, l.size), wp)
        return withWaypoints(p.copy(nextId = p.nextId + 1), l)
    }

    /** Insert at the route position nearest to [pt] (between the closest leg's endpoints), or append. */
    fun addNearest(p: Project, pt: GeoPoint): Project {
        val w = p.waypoints
        if (w.size < 2) return insertAfter(p, w.lastIndex, pt)
        val proj = LocalProjection(pt)
        val c = proj.toXY(pt)
        var best = w.lastIndex; var bestD = GeoMath.distance(w.last().point, pt)
        for (i in 0 until w.lastIndex) {
            val seg = GF.createLineString(arrayOf(proj.toXY(w[i].point), proj.toXY(w[i + 1].point)))
            val d = seg.distance(GF.createPoint(c))
            if (d < bestD) { bestD = d; best = i }
        }
        return insertAfter(p, best, pt)
    }

    fun reverse(p: Project) = withWaypoints(p, p.waypoints.reversed())
    fun reorder(p: Project, from: Int, to: Int): Project {
        val l = p.waypoints.toMutableList(); if (from !in l.indices || to !in l.indices) return p
        l.add(to, l.removeAt(from)); return withWaypoints(p, l)
    }
    fun resetToGenerated(p: Project) = withWaypoints(p, p.generated)

    /** Choose the route direction that minimises takeoff→start and finish→landing transit. */
    fun optimise(p: Project): Project {
        val w = p.waypoints; if (w.size < 2) return p
        val home = p.takeoff ?: return p
        val land = p.landing ?: home
        val fwd = GeoMath.distance(home, w.first().point) + GeoMath.distance(w.last().point, land)
        val rev = GeoMath.distance(home, w.last().point) + GeoMath.distance(w.first().point, land)
        return if (rev < fwd) reverse(p) else p
    }

    /**
     * Regenerate the route from geometry. Per-waypoint overrides (height, speed, gimbal, heading) are carried
     * to the nearest new waypoint within half a line spacing. Returns the project and number of edits that could not be kept.
     */
    fun regenerate(p: Project, dem: ElevationSource?): Pair<Project, PlanResult> {
        val r = RoutePlanner.generate(p, dem)
        if (p.settings.pattern == FlightPattern.MANUAL) return p to r
        val tol = Photogrammetry.lineSpacing(p.camera, p.settings) / 2
        val carried = r.waypoints.toMutableList()
        var lost = 0
        for (old in p.waypoints.filter { it.hasOverrides || it.moved }) {
            if (!old.hasOverrides) { lost++; continue }
            val idx = carried.indices.minByOrNull { GeoMath.distance(carried[it].point, old.point) }
            if (idx != null && GeoMath.distance(carried[idx].point, old.point) <= tol) {
                carried[idx] = carried[idx].copy(heightOverride = old.heightOverride, speedOverride = old.speedOverride, gimbalPitch = old.gimbalPitch,
                    headingMode = old.headingMode, headingDeg = old.headingDeg, zoom = old.zoom, hoverSec = old.hoverSec, turnMode = old.turnMode)
            } else lost++
        }
        val warnings = if (lost > 0) r.warnings + "$lost manual edit(s) could not be mapped onto the new grid" else r.warnings
        val np = withWaypoints(p.copy(generated = r.waypoints, nextId = r.nextId, gridHeightM = p.settings.heightM, gridBearingDeg = r.bearingDeg), carried)
        return np to r.copy(warnings = warnings)
    }

    // ---- Polygon editing: kind 0 = boundary, 1 = exclusion, 2 = corridor (polyline) ----
    fun rings(p: Project, kind: Int): List<List<GeoPoint>> = when (kind) { 0 -> p.boundaries; 1 -> p.exclusions; else -> if (p.corridor.isEmpty()) emptyList() else listOf(p.corridor) }
    fun withRings(p: Project, kind: Int, r: List<List<GeoPoint>>) = when (kind) {
        0 -> p.copy(boundaries = r); 1 -> p.copy(exclusions = r); else -> p.copy(corridor = r.firstOrNull() ?: emptyList())
    }
    fun moveVertex(p: Project, kind: Int, ring: Int, v: Int, pt: GeoPoint) =
        withRings(p, kind, rings(p, kind).mapIndexed { i, r -> if (i == ring) r.mapIndexed { j, q -> if (j == v) pt else q } else r })
    fun insertVertex(p: Project, kind: Int, ring: Int, afterV: Int, pt: GeoPoint) =
        withRings(p, kind, rings(p, kind).mapIndexed { i, r -> if (i == ring) r.toMutableList().apply { add(afterV + 1, pt) } else r })
    fun deleteVertex(p: Project, kind: Int, ring: Int, v: Int) =
        withRings(p, kind, rings(p, kind).mapIndexedNotNull { i, r -> if (i == ring) r.filterIndexed { j, _ -> j != v }.takeIf { it.size >= (if (kind == 2) 2 else 3) } else r })
}

/** Deterministic flight simulation timeline. */
class Simulation(private val p: Project) {
    data class State(val pos: GeoPoint, val headingDeg: Double, val wpIndex: Int, val elapsed: Double, val total: Double, val heightM: Double, val photos: List<GeoPoint>, val done: Boolean)
    private data class Leg(val a: Waypoint, val b: Waypoint, val i: Int, val t0: Double, val stop: Double, val move: Double)

    private val legs = ArrayList<Leg>()
    val total: Double
    private val photoTimes = ArrayList<Pair<Double, GeoPoint>>()

    init {
        var t = 0.0
        val w = p.waypoints
        val interval = p.settings.intervalSec.toDouble()
        var nextShot = 0.0
        for (i in 0 until w.size - 1) {
            val stop = Timeline.stopTime(p, w[i])
            if (w[i].action == CameraAction.TAKE_PHOTO) photoTimes += (t + stop * 0.5) to w[i].point
            val d = GeoMath.distance(w[i].point, w[i + 1].point)
            val move = d / Timeline.legSpeed(p, w[i])
            legs += Leg(w[i], w[i + 1], i, t, stop, move)
            if (p.settings.capture == CaptureStrategy.TIMED_INTERVAL && !w[i].transit && !w[i + 1].transit && w[i].lineIndex == w[i + 1].lineIndex && move > 0) {
                if (nextShot < t + stop) nextShot = t + stop
                while (nextShot <= t + stop + move) {
                    photoTimes += nextShot to GeoMath.interpolate(w[i].point, w[i + 1].point, (nextShot - t - stop) / move); nextShot += interval
                }
            }
            t += stop + move
        }
        w.lastOrNull()?.let { if (it.action == CameraAction.TAKE_PHOTO) photoTimes += t to it.point }
        total = t
    }

    val photoPositions: List<GeoPoint> get() = photoTimes.map { it.second }

    fun at(time: Double): State? {
        val w = p.waypoints; if (w.isEmpty()) return null
        val t = time.coerceIn(0.0, total)
        val photos = photoTimes.takeWhile { it.first <= t }.map { it.second }
        if (legs.isEmpty()) return State(w[0].point, 0.0, 0, 0.0, 0.0, p.heightOf(w[0]), photos, true)
        val leg = legs.lastOrNull { it.t0 <= t } ?: legs.first()
        val brg = GeoMath.bearing(leg.a.point, leg.b.point)
        val local = t - leg.t0
        if (local < leg.stop || leg.move <= 0) return State(leg.a.point, brg, leg.i, t, total, p.heightOf(leg.a), photos, t >= total)
        val f = ((local - leg.stop) / leg.move).coerceIn(0.0, 1.0)
        val h = p.heightOf(leg.a) + (p.heightOf(leg.b) - p.heightOf(leg.a)) * f
        return State(GeoMath.interpolate(leg.a.point, leg.b.point, f), brg, if (f >= 1.0) leg.i + 1 else leg.i, t, total, h, photos, t >= total)
    }
}
