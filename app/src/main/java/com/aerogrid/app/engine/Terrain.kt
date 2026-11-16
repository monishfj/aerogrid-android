package com.aerogrid.app.engine

import com.aerogrid.app.model.AltitudeMode
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.Waypoint
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.sqrt

/** Elevation above mean sea level (m). Returns null outside coverage or on voids — never fabricated. */
interface ElevationSource {
    val description: String
    fun elevation(lat: Double, lon: Double): Double?
}

/** SRTM .hgt tile (1 or 3 arc-second, big-endian int16). File name gives SW corner, e.g. S18E177.hgt. */
class HgtDem(file: File) : ElevationSource {
    private val n: Int
    private val lat0: Int
    private val lon0: Int
    private val buf: MappedByteBuffer
    override val description: String

    init {
        val len = file.length()
        n = sqrt((len / 2).toDouble()).toInt()
        require(n.toLong() * n * 2 == len && (n == 1201 || n == 3601)) { "Not an SRTM .hgt tile (size $len bytes)" }
        val m = Regex("([NS])(\\d{2})([EW])(\\d{3})", RegexOption.IGNORE_CASE).find(file.name)
            ?: throw IllegalArgumentException("HGT file name must encode its corner, e.g. S18E177.hgt")
        lat0 = m.groupValues[2].toInt() * if (m.groupValues[1].equals("S", true)) -1 else 1
        lon0 = m.groupValues[4].toInt() * if (m.groupValues[3].equals("W", true)) -1 else 1
        RandomAccessFile(file, "r").use { raf -> buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len) }
        buf.order(ByteOrder.BIG_ENDIAN)
        description = "SRTM ${file.name} (${if (n == 3601) "1\"" else "3\""})"
    }

    private fun sample(r: Int, c: Int): Double? {
        val v = buf.getShort((r * n + c) * 2).toInt()
        return if (v == -32768) null else v.toDouble()
    }

    override fun elevation(lat: Double, lon: Double): Double? {
        if (lat < lat0 || lat > lat0 + 1 || lon < lon0 || lon > lon0 + 1) return null
        val fr = (lat0 + 1 - lat) * (n - 1); val fc = (lon - lon0) * (n - 1)
        val r = floor(fr).toInt().coerceIn(0, n - 2); val c = floor(fc).toInt().coerceIn(0, n - 2)
        val dr = fr - r; val dc = fc - c
        val a = sample(r, c) ?: return null; val b = sample(r, c + 1) ?: return null
        val d = sample(r + 1, c) ?: return null; val e = sample(r + 1, c + 1) ?: return null
        return (a * (1 - dc) + b * dc) * (1 - dr) + (d * (1 - dc) + e * dc) * dr
    }
}

/** ESRI ASCII grid (.asc) in geographic WGS84 coordinates. */
class AscDem(file: File) : ElevationSource {
    private val ncols: Int; private val nrows: Int
    private val xll: Double; private val yll: Double; private val cell: Double; private val nodata: Double
    private val data: FloatArray
    override val description = "ASCII grid ${file.name}"

    init {
        val header = HashMap<String, String>()
        val values = ArrayList<Float>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim().split(Regex("\\s+"))
                if (t.isEmpty() || t[0].isEmpty()) continue
                if (t[0][0].isLetter() && header.size < 6) header[t[0].lowercase()] = t[1]
                else t.forEach { values.add(it.toFloat()) }
            }
        }
        ncols = header["ncols"]!!.toInt(); nrows = header["nrows"]!!.toInt()
        cell = header["cellsize"]!!.toDouble(); nodata = header["nodata_value"]?.toDouble() ?: -9999.0
        xll = header["xllcorner"]?.toDouble() ?: (header["xllcenter"]!!.toDouble() - cell / 2)
        yll = header["yllcorner"]?.toDouble() ?: (header["yllcenter"]!!.toDouble() - cell / 2)
        require(values.size == ncols * nrows) { "ASCII grid has ${values.size} values, expected ${ncols * nrows}" }
        require(xll in -180.0..180.0 && yll in -90.0..90.0 && cell < 1.0) { "ASCII grid must use geographic (WGS84 degree) coordinates" }
        data = values.toFloatArray()
    }

    private fun v(r: Int, c: Int): Double? { val x = data[r * ncols + c].toDouble(); return if (x == nodata) null else x }

    override fun elevation(lat: Double, lon: Double): Double? {
        val fc = (lon - xll) / cell - 0.5; val fr = (yll + nrows * cell - lat) / cell - 0.5
        if (fc < 0 || fr < 0 || fc > ncols - 1 || fr > nrows - 1) return null
        val r = floor(fr).toInt().coerceIn(0, nrows - 2); val c = floor(fc).toInt().coerceIn(0, ncols - 2)
        val dr = fr - r; val dc = fc - c
        val a = v(r, c) ?: return null; val b = v(r, c + 1) ?: return null
        val d = v(r + 1, c) ?: return null; val e = v(r + 1, c + 1) ?: return null
        return (a * (1 - dc) + b * dc) * (1 - dr) + (d * (1 - dc) + e * dc) * dr
    }
}

object Terrain {
    fun load(path: String?): ElevationSource? {
        if (path == null) return null
        val f = File(path); if (!f.exists()) return null
        return runCatching { if (f.name.endsWith(".asc", true)) AscDem(f) else HgtDem(f) }.getOrNull()
    }

    fun takeoffPoint(p: Project): GeoPoint? = p.takeoff ?: p.waypoints.firstOrNull()?.point

    /**
     * Height relative to the takeoff point, as written to DJI Fly (relativeToStartPoint).
     * In TERRAIN_AGL mode with a DEM: AGL target + ground(wp) − ground(takeoff). Otherwise the height is used as-is.
     */
    fun relativeHeight(p: Project, wp: Waypoint, dem: ElevationSource?): Double {
        val h = p.heightOf(wp)
        if (p.settings.altitudeMode != AltitudeMode.TERRAIN_AGL || dem == null) return h
        val t = takeoffPoint(p) ?: return h
        val g0 = dem.elevation(t.lat, t.lon) ?: return h
        val g = dem.elevation(wp.lat, wp.lon) ?: return h
        return h + g - g0
    }

    data class ProfileSample(val distM: Double, val groundM: Double?, val aircraftM: Double, val wpIndex: Int)

    /** Terrain/aircraft profile along the route sampled every [stepM]. Aircraft altitude is linearly interpolated between waypoints. */
    fun profile(p: Project, dem: ElevationSource?, stepM: Double = 20.0): List<ProfileSample> {
        val wps = p.waypoints; if (wps.isEmpty()) return emptyList()
        val t = takeoffPoint(p)!!
        val g0 = dem?.elevation(t.lat, t.lon) ?: 0.0
        val out = ArrayList<ProfileSample>()
        var dist = 0.0
        for (i in wps.indices) {
            val a = wps[i]; val ha = g0 + relativeHeight(p, a, dem)
            out += ProfileSample(dist, dem?.elevation(a.lat, a.lon), ha, i)
            if (i == wps.lastIndex) break
            val b = wps[i + 1]; val hb = g0 + relativeHeight(p, b, dem)
            val d = GeoMath.distance(a.point, b.point)
            var s = stepM
            while (s < d) {
                val f = s / d; val pt = GeoMath.interpolate(a.point, b.point, f)
                out += ProfileSample(dist + s, dem?.elevation(pt.lat, pt.lon), ha + (hb - ha) * f, -1)
                s += stepM
            }
            dist += d
        }
        return out
    }

    fun minClearance(samples: List<ProfileSample>): Double? = samples.mapNotNull { s -> s.groundM?.let { s.aircraftM - it } }.minOrNull()
}
