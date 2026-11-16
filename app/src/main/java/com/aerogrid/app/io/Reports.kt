package com.aerogrid.app.io

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.aerogrid.app.engine.ElevationSource
import com.aerogrid.app.engine.Issue
import com.aerogrid.app.engine.MissionStats
import com.aerogrid.app.engine.Severity
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.engine.formatArea
import com.aerogrid.app.engine.formatDistance
import com.aerogrid.app.engine.formatDuration
import com.aerogrid.app.model.Project
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Reports {
    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun statsLines(p: Project, st: MissionStats): List<Pair<String, String>> = listOf(
        "Survey area" to formatArea(st.areaM2), "Waypoints" to "${st.waypointCount}", "Flight lines" to "${st.lineCount}",
        "Total distance" to formatDistance(st.distanceM), "Flight height" to "%.0f m (%s)".format(st.heightM, p.settings.altitudeMode.name.replace('_', ' ').lowercase()),
        "GSD" to "%.2f cm/px".format(st.gsdCm), "Overlap (front/side)" to "%.0f%% / %.0f%%".format(st.frontOverlap, st.sideOverlap),
        "Photos (est.)" to "${st.photoCount} (~%.1f GB)".format(st.storageMb / 1024), "Duration (est.)" to formatDuration(st.durationSec),
        "Speed" to "%.1f m/s".format(st.speedMs), "Batteries (est.)" to "${st.batteries}", "Missions/blocks" to "${st.missionCount}",
        "Camera" to st.camera, "Footprint" to "%.0f × %.0f m".format(st.footprintW, st.footprintH),
        "Line / photo spacing" to "%.1f m / %.1f m".format(st.lineSpacing, st.photoSpacing),
        "Capture" to if (p.settings.capture.name == "TIMED_INTERVAL") "Timed shot every ${p.settings.intervalSec} s (set in DJI Fly)" else "Photo at each waypoint",
        "Gimbal" to "%.0f°".format(p.settings.gimbalPitch), "Finish / signal loss" to "${p.settings.finishAction.label} / ${p.settings.lostAction.label}",
    )

    fun pdf(p: Project, st: MissionStats, issues: List<Issue>, map: Bitmap?, dem: ElevationSource?): ByteArray {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 36
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val h2 = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint().apply { textSize = 9f }
        val small = Paint().apply { textSize = 7.5f; color = Color.DKGRAY }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        var y = M + 18f
        c.drawText("AeroGrid mission summary — ${p.name}", M.toFloat(), y, title); y += 14
        c.drawText("${p.location}  ·  generated ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}", M.toFloat(), y, small); y += 10
        map?.let { bmp ->
            val maxW = W - 2 * M; val maxH = 330
            val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
            val dw = (bmp.width * scale).toInt(); val dh = (bmp.height * scale).toInt()
            c.drawBitmap(bmp, null, Rect(M, y.toInt(), M + dw, y.toInt() + dh), null); y += dh + 14
        }
        c.drawText("Flight statistics", M.toFloat(), y, h2); y += 14
        statsLines(p, st).chunked(2).forEach { row ->
            row.forEachIndexed { i, (k, v) -> val x = M + i * 270f; c.drawText("$k:", x, y, small); c.drawText(v, x + 95, y, body) }
            y += 12
        }
        y += 6
        c.drawText("Validation", M.toFloat(), y, h2); y += 14
        issues.filter { it.severity != Severity.INFO || it.message.startsWith("Timed") || it.message.startsWith("Routes") }.forEach { iss ->
            if (y > H - M - 20) return@forEach
            val tag = when (iss.severity) { Severity.ERROR -> "ERROR"; Severity.WARNING -> "WARN"; Severity.INFO -> "NOTE" }
            c.drawText("[$tag] ${iss.message}".take(130), M.toFloat(), y, body); y += 11
        }
        doc.finishPage(page)
        // Waypoint table pages
        val rows = p.waypoints.withIndex().toList()
        rows.chunked(60).forEach { chunk ->
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); c = page.canvas
            var yy = M + 12f
            c.drawText("Waypoints (heights: project mode / relative to takeoff)", M.toFloat(), yy, h2); yy += 14
            val cols = listOf("WP", "Latitude", "Longitude", "Height", "Rel. TO", "Mode", "Speed", "Gimbal", "Action", "Block")
            val xs = listOf(0, 34, 110, 186, 230, 274, 330, 376, 420, 480)
            cols.forEachIndexed { i, s -> c.drawText(s, M + xs[i].toFloat(), yy, h2.apply { textSize = 8f }) }; yy += 10
            chunk.forEach { (i, w) ->
                val blk = p.blocks.indexOfFirst { i in it.start..it.end } + 1
                val v = listOf("%03d".format(i + 1), "%.7f".format(w.lat), "%.7f".format(w.lon), "%.1f".format(p.heightOf(w)), "%.1f".format(Terrain.relativeHeight(p, w, dem)),
                    if (w.heightOverride != null) "indiv." else "global", w.speedOverride?.let { "%.1f".format(it) } ?: "auto", "%.0f°".format(w.gimbalPitch ?: p.settings.gimbalPitch),
                    if (w.transit) "transit" else w.action.name.lowercase().replace('_', ' '), "$blk")
                v.forEachIndexed { k, s -> c.drawText(s, M + xs[k].toFloat(), yy, body) }; yy += 12
            }
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
