package com.aerogrid.app

import com.aerogrid.app.engine.AscDem
import com.aerogrid.app.engine.HgtDem
import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.io.BackupCodec
import com.aerogrid.app.io.GeoExport
import com.aerogrid.app.io.GeoImport
import com.aerogrid.app.kmz.KmzValidator
import com.aerogrid.app.kmz.WpmlReader
import com.aerogrid.app.kmz.WpmlWriter
import com.aerogrid.app.model.AltitudeMode
import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.DroneInfo
import com.aerogrid.app.model.HeadingMode
import com.aerogrid.app.model.Project
import com.aerogrid.app.transfer.DjiMission
import com.aerogrid.app.transfer.MissionFs
import com.aerogrid.app.transfer.TransferAvailability
import com.aerogrid.app.transfer.TransferEngine
import com.aerogrid.app.transfer.TransferStep
import com.aerogrid.app.transfer.parseStatLines
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WpmlTest {
    private fun mission(): Project {
        var p = Fixtures.generated()
        p = MissionEditor.applyHeightToSelected(p, setOf(p.waypoints[2].id), 65.0)
        p = MissionEditor.update(p, setOf(p.waypoints[3].id)) { it.copy(speedOverride = 3.5, gimbalPitch = -45.0, headingMode = HeadingMode.FIXED, headingDeg = 120.0, hoverSec = 2.0, action = CameraAction.TAKE_PHOTO) }
        return p
    }

    @Test fun kmzStructureAndContent() {
        val p = mission()
        val out = WpmlWriter.build(p, p.waypoints, DroneInfo(), null)
        val files = WpmlReader.unzip(ByteArrayInputStream(out.bytes))
        assertEquals(setOf("wpmz/template.kml", "wpmz/waylines.wpml"), files.keys)
        val wl = String(files["wpmz/waylines.wpml"]!!)
        assertTrue(wl.contains("xmlns:wpml=\"http://www.uav.com/wpmz/1.0.2\""))
        assertTrue(wl.contains("<wpml:droneEnumValue>68</wpml:droneEnumValue>"))
        assertTrue(wl.contains("<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>"))
        assertTrue(wl.contains("<wpml:finishAction>goHome</wpml:finishAction>"))
        assertTrue(wl.contains("<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>"))
        assertTrue(wl.contains("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>"))
        assertTrue(wl.contains("<wpml:hoverTime>2</wpml:hoverTime>"))
        assertTrue(wl.contains("<wpml:executeHeight>65</wpml:executeHeight>"))
        assertEquals(p.waypoints.size, Regex("<Placemark>").findAll(wl).count())
        val tp = String(files["wpmz/template.kml"]!!)
        assertTrue(tp.contains("<wpml:templateType>waypoint</wpml:templateType>"))
        assertEquals(WpmlWriter.sha256(out.bytes), out.sha256)
    }

    @Test fun validatorAcceptsGeneratedAndRejectsBroken() {
        val p = mission()
        val out = WpmlWriter.build(p, p.waypoints, DroneInfo(), null)
        val ok = KmzValidator.validate(out.bytes, 200, DroneInfo().namespace)
        assertTrue(ok.errors.toString(), ok.ok); assertEquals(p.waypoints.size, ok.waypointCount)
        // missing waylines file
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("wpmz/template.kml")); z.write(WpmlReader.unzip(ByteArrayInputStream(out.bytes))["wpmz/template.kml"]!!); z.closeEntry() }
        assertFalse(KmzValidator.validate(bos.toByteArray()).ok)
        assertFalse(KmzValidator.validate("not a zip".toByteArray()).ok)
        // over waypoint limit
        assertFalse(KmzValidator.validate(out.bytes, maxWaypoints = 3).ok)
    }

    @Test fun importRoundTripPreservesWaypointSettings() {
        val p = mission()
        val out = WpmlWriter.build(p, p.waypoints, DroneInfo(droneEnumValue = 91, droneSubEnumValue = 1, namespace = "http://www.dji.com/wpmz/1.0.6"), null)
        val m = WpmlReader.read(out.bytes)
        assertEquals(p.waypoints.size, m.waypoints.size)
        assertEquals(91, m.drone.droneEnumValue); assertEquals(1, m.drone.droneSubEnumValue); assertEquals("http://www.dji.com/wpmz/1.0.6", m.drone.namespace)
        p.waypoints.zip(m.waypoints).forEach { (a, b) -> assertEquals(a.lat, b.lat, 1e-9); assertEquals(a.lon, b.lon, 1e-9); assertEquals(p.heightOf(a), b.heightOverride!!, 0.01) }
        val w3 = m.waypoints[3]
        assertEquals(3.5, w3.speedOverride!!, 0.01); assertEquals(-45.0, w3.gimbalPitch!!, 0.01); assertEquals(HeadingMode.FIXED, w3.headingMode)
        assertEquals(120.0, w3.headingDeg, 0.1); assertEquals(2.0, w3.hoverSec, 0.01); assertEquals(CameraAction.TAKE_PHOTO, w3.action)
    }

    @Test fun terrainAdjustedHeightsWrittenRelativeToTakeoff() {
        val dem = object : com.aerogrid.app.engine.ElevationSource {
            override val description = "ramp"
            override fun elevation(lat: Double, lon: Double) = (lon - 177.8646) * 100_000 // ~1 m per 1e-5 deg east
        }
        val p = Fixtures.generated().let { it.copy(takeoff = Fixtures.TAVUA, settings = it.settings.copy(altitudeMode = AltitudeMode.TERRAIN_AGL)) }
        val east = p.waypoints.maxBy { it.lon }
        val rel = Terrain.relativeHeight(p, east, dem)
        assertEquals(50.0 + (east.lon - 177.8646) * 100_000, rel, 1e-6)
        val wl = String(WpmlReader.unzip(ByteArrayInputStream(WpmlWriter.build(p, p.waypoints, DroneInfo(), dem).bytes))["wpmz/waylines.wpml"]!!)
        assertTrue(wl.contains("<wpml:executeHeight>%.2f".format(java.util.Locale.US, rel).trimEnd('0').trimEnd('.')))
    }
}

class FormatsTest {
    @Test fun kmlGeoJsonGpxRoundTrips() {
        val p = Fixtures.generated(Fixtures.project().copy(exclusions = listOf(Fixtures.exclusionCenter)))
        val k = GeoImport.kml(GeoExport.kml(p, null).toByteArray())
        assertEquals(1, k.boundaries.size); assertEquals(1, k.exclusions.size); assertEquals(p.waypoints.size, k.points.size)
        assertEquals(p.boundaries[0][0].lat, k.boundaries[0][0].lat, 1e-7)
        val g = GeoImport.geojson(GeoExport.geojson(p))
        assertEquals(1, g.boundaries.size); assertEquals(1, g.exclusions.size); assertEquals(1, g.lines.size)
        val x = GeoImport.gpx(GeoExport.gpx(p).toByteArray())
        assertEquals(p.waypoints.size, x.lines.single().size)
        val csv = GeoExport.csv(p, null).lines().filter { it.isNotBlank() }
        assertEquals(p.waypoints.size + 1, csv.size)
        assertEquals("djikmz".take(3), GeoImport.detect("m.kmz", WpmlWriter.build(p, p.waypoints, DroneInfo(), null).bytes))
    }

    @Test fun backupAndRestore() {
        val a = Fixtures.generated(); val b = Project(name = "Second")
        val bytes = BackupCodec.encode(listOf(a, b))
        val back = BackupCodec.decode(bytes)
        assertEquals(listOf(a, b), back)
        assertEquals(listOf(a), BackupCodec.decode(BackupCodec.projectJson(a).toByteArray()))
    }
}

/** In-memory MissionFs for transfer recovery tests. */
class FakeFs(var failWrite: Boolean = false, var corruptWrite: Boolean = false, var failBackup: Boolean = false, var accessible: Boolean = true) : MissionFs {
    val files = HashMap<String, ByteArray>()
    override val name = "fake"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    override suspend fun probe() = if (accessible) "/w" else null
    override suspend fun list(root: String) = files.filterKeys { it.startsWith("$root/") && it.endsWith(".kmz") }.map { DjiMission(it.key.substringAfterLast('/').removeSuffix(".kmz"), it.key, it.value.size.toLong(), 0) }
    override suspend fun sha256(path: String) = files[path]?.let(::sha)
    override suspend fun copy(from: String, to: String): Boolean {
        if (failBackup && to.startsWith("/backup")) return false
        files[to] = files[from] ?: return false; return true
    }
    override suspend fun writeStaged(stagedPath: String, to: String): Boolean {
        if (failWrite) { files[to] = ByteArray(0); return false }
        files[to] = if (corruptWrite) files[stagedPath]!!.copyOf().also { it[0] = 0 } else files[stagedPath]!!; return true
    }
    override fun backupPathFor(m: DjiMission, time: Long) = "/backup/${m.id}_$time.kmz"
    override suspend fun listBackups() = files.keys.filter { it.startsWith("/backup") }
}

class TransferTest {
    private val original = "placeholder".toByteArray()
    private val staged = "new mission".toByteArray()
    private fun setup(fs: FakeFs): DjiMission { fs.files["/w/ABC/ABC.kmz"] = original; fs.files["/stage/m.kmz"] = staged; return DjiMission("ABC", "/w/ABC/ABC.kmz", 11, 0) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun successfulReplaceBacksUpAndVerifies() = runTest {
        val fs = FakeFs(); val t = setup(fs)
        val r = TransferEngine(fs).replace(t, "/stage/m.kmz", sha(staged), now = 1)
        assertTrue(r.success)
        assertEquals(listOf(TransferStep.PLACEHOLDER_SELECTED, TransferStep.BACKUP_CREATED, TransferStep.FILE_COPIED, TransferStep.FILE_VERIFIED, TransferStep.READY), r.log.map { it.step })
        assertTrue(fs.files["/w/ABC/ABC.kmz"]!!.contentEquals(staged))
        assertTrue(fs.files["/backup/ABC_1.kmz"]!!.contentEquals(original))
        assertTrue(TransferEngine(fs).restore("/backup/ABC_1.kmz", t)); assertTrue(fs.files["/w/ABC/ABC.kmz"]!!.contentEquals(original))
    }

    @Test fun writeFailureRestoresOriginal() = runTest {
        val fs = FakeFs(failWrite = true); val t = setup(fs)
        val r = TransferEngine(fs).replace(t, "/stage/m.kmz", sha(staged))
        assertFalse(r.success); assertEquals(TransferStep.RESTORED, r.log.last().step)
        assertTrue(fs.files["/w/ABC/ABC.kmz"]!!.contentEquals(original))
    }

    @Test fun hashMismatchRestoresOriginal() = runTest {
        val fs = FakeFs(corruptWrite = true); val t = setup(fs)
        val r = TransferEngine(fs).replace(t, "/stage/m.kmz", sha(staged))
        assertFalse(r.success); assertTrue(r.log.any { it.detail.contains("hash mismatch") })
        assertTrue(fs.files["/w/ABC/ABC.kmz"]!!.contentEquals(original))
    }

    @Test fun backupFailureLeavesPlaceholderUntouched() = runTest {
        val fs = FakeFs(failBackup = true); val t = setup(fs)
        val r = TransferEngine(fs).replace(t, "/stage/m.kmz", sha(staged))
        assertFalse(r.success); assertNull(r.backupPath)
        assertTrue(fs.files["/w/ABC/ABC.kmz"]!!.contentEquals(original))
        assertFalse(r.log.any { it.step == TransferStep.FILE_COPIED })
    }

    @Test fun permissionAndAvailabilityHandling() = runTest {
        assertNull(FakeFs(accessible = false).probe())
        val m = TransferAvailability.evaluate(sdk = 36, djiInstalled = true, shizukuInstalled = true, shizukuRunning = true, shizukuGranted = false, safGranted = false)
        assertFalse(m[0].available); assertTrue(m[0].reason.contains("Authorise"))
        assertFalse(m[1].available); assertTrue(m[1].reason.contains("Android 13+"))
        assertTrue(m[2].available)
        assertTrue(TransferAvailability.evaluate(36, true, true, true, true, false)[0].available)
        assertTrue(TransferAvailability.evaluate(30, true, false, false, false, true)[1].available)
        assertFalse(TransferAvailability.evaluate(36, false, true, true, true, true)[0].available)
    }

    @Test fun parsesShellStatListing() {
        val l = parseStatLines("/w/A/A.kmz|1200|100\n/w/B/B.kmz|900|300\n/w/x.txt|1|1\n")
        assertEquals(listOf("B", "A"), l.map { it.id }); assertEquals(1200L, l[1].sizeBytes)
    }
}

class TerrainTest {
    @Test fun hgtTileBilinearAndVoids() {
        val n = 1201
        val buf = ByteBuffer.allocate(n * n * 2)
        for (r in 0 until n) for (c in 0 until n) buf.putShort(((n - 1 - r) * 1).toShort().let { if (r == 5 && c == 5) (-32768).toShort() else it })
        val f = File.createTempFile("S18E177", ".hgt").let { val t = File(it.parentFile, "S18E177.hgt"); t.writeBytes(buf.array()); it.delete(); t }
        val dem = HgtDem(f)
        // elevation increases northward by 1 m per row; tile spans lat -18..-17
        assertEquals(600.0, dem.elevation(-17.5, 177.5)!!, 0.01)
        assertNull(dem.elevation(-16.5, 177.5))
        assertNull(dem.elevation(-17.0 - 5.0 / 1200, 177.0 + 5.0 / 1200))
        f.delete()
    }

    @Test fun ascGridParsing() {
        val f = File.createTempFile("dem", ".asc")
        f.writeText("ncols 3\nnrows 2\nxllcorner 177.0\nyllcorner -18.0\ncellsize 0.5\nNODATA_value -9999\n10 20 30\n40 50 60\n")
        val dem = AscDem(f)
        assertNotNull(dem.elevation(-17.25, 177.25)); assertEquals(10.0, dem.elevation(-17.25, 177.25)!!, 1e-9)
        assertEquals(30.0, dem.elevation(-17.5, 177.5)!!, 1e-9)
        f.delete()
    }
}
