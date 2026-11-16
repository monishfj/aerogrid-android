package com.aerogrid.app.kmz

import com.aerogrid.app.engine.ElevationSource
import com.aerogrid.app.engine.GeoMath
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.engine.Timeline
import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.DroneInfo
import com.aerogrid.app.model.FinishAction
import com.aerogrid.app.model.HeadingMode
import com.aerogrid.app.model.LostAction
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.TurnMode
import com.aerogrid.app.model.Waypoint
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

private fun f(v: Double, d: Int = 6) = String.format(Locale.US, "%.${d}f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
private fun fc(v: Double) = String.format(Locale.US, "%.12f", v)

/** Writes DJI WPML 1.0.x KMZ (wpmz/template.kml + wpmz/waylines.wpml) in the layout DJI Fly saves for consumer drones. */
object WpmlWriter {
    const val TEMPLATE = "wpmz/template.kml"
    const val WAYLINES = "wpmz/waylines.wpml"

    data class Output(val bytes: ByteArray, val waypointCount: Int, val sha256: String)

    fun turnModeWpml(t: TurnMode) = when (t) {
        TurnMode.STOP_AT_POINT -> "toPointAndStopWithDiscontinuityCurvature"
        TurnMode.COORDINATED_TURN -> "coordinateTurn"
        TurnMode.CURVED_PASS -> "toPointAndPassWithContinuityCurvature"
    }

    fun build(p: Project, wps: List<Waypoint>, drone: DroneInfo, dem: ElevationSource?, now: Long = System.currentTimeMillis()): Output {
        require(wps.size >= 2) { "A DJI Fly mission needs at least 2 waypoints" }
        val ns = drone.namespace
        val speed = Timeline.missionSpeed(p)
        val heights = wps.map { Terrain.relativeHeight(p, it, dem) }
        val dist = GeoMath.pathLength(wps.map { it.point })
        val dur = Timeline.duration(p, wps, includeTransit = false)
        val missionConfig = buildString {
            append("    <wpml:missionConfig>\n")
            append("      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>\n")
            append("      <wpml:finishAction>${p.settings.finishAction.wpml}</wpml:finishAction>\n")
            append("      <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>\n")
            append("      <wpml:executeRCLostAction>${p.settings.lostAction.wpml}</wpml:executeRCLostAction>\n")
            append("      <wpml:globalTransitionalSpeed>${f(p.settings.transitSpeedMs, 1)}</wpml:globalTransitionalSpeed>\n")
            append("      <wpml:droneInfo>\n        <wpml:droneEnumValue>${drone.droneEnumValue}</wpml:droneEnumValue>\n        <wpml:droneSubEnumValue>${drone.droneSubEnumValue}</wpml:droneSubEnumValue>\n      </wpml:droneInfo>\n")
            if (drone.payloadEnumValue != null) append("      <wpml:payloadInfo>\n        <wpml:payloadEnumValue>${drone.payloadEnumValue}</wpml:payloadEnumValue>\n        <wpml:payloadSubEnumValue>${drone.payloadSubEnumValue ?: 0}</wpml:payloadSubEnumValue>\n        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n      </wpml:payloadInfo>\n")
            append("    </wpml:missionConfig>\n")
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"$ns\">\n  <Document>\n"
        val template = buildString {
            append(head)
            append("    <wpml:author>AeroGrid</wpml:author>\n    <wpml:createTime>$now</wpml:createTime>\n    <wpml:updateTime>$now</wpml:updateTime>\n")
            append(missionConfig)
            append("    <Folder>\n      <wpml:templateType>waypoint</wpml:templateType>\n      <wpml:templateId>0</wpml:templateId>\n")
            append("      <wpml:waylineCoordinateSysParam>\n        <wpml:coordinateMode>WGS84</wpml:coordinateMode>\n        <wpml:heightMode>relativeToStartPoint</wpml:heightMode>\n      </wpml:waylineCoordinateSysParam>\n")
            append("      <wpml:autoFlightSpeed>${f(speed, 2)}</wpml:autoFlightSpeed>\n      <wpml:globalHeight>${f(p.settings.heightM, 1)}</wpml:globalHeight>\n")
            append("      <wpml:caliFlightEnable>0</wpml:caliFlightEnable>\n      <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>\n")
            append("      <wpml:globalWaypointHeadingParam>\n        <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>\n        <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>\n        <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>\n        <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>\n        <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>\n      </wpml:globalWaypointHeadingParam>\n")
            append("      <wpml:globalWaypointTurnMode>${turnModeWpml(p.settings.turnMode)}</wpml:globalWaypointTurnMode>\n      <wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>\n")
            wps.forEachIndexed { i, w -> append(placemark(p, w, i, heights[i], speed, template = true)) }
            append("    </Folder>\n  </Document>\n</kml>\n")
        }
        val waylines = buildString {
            append(head)
            append(missionConfig)
            append("    <Folder>\n      <wpml:templateId>0</wpml:templateId>\n      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>\n      <wpml:waylineId>0</wpml:waylineId>\n")
            append("      <wpml:distance>${f(dist, 2)}</wpml:distance>\n      <wpml:duration>${f(dur, 2)}</wpml:duration>\n      <wpml:autoFlightSpeed>${f(speed, 2)}</wpml:autoFlightSpeed>\n")
            wps.forEachIndexed { i, w -> append(placemark(p, w, i, heights[i], speed, template = false)) }
            append("    </Folder>\n  </Document>\n</kml>\n")
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry(TEMPLATE)); z.write(template.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry(WAYLINES)); z.write(waylines.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        val bytes = bos.toByteArray()
        return Output(bytes, wps.size, sha256(bytes))
    }

    private fun placemark(p: Project, w: Waypoint, i: Int, h: Double, globalSpeed: Double, template: Boolean): String = buildString {
        val speed = Timeline.legSpeed(p, w).takeIf { w.speedOverride != null || w.transit } ?: globalSpeed
        val pitch = w.gimbalPitch ?: p.settings.gimbalPitch
        val turn = w.turnMode ?: p.settings.turnMode
        append("      <Placemark>\n        <Point>\n          <coordinates>\n            ${fc(w.lon)},${fc(w.lat)}\n          </coordinates>\n        </Point>\n")
        append("        <wpml:index>$i</wpml:index>\n")
        if (template) {
            append("        <wpml:ellipsoidHeight>${f(h, 2)}</wpml:ellipsoidHeight>\n        <wpml:height>${f(h, 2)}</wpml:height>\n")
            append("        <wpml:useGlobalHeight>0</wpml:useGlobalHeight>\n        <wpml:useGlobalSpeed>0</wpml:useGlobalSpeed>\n        <wpml:waypointSpeed>${f(speed, 2)}</wpml:waypointSpeed>\n")
            append("        <wpml:useGlobalHeadingParam>0</wpml:useGlobalHeadingParam>\n")
            append(headingParam(w))
            append("        <wpml:useGlobalTurnParam>0</wpml:useGlobalTurnParam>\n")
            append(turnParam(turn))
            append("        <wpml:useStraightLine>${if (turn == TurnMode.STOP_AT_POINT) 1 else 0}</wpml:useStraightLine>\n")
            append("        <wpml:gimbalPitchAngle>${f(pitch, 1)}</wpml:gimbalPitchAngle>\n")
        } else {
            append("        <wpml:executeHeight>${f(h, 2)}</wpml:executeHeight>\n        <wpml:waypointSpeed>${f(speed, 2)}</wpml:waypointSpeed>\n")
            append(headingParam(w))
            append(turnParam(turn))
            append("        <wpml:useStraightLine>${if (turn == TurnMode.STOP_AT_POINT) 1 else 0}</wpml:useStraightLine>\n")
        }
        append(actions(p, w, i, pitch))
        append("        <wpml:waypointGimbalHeadingParam>\n          <wpml:waypointGimbalPitchAngle>${f(pitch, 1)}</wpml:waypointGimbalPitchAngle>\n          <wpml:waypointGimbalYawAngle>0</wpml:waypointGimbalYawAngle>\n        </wpml:waypointGimbalHeadingParam>\n")
        append("        <wpml:isRisky>0</wpml:isRisky>\n        <wpml:waypointWorkType>0</wpml:waypointWorkType>\n")
        append("      </Placemark>\n")
    }

    private fun headingParam(w: Waypoint): String {
        val mode = when (w.headingMode) { HeadingMode.FOLLOW_ROUTE -> "followWayline"; HeadingMode.FIXED -> "smoothTransition"; HeadingMode.MANUAL -> "manually" }
        val ang = GeoMath.normalize180(w.headingDeg)
        return "        <wpml:waypointHeadingParam>\n          <wpml:waypointHeadingMode>$mode</wpml:waypointHeadingMode>\n          <wpml:waypointHeadingAngle>${f(ang, 1)}</wpml:waypointHeadingAngle>\n" +
            "          <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>\n          <wpml:waypointHeadingAngleEnable>${if (w.headingMode == HeadingMode.FIXED) 1 else 0}</wpml:waypointHeadingAngleEnable>\n" +
            "          <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>\n          <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>\n        </wpml:waypointHeadingParam>\n"
    }

    private fun turnParam(t: TurnMode) =
        "        <wpml:waypointTurnParam>\n          <wpml:waypointTurnMode>${turnModeWpml(t)}</wpml:waypointTurnMode>\n          <wpml:waypointTurnDampingDist>${if (t == TurnMode.STOP_AT_POINT) 0 else "0.2"}</wpml:waypointTurnDampingDist>\n        </wpml:waypointTurnParam>\n"

    private fun actions(p: Project, w: Waypoint, i: Int, pitch: Double): String {
        val acts = ArrayList<String>()
        if (i == 0 || w.gimbalPitch != null) acts += "<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:gimbalHeadingYawBase>aircraft</wpml:gimbalHeadingYawBase>\n              <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>\n              <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>\n              <wpml:gimbalPitchRotateAngle>${f(pitch, 1)}</wpml:gimbalPitchRotateAngle>\n              <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>\n              <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>\n              <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>\n              <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>\n              <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>\n              <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>\n              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n            </wpml:actionActuatorFuncParam>"
        w.zoom?.let { z -> acts += "<wpml:actionActuatorFunc>zoom</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:focalLength>${f(p.camera.equivFocalMm * z, 1)}</wpml:focalLength>\n              <wpml:isUseFocalFactor>0</wpml:isUseFocalFactor>\n              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n            </wpml:actionActuatorFuncParam>" }
        if (w.hoverSec > 0) acts += "<wpml:actionActuatorFunc>hover</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:hoverTime>${f(w.hoverSec, 1)}</wpml:hoverTime>\n            </wpml:actionActuatorFuncParam>"
        when (w.action) {
            CameraAction.TAKE_PHOTO -> acts += "<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n              <wpml:useGlobalPayloadLensIndex>0</wpml:useGlobalPayloadLensIndex>\n            </wpml:actionActuatorFuncParam>"
            CameraAction.START_RECORD -> acts += "<wpml:actionActuatorFunc>startRecord</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n              <wpml:useGlobalPayloadLensIndex>0</wpml:useGlobalPayloadLensIndex>\n            </wpml:actionActuatorFuncParam>"
            CameraAction.STOP_RECORD -> acts += "<wpml:actionActuatorFunc>stopRecord</wpml:actionActuatorFunc>\n            <wpml:actionActuatorFuncParam>\n              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n            </wpml:actionActuatorFuncParam>"
            CameraAction.NONE -> {}
        }
        if (acts.isEmpty()) return ""
        return buildString {
            append("        <wpml:actionGroup>\n          <wpml:actionGroupId>$i</wpml:actionGroupId>\n          <wpml:actionGroupStartIndex>$i</wpml:actionGroupStartIndex>\n          <wpml:actionGroupEndIndex>$i</wpml:actionGroupEndIndex>\n")
            append("          <wpml:actionGroupMode>sequence</wpml:actionGroupMode>\n          <wpml:actionTrigger>\n            <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>\n          </wpml:actionTrigger>\n")
            acts.forEachIndexed { k, a -> append("          <wpml:action>\n            <wpml:actionId>$k</wpml:actionId>\n            $a\n          </wpml:action>\n") }
            append("        </wpml:actionGroup>\n")
        }
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

data class ImportedMission(
    val waypoints: List<Waypoint>, val drone: DroneInfo, val finish: FinishAction?, val lost: LostAction?,
    val autoSpeed: Double?, val globalHeight: Double?, val source: String,
)

object WpmlReader {
    fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            var total = 0L
            while (e != null) {
                if (!e.isDirectory) {
                    val b = z.readBytes(); total += b.size
                    require(total < 50_000_000) { "KMZ too large" }
                    out[e.name.replace('\\', '/')] = b
                }
                e = z.nextEntry
            }
        }
        return out
    }

    fun parseXml(b: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(b))

    private fun Element.child(local: String): Element? {
        val nl = childNodes
        for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) return n }
        return null
    }
    private fun Element.all(local: String): List<Element> {
        val nl = getElementsByTagNameNS("*", local); return (0 until nl.length).map { nl.item(it) as Element }
    }
    private fun Element.text(local: String) = child(local)?.textContent?.trim()
    private fun Element.dbl(local: String) = text(local)?.toDoubleOrNull()

    /** Import a DJI WPML KMZ (DJI Fly or Pilot 2). waylines.wpml is preferred, template.kml is used as fallback. */
    fun read(bytes: ByteArray, firstId: Long = 1): ImportedMission {
        val files = unzip(ByteArrayInputStream(bytes))
        val wl = files.entries.firstOrNull { it.key.endsWith("waylines.wpml") }
        val tp = files.entries.firstOrNull { it.key.endsWith("template.kml") }
        val main = wl ?: tp ?: throw IllegalArgumentException("Not a DJI waypoint KMZ: wpmz/waylines.wpml and wpmz/template.kml missing")
        val doc = parseXml(main.value).documentElement
        val ns = doc.lookupNamespaceURI("wpml") ?: "http://www.uav.com/wpmz/1.0.2"
        val mc = doc.all("missionConfig").firstOrNull()
        val di = mc?.child("droneInfo"); val pi = mc?.child("payloadInfo")
        val drone = DroneInfo(
            droneEnumValue = di?.text("droneEnumValue")?.toIntOrNull() ?: 68,
            droneSubEnumValue = di?.text("droneSubEnumValue")?.toIntOrNull() ?: 0,
            namespace = ns,
            payloadEnumValue = pi?.text("payloadEnumValue")?.toIntOrNull(),
            payloadSubEnumValue = pi?.text("payloadSubEnumValue")?.toIntOrNull(),
            source = "Imported reference mission",
        )
        val folder = doc.all("Folder").firstOrNull()
        val autoSpeed = folder?.dbl("autoFlightSpeed")
        val globalHeight = folder?.dbl("globalHeight")
        var id = firstId
        val wps = doc.all("Placemark").sortedBy { it.text("index")?.toIntOrNull() ?: 0 }.map { pm ->
            val coords = pm.all("coordinates").first().textContent.trim().split(',').map { it.trim().toDouble() }
            val h = pm.dbl("executeHeight") ?: pm.dbl("height") ?: pm.dbl("ellipsoidHeight")
            val sp = pm.dbl("waypointSpeed")
            val hp = pm.child("waypointHeadingParam")
            val headMode = when (hp?.text("waypointHeadingMode")) { "manually" -> HeadingMode.MANUAL; "fixed", "smoothTransition" -> HeadingMode.FIXED; else -> HeadingMode.FOLLOW_ROUTE }
            val turn = when (pm.child("waypointTurnParam")?.text("waypointTurnMode")) {
                "coordinateTurn" -> TurnMode.COORDINATED_TURN
                "toPointAndPassWithContinuityCurvature" -> TurnMode.CURVED_PASS
                null -> null
                else -> TurnMode.STOP_AT_POINT
            }
            var action = CameraAction.NONE; var hover = 0.0; var pitch: Double? = pm.child("waypointGimbalHeadingParam")?.dbl("waypointGimbalPitchAngle") ?: pm.dbl("gimbalPitchAngle")
            var zoom: Double? = null
            pm.all("action").forEach { a ->
                val prm = a.child("actionActuatorFuncParam")
                when (a.text("actionActuatorFunc")) {
                    "takePhoto" -> action = CameraAction.TAKE_PHOTO
                    "startRecord" -> action = CameraAction.START_RECORD
                    "stopRecord" -> action = CameraAction.STOP_RECORD
                    "hover" -> hover = prm?.dbl("hoverTime") ?: 0.0
                    "gimbalRotate" -> prm?.dbl("gimbalPitchRotateAngle")?.let { pitch = it }
                    "zoom" -> prm?.dbl("focalLength")?.let { zoom = it / 24.0 }
                }
            }
            Waypoint(id++, coords[1], coords[0], heightOverride = h, speedOverride = sp?.takeIf { autoSpeed == null || kotlin.math.abs(it - autoSpeed) > 0.01 },
                headingMode = headMode, headingDeg = hp?.dbl("waypointHeadingAngle") ?: 0.0, gimbalPitch = pitch, zoom = zoom, action = action, hoverSec = hover, turnMode = turn, lineIndex = -1)
        }
        return ImportedMission(wps, drone,
            FinishAction.entries.firstOrNull { it.wpml == mc?.text("finishAction") },
            LostAction.entries.firstOrNull { it.wpml == mc?.text("executeRCLostAction") },
            autoSpeed, globalHeight, main.key)
    }
}

object KmzValidator {
    data class Result(val ok: Boolean, val errors: List<String>, val warnings: List<String>, val waypointCount: Int)

    fun validate(bytes: ByteArray, maxWaypoints: Int = 200, expectedNamespace: String? = null): Result {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val files = runCatching { WpmlReader.unzip(ByteArrayInputStream(bytes)) }.getOrElse { return Result(false, listOf("Not a valid ZIP/KMZ: ${it.message}"), emptyList(), 0) }
        val counts = HashMap<String, Int>()
        for (name in listOf(WpmlWriter.TEMPLATE, WpmlWriter.WAYLINES)) {
            val b = files[name]
            if (b == null) { errors += "Missing $name"; continue }
            val doc = runCatching { WpmlReader.parseXml(b).documentElement }.getOrElse { errors += "$name is not well-formed XML: ${it.message}"; null } ?: continue
            if (doc.localName != "kml") errors += "$name root element is not <kml>"
            val ns = doc.lookupNamespaceURI("wpml")
            if (ns == null) errors += "$name lacks the wpml namespace"
            else if (expectedNamespace != null && ns != expectedNamespace) warnings += "$name namespace $ns differs from reference $expectedNamespace"
            if (doc.getElementsByTagNameNS("*", "droneEnumValue").length == 0) errors += "$name has no droneInfo"
            val pms = doc.getElementsByTagNameNS("*", "Placemark")
            counts[name] = pms.length
            val idx = (0 until pms.length).map { i ->
                val e = pms.item(i) as Element
                val c = e.getElementsByTagNameNS("*", "coordinates").item(0)?.textContent?.trim()?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                if (c == null || c.size < 2 || c[1] !in -90.0..90.0 || c[0] !in -180.0..180.0) errors += "$name placemark $i has invalid coordinates"
                val hTag = if (name == WpmlWriter.WAYLINES) "executeHeight" else "height"
                val h = e.getElementsByTagNameNS("*", hTag).item(0)?.textContent?.trim()?.toDoubleOrNull()
                if (h == null) errors += "$name placemark $i missing $hTag" else if (h < 0 || h > 500) errors += "$name placemark $i height $h out of range"
                e.getElementsByTagNameNS("*", "index").item(0)?.textContent?.trim()?.toIntOrNull() ?: -1
            }
            if (idx != idx.indices.toList()) errors += "$name waypoint indices are not sequential from 0"
        }
        val n = counts[WpmlWriter.WAYLINES] ?: counts[WpmlWriter.TEMPLATE] ?: 0
        if (counts.size == 2 && counts[WpmlWriter.WAYLINES] != counts[WpmlWriter.TEMPLATE]) errors += "template.kml and waylines.wpml waypoint counts differ"
        if (n < 2) errors += "Mission needs at least 2 waypoints"
        if (n > maxWaypoints) errors += "$n waypoints exceed the $maxWaypoints-waypoint limit"
        files.keys.filter { !it.startsWith("wpmz/") }.forEach { warnings += "Unexpected file in KMZ: $it" }
        return Result(errors.isEmpty(), errors, warnings, n)
    }
}
