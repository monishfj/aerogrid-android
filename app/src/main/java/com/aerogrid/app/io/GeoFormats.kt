package com.aerogrid.app.io

import com.aerogrid.app.engine.ElevationSource
import com.aerogrid.app.engine.MissionSplitter
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.kmz.WpmlReader
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.Waypoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportedGeometry(
    val boundaries: List<List<GeoPoint>> = emptyList(),
    val exclusions: List<List<GeoPoint>> = emptyList(),
    val lines: List<List<GeoPoint>> = emptyList(),
    val points: List<Pair<GeoPoint, Double?>> = emptyList(),
    val name: String? = null,
)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun n(v: Double, d: Int = 8) = String.format(Locale.US, "%.${d}f", v)
private fun isExclusionName(s: String?) = s != null && Regex("exclu|no[ -]?fly|restrict|avoid", RegexOption.IGNORE_CASE).containsMatchIn(s)

object GeoImport {
    fun detect(name: String, bytes: ByteArray): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".geojson") || lower.endsWith(".json") -> if (String(bytes.take(200).toByteArray()).contains("\"aerogrid\"")) "aerogrid" else "geojson"
            lower.endsWith(".gpx") -> "gpx"
            lower.endsWith(".kml") -> "kml"
            lower.endsWith(".kmz") -> if (isDjiKmz(bytes)) "dji" else "kmz"
            lower.endsWith(".aerogrid") || lower.endsWith(".zip") -> "aerogrid"
            bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> if (isDjiKmz(bytes)) "dji" else "kmz"
            else -> "kml"
        }
    }

    fun isDjiKmz(bytes: ByteArray) = runCatching { WpmlReader.unzip(ByteArrayInputStream(bytes)).keys.any { it.endsWith("waylines.wpml") || it.endsWith("template.kml") } }.getOrDefault(false)

    private fun coords(text: String): List<GeoPoint> = text.trim().split(Regex("\\s+")).mapNotNull { t ->
        val c = t.split(',').mapNotNull { it.toDoubleOrNull() }; if (c.size >= 2) GeoPoint(c[1], c[0]) else null
    }
    private fun ring(pts: List<GeoPoint>) = if (pts.size > 1 && pts.first() == pts.last()) pts.dropLast(1) else pts

    fun kml(bytes: ByteArray): ImportedGeometry {
        val doc = WpmlReader.parseXml(bytes).documentElement
        val b = ArrayList<List<GeoPoint>>(); val e = ArrayList<List<GeoPoint>>(); val l = ArrayList<List<GeoPoint>>(); val p = ArrayList<Pair<GeoPoint, Double?>>()
        val pms = doc.getElementsByTagNameNS("*", "Placemark")
        for (i in 0 until pms.length) {
            val pm = pms.item(i) as Element
            val name = pm.getElementsByTagNameNS("*", "name").item(0)?.textContent
            val polys = pm.getElementsByTagNameNS("*", "Polygon")
            for (j in 0 until polys.length) {
                val poly = polys.item(j) as Element
                val outer = poly.getElementsByTagNameNS("*", "outerBoundaryIs").item(0) as? Element
                outer?.getElementsByTagNameNS("*", "coordinates")?.item(0)?.textContent?.let { (if (isExclusionName(name)) e else b) += ring(coords(it)) }
                val inner = poly.getElementsByTagNameNS("*", "innerBoundaryIs")
                for (k in 0 until inner.length) (inner.item(k) as Element).getElementsByTagNameNS("*", "coordinates").item(0)?.textContent?.let { e += ring(coords(it)) }
            }
            val lines = pm.getElementsByTagNameNS("*", "LineString")
            for (j in 0 until lines.length) (lines.item(j) as Element).getElementsByTagNameNS("*", "coordinates").item(0)?.textContent?.let { l += coords(it) }
            val pts = pm.getElementsByTagNameNS("*", "Point")
            for (j in 0 until pts.length) (pts.item(j) as Element).getElementsByTagNameNS("*", "coordinates").item(0)?.textContent?.let { t ->
                val c = t.trim().split(',').mapNotNull { it.toDoubleOrNull() }
                if (c.size >= 2) p += GeoPoint(c[1], c[0]) to c.getOrNull(2)?.takeIf { it > 0 }
            }
        }
        val docName = doc.getElementsByTagNameNS("*", "name").item(0)?.textContent
        return ImportedGeometry(b.filter { it.size >= 3 }, e.filter { it.size >= 3 }, l.filter { it.size >= 2 }, p, docName)
    }

    fun kmz(bytes: ByteArray): ImportedGeometry {
        val files = WpmlReader.unzip(ByteArrayInputStream(bytes))
        val kml = files.entries.firstOrNull { it.key.endsWith(".kml", true) } ?: throw IllegalArgumentException("KMZ contains no .kml document")
        return kml(kml.value)
    }

    fun geojson(text: String): ImportedGeometry {
        val root = AppJson.parseToJsonElement(text).jsonObject
        val b = ArrayList<List<GeoPoint>>(); val e = ArrayList<List<GeoPoint>>(); val l = ArrayList<List<GeoPoint>>(); val p = ArrayList<Pair<GeoPoint, Double?>>()
        fun pt(a: JsonElement): GeoPoint { val c = a.jsonArray; return GeoPoint(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double) }
        fun geom(g: JsonObject, props: JsonObject?) {
            val excl = isExclusionName(props?.get("name")?.jsonPrimitive?.content) || props?.get("exclusion")?.jsonPrimitive?.content == "true" || props?.get("kind")?.jsonPrimitive?.content == "exclusion"
            val c = g["coordinates"]
            when (g["type"]?.jsonPrimitive?.content) {
                "Polygon" -> c!!.jsonArray.forEachIndexed { i, r -> (if (i == 0 && !excl) b else e) += ring(r.jsonArray.map(::pt)) }
                "MultiPolygon" -> c!!.jsonArray.forEach { poly -> poly.jsonArray.forEachIndexed { i, r -> (if (i == 0 && !excl) b else e) += ring(r.jsonArray.map(::pt)) } }
                "LineString" -> l += c!!.jsonArray.map(::pt)
                "MultiLineString" -> c!!.jsonArray.forEach { ls -> l += ls.jsonArray.map(::pt) }
                "Point" -> p += pt(c!!) to c.jsonArray.getOrNull(2)?.jsonPrimitive?.double
                "MultiPoint" -> c!!.jsonArray.forEach { p += pt(it) to null }
                "GeometryCollection" -> g["geometries"]!!.jsonArray.forEach { geom(it.jsonObject, props) }
            }
        }
        when (root["type"]?.jsonPrimitive?.content) {
            "FeatureCollection" -> root["features"]!!.jsonArray.forEach { f -> val o = f.jsonObject; o["geometry"]?.let { if (it is JsonObject) geom(it, o["properties"] as? JsonObject) } }
            "Feature" -> geom(root["geometry"]!!.jsonObject, root["properties"] as? JsonObject)
            else -> geom(root, null)
        }
        return ImportedGeometry(b.filter { it.size >= 3 }, e.filter { it.size >= 3 }, l.filter { it.size >= 2 }, p)
    }

    fun gpx(bytes: ByteArray): ImportedGeometry {
        val doc = WpmlReader.parseXml(bytes).documentElement
        fun pts(tag: String, parent: Element): List<GeoPoint> { val nl = parent.getElementsByTagNameNS("*", tag); return (0 until nl.length).map { val e = nl.item(it) as Element; GeoPoint(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble()) } }
        val lines = ArrayList<List<GeoPoint>>()
        listOf("rte" to "rtept", "trkseg" to "trkpt").forEach { (c, t) -> val nl = doc.getElementsByTagNameNS("*", c); for (i in 0 until nl.length) lines += pts(t, nl.item(i) as Element) }
        val wpts = doc.getElementsByTagNameNS("*", "wpt")
        val p = (0 until wpts.length).map { val e = wpts.item(it) as Element
            GeoPoint(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble()) to e.getElementsByTagNameNS("*", "ele").item(0)?.textContent?.toDoubleOrNull() }
        return ImportedGeometry(lines = lines.filter { it.size >= 2 }, points = p)
    }

    fun parse(name: String, bytes: ByteArray): ImportedGeometry = when (detect(name, bytes)) {
        "geojson" -> geojson(String(bytes, Charsets.UTF_8))
        "gpx" -> gpx(bytes)
        "kmz" -> kmz(bytes)
        else -> kml(bytes)
    }
}

object GeoExport {
    fun kml(p: Project, dem: ElevationSource?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>${esc(p.name)}</name>\n")
        append("<Style id=\"b\"><LineStyle><color>ff00c8ff</color><width>2</width></LineStyle><PolyStyle><color>3300c8ff</color></PolyStyle></Style>\n")
        append("<Style id=\"x\"><LineStyle><color>ff3030ff</color><width>2</width></LineStyle><PolyStyle><color>553030ff</color></PolyStyle></Style>\n")
        append("<Style id=\"r\"><LineStyle><color>ff36d1ff</color><width>3</width></LineStyle></Style>\n")
        p.boundaries.forEachIndexed { i, r -> append(poly("Boundary ${i + 1}", "b", r)) }
        p.exclusions.forEachIndexed { i, r -> append(poly("Exclusion ${i + 1}", "x", r)) }
        if (p.corridor.size >= 2) append("<Placemark><name>Corridor</name><LineString><coordinates>${p.corridor.joinToString(" ") { "${n(it.lon)},${n(it.lat)},0" }}</coordinates></LineString></Placemark>\n")
        if (p.waypoints.isNotEmpty()) {
            append("<Placemark><name>Route</name><styleUrl>#r</styleUrl><LineString><altitudeMode>relativeToGround</altitudeMode><coordinates>")
            append(p.waypoints.joinToString(" ") { "${n(it.lon)},${n(it.lat)},${n(p.heightOf(it), 1)}" })
            append("</coordinates></LineString></Placemark>\n<Folder><name>Waypoints</name>\n")
            p.waypoints.forEachIndexed { i, w -> append("<Placemark><name>WP%03d</name><description>height %.1f m (relative to takeoff %.1f m)</description><Point><altitudeMode>relativeToGround</altitudeMode><coordinates>%s,%s,%s</coordinates></Point></Placemark>\n".format(Locale.US, i + 1, p.heightOf(w), Terrain.relativeHeight(p, w, dem), n(w.lon), n(w.lat), n(p.heightOf(w), 1))) }
            append("</Folder>\n")
        }
        append("</Document>\n</kml>\n")
    }

    private fun poly(name: String, style: String, r: List<GeoPoint>) =
        "<Placemark><name>${esc(name)}</name><styleUrl>#$style</styleUrl><Polygon><outerBoundaryIs><LinearRing><coordinates>${(r + r.first()).joinToString(" ") { "${n(it.lon)},${n(it.lat)},0" }}</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n"

    fun geojson(p: Project): String {
        fun ring(r: List<GeoPoint>) = buildJsonArray { add(buildJsonArray { (r + r.first()).forEach { q -> add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(q.lon)); add(kotlinx.serialization.json.JsonPrimitive(q.lat)) }) } }) }
        val features = buildJsonArray {
            p.boundaries.forEach { r -> add(buildJsonObject { put("type", "Feature"); putJsonObject("properties") { put("kind", "boundary") }; putJsonObject("geometry") { put("type", "Polygon"); put("coordinates", ring(r)) } }) }
            p.exclusions.forEach { r -> add(buildJsonObject { put("type", "Feature"); putJsonObject("properties") { put("kind", "exclusion") }; putJsonObject("geometry") { put("type", "Polygon"); put("coordinates", ring(r)) } }) }
            if (p.waypoints.size >= 2) add(buildJsonObject { put("type", "Feature"); putJsonObject("properties") { put("kind", "route") }
                putJsonObject("geometry") { put("type", "LineString"); putJsonArray("coordinates") { p.waypoints.forEach { w -> add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(w.lon)); add(kotlinx.serialization.json.JsonPrimitive(w.lat)); add(kotlinx.serialization.json.JsonPrimitive(p.heightOf(w))) }) } } } })
            p.waypoints.forEachIndexed { i, w -> add(buildJsonObject { put("type", "Feature")
                putJsonObject("properties") { put("kind", "waypoint"); put("name", "WP%03d".format(i + 1)); put("height_m", p.heightOf(w)); put("override", w.heightOverride != null) }
                putJsonObject("geometry") { put("type", "Point"); putJsonArray("coordinates") { add(kotlinx.serialization.json.JsonPrimitive(w.lon)); add(kotlinx.serialization.json.JsonPrimitive(w.lat)); add(kotlinx.serialization.json.JsonPrimitive(p.heightOf(w))) } } }) }
        }
        return buildJsonObject { put("type", "FeatureCollection"); put("name", p.name); put("features", features) }.toString()
    }

    fun csv(p: Project, dem: ElevationSource?): String = buildString {
        append("index,name,latitude,longitude,height_m,height_mode,relative_to_takeoff_m,speed_ms,heading_mode,heading_deg,gimbal_pitch,action,hover_s,transit,block\n")
        p.waypoints.forEachIndexed { i, w ->
            val block = p.blocks.indexOfFirst { i in it.start..it.end } + 1
            append(listOf(i + 1, "WP%03d".format(i + 1), n(w.lat), n(w.lon), n(p.heightOf(w), 2), if (w.heightOverride != null) "individual" else "global",
                n(Terrain.relativeHeight(p, w, dem), 2), w.speedOverride?.let { n(it, 2) } ?: "", w.headingMode, n(w.headingDeg, 1),
                n(w.gimbalPitch ?: p.settings.gimbalPitch, 1), w.action, n(w.hoverSec, 1), w.transit, block).joinToString(","))
            append('\n')
        }
    }

    fun gpx(p: Project): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"AeroGrid\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("<metadata><name>${esc(p.name)}</name><desc>ele = flight height relative to takeoff/AGL target, not MSL</desc></metadata>\n<rte><name>${esc(p.name)}</name>\n")
        p.waypoints.forEachIndexed { i, w -> append("<rtept lat=\"${n(w.lat)}\" lon=\"${n(w.lon)}\"><ele>${n(p.heightOf(w), 1)}</ele><name>WP%03d</name></rtept>\n".format(i + 1)) }
        append("</rte>\n</gpx>\n")
    }
}

/** AeroGrid backup: a ZIP with manifest.json and one JSON file per project. */
object BackupCodec {
    fun encode(projects: List<Project>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"aerogrid":1,"count":${projects.size},"created":${System.currentTimeMillis()}}""".toByteArray()); z.closeEntry()
            projects.forEachIndexed { i, p -> z.putNextEntry(ZipEntry("projects/%04d.json".format(i))); z.write(AppJson.encodeToString(p).toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Project> {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return listOf(AppJson.decodeFromString(Project.serializer(), String(bytes, Charsets.UTF_8)))
        val files = WpmlReader.unzip(ByteArrayInputStream(bytes))
        require(files.containsKey("manifest.json")) { "Not an AeroGrid backup" }
        return files.filterKeys { it.startsWith("projects/") && it.endsWith(".json") }.toSortedMap().values.map { AppJson.decodeFromString(Project.serializer(), String(it, Charsets.UTF_8)) }
    }

    fun projectJson(p: Project) = AppJson.encodeToString(p)
}

/** Convert imported geometry into a project (adds to existing project). */
fun Project.withImported(g: ImportedGeometry, asWaypoints: Boolean): Project {
    var np = copy(boundaries = boundaries + g.boundaries, exclusions = exclusions + g.exclusions,
        corridor = if (corridor.isEmpty() && g.lines.isNotEmpty() && !asWaypoints) g.lines.first() else corridor)
    if (asWaypoints) {
        var id = nextId
        val src = g.points.ifEmpty { g.lines.flatten().map { it to null } }
        val wps = src.map { (pt, h) -> Waypoint(id++, pt.lat, pt.lon, heightOverride = h) }
        np = np.copy(waypoints = waypoints + wps, nextId = id, settings = settings.copy(pattern = com.aerogrid.app.model.FlightPattern.MANUAL))
        np = np.copy(blocks = MissionSplitter.split(np))
    }
    return np
}
