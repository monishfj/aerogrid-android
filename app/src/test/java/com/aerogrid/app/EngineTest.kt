package com.aerogrid.app

import com.aerogrid.app.Fixtures.exclusionCenter
import com.aerogrid.app.Fixtures.rect400x300
import com.aerogrid.app.Fixtures.uShape
import com.aerogrid.app.engine.GF
import com.aerogrid.app.engine.GeoMath
import com.aerogrid.app.engine.LocalProjection
import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.engine.MissionSplitter
import com.aerogrid.app.engine.Photogrammetry
import com.aerogrid.app.engine.RoutePlanner
import com.aerogrid.app.engine.Severity
import com.aerogrid.app.engine.Simulation
import com.aerogrid.app.engine.Stats
import com.aerogrid.app.engine.SurveyGeometry
import com.aerogrid.app.engine.Timeline
import com.aerogrid.app.engine.Validator
import com.aerogrid.app.io.AppJson
import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.CameraProfile
import com.aerogrid.app.model.CaptureStrategy
import com.aerogrid.app.model.FlightPattern
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.PhotoResolution
import com.aerogrid.app.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

class GeometryTest {
    @Test fun projectionRoundTripAndScale() {
        val pr = LocalProjection(Fixtures.TAVUA)
        val g = GeoPoint(-17.45, 177.87)
        val back = pr.toGeo(pr.toXY(g))
        assertEquals(g.lat, back.lat, 1e-10); assertEquals(g.lon, back.lon, 1e-10)
        // WGS84 reference lengths of 1 degree at the origin latitude
        val phi = Math.toRadians(Fixtures.TAVUA.lat)
        val mLat = 111132.92 - 559.82 * kotlin.math.cos(2 * phi) + 1.175 * kotlin.math.cos(4 * phi)
        val mLon = 111412.84 * kotlin.math.cos(phi) - 93.5 * kotlin.math.cos(3 * phi)
        val c = pr.toXY(GeoPoint(Fixtures.TAVUA.lat + 0.01, Fixtures.TAVUA.lon + 0.01))
        assertEquals(mLat * 0.01, c.y, 0.5); assertEquals(mLon * 0.01, c.x, 0.5)
        assertTrue(sqrt(c.x * c.x + c.y * c.y) > 0)
    }

    @Test fun polygonAreaIncludingConcave() {
        assertEquals(120_000.0, GeoMath.area(rect400x300), 120.0)
        assertEquals(120_000.0 - 120.0 * 200, GeoMath.area(uShape), 120.0)
    }

    @Test fun polygonEditing() {
        var p = Fixtures.project()
        p = MissionEditor.insertVertex(p, 0, 0, 0, Fixtures.ring(200.0 to -50.0)[0])
        assertEquals(5, p.boundaries[0].size)
        p = MissionEditor.moveVertex(p, 0, 0, 1, Fixtures.ring(200.0 to -80.0)[0])
        assertTrue(GeoMath.area(p.boundaries[0]) > 120_000 + 400 * 80 / 2 - 500)
        p = MissionEditor.deleteVertex(p, 0, 0, 1)
        assertEquals(4, p.boundaries[0].size)
        // deleting below 3 vertices removes the ring
        repeat(2) { p = MissionEditor.deleteVertex(p, 0, 0, 0) }
        assertTrue(p.boundaries.isEmpty())
    }

    @Test fun selfIntersectingBoundaryIsRepairedAndFlagged() {
        val bow = Fixtures.ring(0.0 to 0.0, 300.0 to 300.0, 300.0 to 0.0, 0.0 to 300.0)
        val p = Fixtures.project(bow)
        assertTrue(SurveyGeometry(p).boundary!!.isValid)
        assertTrue(Validator.validate(p, null).any { it.message.contains("self-intersects") })
    }
}

class PhotogrammetryTest {
    private val cam = CameraProfile.MINI_5_PRO
    @Test fun cameraProfileMatchesMini5ProSpec() {
        assertEquals(84.0, cam.dfovDeg, 0.01)
        assertEquals(4.0 / 3.0, cam.sensorWidthMm / cam.sensorHeightMm, 1e-9)
        // horizontal FOV must be derived, not equal to diagonal FOV
        val expectedH = Math.toDegrees(2 * atan(tan(Math.toRadians(42.0)) * 8192 / 10240.0))
        assertEquals(expectedH, cam.hfovDeg, 0.01)
        assertTrue(cam.hfovDeg < cam.dfovDeg)
    }

    @Test fun footprintAndGsd() {
        val fp = Photogrammetry.footprint(cam, 50.0)
        assertEquals(2 * 50 * tan(Math.toRadians(cam.hfovDeg / 2)), fp.widthM, 1e-6)
        assertEquals(fp.widthM * 0.75, fp.heightM, 1e-6)
        val gsd = Photogrammetry.gsdCm(cam, PhotoResolution.MP50, 50.0)
        assertEquals(fp.widthM / 8192 * 100, gsd, 1e-9)
        assertEquals(gsd * 2, Photogrammetry.gsdCm(cam, PhotoResolution.MP12, 50.0), 1e-9)
        assertEquals(50.0, Photogrammetry.heightForGsd(cam, PhotoResolution.MP50, gsd), 1e-6)
    }

    @Test fun overlapSpacingAndSpeed() {
        val s = Fixtures.project().settings
        val fp = Photogrammetry.footprint(cam, s.heightM)
        assertEquals(fp.widthM * 0.25, Photogrammetry.lineSpacing(cam, s), 1e-9)
        assertEquals(fp.heightM * 0.20, Photogrammetry.photoSpacing(cam, s), 1e-9)
        val v = Photogrammetry.requiredSpeed(cam, s)
        assertEquals(fp.heightM * 0.2 / 5, v, 1e-9)
        assertEquals(80.0, Photogrammetry.actualFrontOverlap(cam, s, v), 1e-6)
        assertTrue(Photogrammetry.actualFrontOverlap(cam, s, v * 2) < 80)
    }

    @Test fun durationIncludesLegsStopsAndTransit() {
        val p = Fixtures.generated()
        val inner = Timeline.duration(p, p.waypoints, includeTransit = false)
        val legs = p.waypoints.zipWithNext().sumOf { (a, b) -> GeoMath.distance(a.point, b.point) / Timeline.legSpeed(p, a) }
        assertEquals(legs + p.waypoints.size * 3.0, inner, 1e-6)
        assertTrue(Timeline.duration(p, p.waypoints) > inner)
    }
}

class PlannerTest {
    private fun insideCount(p: Project, tol: Double = 1.0): Int {
        val g = SurveyGeometry(p); val area = g.boundary!!.buffer(tol)
        return p.waypoints.count { !it.transit && !area.contains(GF.createPoint(g.xy(it.point))) }
    }

    @Test fun lawnmowerGridCoversRectangle() {
        val p = Fixtures.generated()
        val s = Stats.compute(p)
        val spacing = Photogrammetry.lineSpacing(p.camera, p.settings)
        assertEquals(kotlin.math.ceil(300 / spacing).toInt(), s.lineCount) // auto bearing along 400 m side
        assertEquals(0, insideCount(p))
        assertEquals(2 * s.lineCount, p.waypoints.count { !it.transit })
        assertEquals(90.0, p.gridBearingDeg!!, 0.5)
    }

    @Test fun linesAlternateDirection() {
        val p = Fixtures.generated()
        val w = p.waypoints.filter { !it.transit }
        val b1 = GeoMath.bearing(w[0].point, w[1].point); val b2 = GeoMath.bearing(w[2].point, w[3].point)
        assertEquals(180.0, abs(GeoMath.normalize180(b1 - b2)), 1.0)
        // consecutive lines are adjacent: end of line 1 close to start of line 2
        assertTrue(GeoMath.distance(w[1].point, w[2].point) < Photogrammetry.lineSpacing(p.camera, p.settings) * 1.5)
    }

    @Test fun gridRotationChangesBearing() {
        val p = Fixtures.project().let { it.copy(settings = it.settings.copy(bearingDeg = 30.0)) }
        val r = MissionEditor.regenerate(p, null).first
        val w = r.waypoints.filter { !it.transit }
        val b = GeoMath.bearing(w[0].point, w[1].point)
        assertTrue(abs(GeoMath.normalize180(b - 30)) < 1 || abs(GeoMath.normalize180(b - 210)) < 1)
        assertEquals(0, insideCount(r))
    }

    @Test fun concavePolygonClippedAndConnectedInside() {
        val p = Fixtures.generated(Fixtures.project(uShape).let { it.copy(settings = it.settings.copy(bearingDeg = 90.0)) })
        assertEquals(0, insideCount(p))
        // no leg may cross the notch (outside the polygon) — transits route around it
        val g = SurveyGeometry(p); val allowed = g.boundary!!.buffer(1.0)
        p.waypoints.zipWithNext().forEach { (a, b) -> assertTrue("leg leaves polygon", allowed.covers(GF.createLineString(arrayOf(g.xy(a.point), g.xy(b.point))))) }
    }

    @Test fun exclusionZoneNeverEnteredOrCrossed() {
        val p = Fixtures.generated(Fixtures.project().copy(exclusions = listOf(exclusionCenter)))
        val g = SurveyGeometry(p); val ex = g.exclusion!!.buffer(-0.5)
        assertTrue(p.waypoints.none { ex.contains(GF.createPoint(g.xy(it.point))) })
        p.waypoints.zipWithNext().forEach { (a, b) -> assertFalse(GF.createLineString(arrayOf(g.xy(a.point), g.xy(b.point))).intersects(ex)) }
        assertTrue(Validator.validate(p, null).none { it.severity == Severity.ERROR && it.message.contains("exclusion") })
    }

    @Test fun crosshatchCorridorPerimeterPatterns() {
        val base = Fixtures.project()
        val single = Fixtures.generated(base)
        val cross = MissionEditor.regenerate(base.copy(settings = base.settings.copy(pattern = FlightPattern.CROSSHATCH)), null).first
        assertTrue(Stats.compute(cross).lineCount > Stats.compute(single).lineCount)
        val corr = MissionEditor.regenerate(base.copy(boundaries = emptyList(), corridor = Fixtures.ring(0.0 to 0.0, 500.0 to 0.0, 800.0 to 300.0),
            settings = base.settings.copy(pattern = FlightPattern.CORRIDOR, corridorWidthM = 60.0)), null).first
        assertTrue(corr.waypoints.size >= 6)
        val per = MissionEditor.regenerate(base.copy(settings = base.settings.copy(pattern = FlightPattern.PERIMETER)), null).first
        assertEquals(5, per.waypoints.size) // closed ring of 4 corners
    }

    @Test fun waypointPhotoCaptureDensifiesWithActions() {
        val base = Fixtures.project()
        val p = MissionEditor.regenerate(base.copy(settings = base.settings.copy(capture = CaptureStrategy.WAYPOINT_PHOTO)), null).first
        assertTrue(p.waypoints.size > Fixtures.generated(base).waypoints.size * 3)
        assertTrue(p.waypoints.filter { !it.transit }.all { it.action == CameraAction.TAKE_PHOTO })
        val spacing = Photogrammetry.photoSpacing(p.camera, p.settings)
        val w = p.waypoints
        assertTrue(GeoMath.distance(w[0].point, w[1].point) <= spacing + 0.01)
    }

    @Test fun multiPolygonMapping() {
        val second = Fixtures.ring(800.0 to 0.0, 1000.0 to 0.0, 1000.0 to 200.0, 800.0 to 200.0)
        val p = Fixtures.generated(Fixtures.project().copy(boundaries = listOf(rect400x300, second)))
        val g = SurveyGeometry(p)
        val comps = (0 until g.boundary!!.numGeometries).map { g.boundary.getGeometryN(it).buffer(1.0) }
        assertTrue(comps.all { c -> p.waypoints.any { c.contains(GF.createPoint(g.xy(it.point))) } })
    }
}

class EditingTest {
    @Test fun globalAltitudeAndIndividualOverrides() {
        var p = Fixtures.generated()
        val wp12 = p.waypoints[11 % p.waypoints.size]
        p = MissionEditor.applyHeightToSelected(p, setOf(wp12.id), 65.0)
        p = MissionEditor.setGlobalHeight(p, 60.0)
        assertEquals(65.0, p.heightOf(p.waypoints.first { it.id == wp12.id }), 0.0)
        assertTrue(p.waypoints.filter { it.id != wp12.id }.all { p.heightOf(it) == 60.0 })
        p = MissionEditor.overwriteAllHeights(p, 45.0)
        assertTrue(p.waypoints.all { it.heightOverride == null && p.heightOf(it) == 45.0 })
        p = MissionEditor.restoreDefaultHeight(p); assertEquals(50.0, p.settings.heightM, 0.0)
    }

    @Test fun bulkEditAndMoveMultiple() {
        var p = Fixtures.generated()
        val ids = p.waypoints.take(3).map { it.id }.toSet()
        p = MissionEditor.update(p, ids) { it.copy(speedOverride = 4.0, gimbalPitch = -60.0) }
        assertEquals(3, p.waypoints.count { it.speedOverride == 4.0 && it.gimbalPitch == -60.0 })
        val before = p.waypoints.take(3)
        p = MissionEditor.moveWaypoints(p, ids, 0.0001, 0.0)
        p.waypoints.take(3).zip(before).forEach { (a, b) -> assertEquals(b.lat + 0.0001, a.lat, 1e-12); assertTrue(a.moved) }
        p = MissionEditor.resetOverrides(p, ids)
        assertTrue(p.waypoints.take(3).all { it.speedOverride == null && it.gimbalPitch == null })
    }

    @Test fun addDeleteReverseReorderReset() {
        val g = Fixtures.generated(); var p = g
        val n = p.waypoints.size
        val mid = GeoMath.interpolate(p.waypoints[0].point, p.waypoints[1].point, 0.5)
        p = MissionEditor.addNearest(p, mid)
        assertEquals(n + 1, p.waypoints.size); assertEquals(mid, p.waypoints[1].point)
        assertEquals(p.waypoints.map { it.id }.distinct().size, p.waypoints.size)
        p = MissionEditor.delete(p, setOf(p.waypoints[1].id)); assertEquals(n, p.waypoints.size)
        val first = p.waypoints.first().id
        p = MissionEditor.reverse(p); assertEquals(first, p.waypoints.last().id)
        val id0 = p.waypoints[0].id
        p = MissionEditor.reorder(p, 0, 2); assertEquals(id0, p.waypoints[2].id)
        p = MissionEditor.resetToGenerated(p); assertEquals(g.waypoints, p.waypoints)
    }

    @Test fun regenerationPreservesHeightOverridesAndReportsLostMoves() {
        var p = Fixtures.generated()
        val target = p.waypoints[4]
        p = MissionEditor.applyHeightToSelected(p, setOf(target.id), 65.0)
        p = MissionEditor.moveWaypoints(p, setOf(p.waypoints[6].id), 0.0003, 0.0)
        val (np, r) = MissionEditor.regenerate(p.copy(settings = p.settings.copy(frontOverlap = 85.0)), null)
        assertEquals(1, np.waypoints.count { it.heightOverride == 65.0 })
        assertTrue(r.warnings.any { it.contains("could not be mapped") })
    }

    @Test fun quickAdvancedSwitchKeepsMissionState() {
        // Modes share one Project; switching is a UI flag. Verify advanced edits survive persistence round-trip.
        var p = Fixtures.generated()
        val n = p.waypoints.size
        p = MissionEditor.applyHeightToSelected(p, setOf(p.waypoints[19 % n].id), 60.0)
        p = MissionEditor.moveWaypoints(p, setOf(p.waypoints[3].id), 0.0001, 0.0001)
        p = p.copy(exclusions = listOf(Fixtures.ring(10.0 to 10.0, 30.0 to 10.0, 30.0 to 30.0)), settings = p.settings.copy(speedMs = 3.0))
        assertTrue(p.hasAdvancedConfig)
        val restored = AppJson.decodeFromString(Project.serializer(), AppJson.encodeToString(Project.serializer(), p))
        assertEquals(p, restored)
        assertEquals(n, restored.waypoints.size)
        assertEquals(60.0, restored.waypoints[19 % n].heightOverride!!, 0.0)
    }
}

class SplittingTest {
    @Test fun splitsByWaypointLimitWithoutGaps() {
        val base = Fixtures.project(Fixtures.ring(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 900.0, 0.0 to 900.0))
        val p0 = MissionEditor.regenerate(base.copy(settings = base.settings.copy(capture = CaptureStrategy.WAYPOINT_PHOTO, batteryMinutes = 999.0)), null).first
        assertTrue(p0.waypoints.size > 200)
        val blocks = p0.blocks
        assertTrue(blocks.size >= 2)
        assertEquals(0, blocks.first().start); assertEquals(p0.waypoints.lastIndex, blocks.last().end)
        blocks.forEach { assertTrue(it.end - it.start + 1 <= 200) }
        blocks.zipWithNext().forEach { (a, b) -> assertTrue("gap between blocks", b.start <= a.end + 1) }
        assertEquals("Tavua_Test_Block_01", blocks[0].name)
        assertTrue(Validator.validate(p0, null).none { it.severity == Severity.ERROR && it.message.contains("limit") })
    }

    @Test fun splitsByBatteryDuration() {
        val p = Fixtures.generated(Fixtures.project(Fixtures.ring(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 900.0, 0.0 to 900.0)))
        val blocks = MissionSplitter.split(p, maxWp = 200, maxSec = 600.0)
        assertTrue(blocks.size > 1)
        blocks.forEach { b -> val wps = MissionSplitter.blockWaypoints(p, b); assertTrue(wps.size <= 2 || Timeline.duration(p, wps) <= 600.0 * 1.5) }
        // every flight line appears in some block
        val lines = p.waypoints.map { it.lineIndex }.filter { it >= 0 }.toSet()
        assertEquals(lines, blocks.flatMap { MissionSplitter.blockWaypoints(p, it).map { w -> w.lineIndex } }.filter { it >= 0 }.toSet())
    }
}

class SimulationValidationTest {
    @Test fun simulationProgressesAndCapturesPhotos() {
        val p = Fixtures.generated()
        val sim = Simulation(p)
        assertTrue(sim.total > 0)
        val s0 = sim.at(0.0)!!; val mid = sim.at(sim.total / 2)!!; val end = sim.at(sim.total + 10)!!
        assertEquals(0, s0.wpIndex); assertTrue(mid.wpIndex > 0); assertTrue(end.done)
        assertTrue(end.photos.size >= Stats.compute(p).photoCount * 0.7)
    }

    @Test fun validatorSeparatesErrorsFromWarnings() {
        val empty = Validator.validate(Project(), null)
        assertTrue(Validator.hasBlocking(empty))
        var p = Fixtures.generated()
        assertFalse(Validator.hasBlocking(Validator.validate(p, null)))
        assertTrue(Validator.validate(p, null).any { it.severity == Severity.WARNING && it.message.contains("No terrain data") })
        p = p.copy(settings = p.settings.copy(speedMs = 12.0))
        assertTrue(Validator.validate(p, null).any { it.message.contains("forward overlap") })
        p = MissionEditor.setGlobalHeight(p, 30.0)
        assertTrue(Validator.validate(p, null).any { it.message.contains("side overlap drops") })
    }

    @Test fun noFabricatedTerrainWithoutDem() {
        val p = Fixtures.generated()
        assertTrue(com.aerogrid.app.engine.Terrain.profile(p, null).all { it.groundM == null })
        assertNull(com.aerogrid.app.engine.Terrain.minClearance(com.aerogrid.app.engine.Terrain.profile(p, null)))
        assertNotEquals(0, p.waypoints.size)
        assertNotEquals(null, RoutePlanner.autoBearing(SurveyGeometry(p).boundary!!))
    }
}
