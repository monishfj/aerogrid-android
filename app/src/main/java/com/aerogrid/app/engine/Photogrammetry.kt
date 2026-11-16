package com.aerogrid.app.engine

import com.aerogrid.app.model.CameraProfile
import com.aerogrid.app.model.FlightSettings
import com.aerogrid.app.model.PhotoResolution
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Plain-language quality rating of side overlap for photogrammetry. level 0 good, 1 acceptable, 2 risky, 3 gaps. */
object OverlapAdvice {
    data class Advice(val level: Int, val text: String)
    fun side(pct: Double) = when {
        pct >= 70 -> Advice(0, "Good: %.0f%% side overlap — reliable orthomosaic".format(pct))
        pct >= 60 -> Advice(1, "Acceptable: %.0f%% side overlap — trees/roofs may stitch poorly".format(pct))
        pct >= 0 -> Advice(2, "Low: %.0f%% side overlap — expect holes and failed stitching".format(pct))
        else -> Advice(3, "Gaps: lines are further apart than the photo width — areas will be missed")
    }
}

/** Nadir camera geometry. Image width (long side) is oriented across track. */
object Photogrammetry {
    data class Footprint(val widthM: Double, val heightM: Double)

    fun footprint(cam: CameraProfile, heightM: Double, zoom: Double = 1.0): Footprint {
        val z = max(zoom, 1.0)
        return Footprint(heightM * cam.sensorWidthMm / cam.focalLengthMm / z, heightM * cam.sensorHeightMm / cam.focalLengthMm / z)
    }

    /** Ground sampling distance in cm/pixel. */
    fun gsdCm(cam: CameraProfile, res: PhotoResolution, heightM: Double, zoom: Double = 1.0) =
        footprint(cam, heightM, zoom).widthM / cam.widthPx(res) * 100.0

    /** Height (m) needed to achieve a target GSD (cm/px). */
    fun heightForGsd(cam: CameraProfile, res: PhotoResolution, gsdCm: Double) =
        gsdCm / 100.0 * cam.widthPx(res) * cam.focalLengthMm / cam.sensorWidthMm

    fun lineSpacing(cam: CameraProfile, s: FlightSettings) = s.lineSpacingM?.coerceAtLeast(1.0) ?: (footprint(cam, s.heightM, s.zoom).widthM * (1 - s.sideOverlap / 100.0))

    /** Side overlap (%) actually produced by the current line spacing (differs from the target when spacing is fixed). */
    fun actualSideOverlap(cam: CameraProfile, s: FlightSettings) = (1 - lineSpacing(cam, s) / footprint(cam, s.heightM, s.zoom).widthM) * 100.0
    fun photoSpacing(cam: CameraProfile, s: FlightSettings) = footprint(cam, s.heightM, s.zoom).heightM * (1 - s.frontOverlap / 100.0)

    /** Speed giving exactly the requested forward overlap at the timed interval, capped at aircraft limit. */
    fun requiredSpeed(cam: CameraProfile, s: FlightSettings) = min(photoSpacing(cam, s) / max(s.intervalSec, 1), cam.maxSpeed)

    fun missionSpeed(cam: CameraProfile, s: FlightSettings) = (s.speedMs ?: requiredSpeed(cam, s)).coerceIn(0.5, cam.maxSpeed)

    /** Forward overlap (%) actually produced by timed capture at [speed]. */
    fun actualFrontOverlap(cam: CameraProfile, s: FlightSettings, speed: Double): Double {
        val h = footprint(cam, s.heightM, s.zoom).heightM
        return ((1 - speed * s.intervalSec / h) * 100.0).coerceAtLeast(-100.0)
    }
}
