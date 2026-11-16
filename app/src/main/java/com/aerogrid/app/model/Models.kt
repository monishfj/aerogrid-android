package com.aerogrid.app.model

import kotlinx.serialization.Serializable
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

@Serializable
data class GeoPoint(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0 && !lat.isNaN() && !lon.isNaN()
}

@Serializable enum class CameraAction { NONE, TAKE_PHOTO, START_RECORD, STOP_RECORD }
@Serializable enum class TurnMode { STOP_AT_POINT, COORDINATED_TURN, CURVED_PASS }
@Serializable enum class HeadingMode { FOLLOW_ROUTE, FIXED, MANUAL }
@Serializable enum class AltitudeMode { RELATIVE_TAKEOFF, TERRAIN_AGL }
@Serializable enum class FlightPattern { LAWNMOWER, CROSSHATCH, CORRIDOR, PERIMETER, MANUAL }
@Serializable enum class CaptureStrategy { TIMED_INTERVAL, WAYPOINT_PHOTO }
@Serializable enum class PhotoResolution(val label: String) { MP50("50 MP"), MP12("12 MP") }
@Serializable enum class FinishAction(val wpml: String, val label: String) {
    GO_HOME("goHome", "Return to home"), AUTO_LAND("autoLand", "Land"), HOVER("noAction", "Hover"), GOTO_FIRST("gotoFirstWaypoint", "Go to first waypoint")
}
@Serializable enum class LostAction(val wpml: String, val label: String) {
    GO_BACK("goBack", "Return to home"), LAND("landing", "Land"), HOVER("hover", "Hover")
}

/**
 * A mission waypoint. All optional fields are per-waypoint overrides: null means "use global setting".
 * [heightOverride] is the individually overridden height (metres) in the project altitude mode.
 */
@Serializable
data class Waypoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val heightOverride: Double? = null,
    val speedOverride: Double? = null,
    val headingMode: HeadingMode = HeadingMode.FOLLOW_ROUTE,
    val headingDeg: Double = 0.0,
    val gimbalPitch: Double? = null,
    val zoom: Double? = null,
    val action: CameraAction = CameraAction.NONE,
    val hoverSec: Double = 0.0,
    val turnMode: TurnMode? = null,
    val lineIndex: Int = -1,
    val transit: Boolean = false,
    val moved: Boolean = false,
) {
    val point get() = GeoPoint(lat, lon)
    val hasOverrides get() = heightOverride != null || speedOverride != null || gimbalPitch != null || headingMode != HeadingMode.FOLLOW_ROUTE || zoom != null || hoverSec > 0
}

@Serializable
data class CameraProfile(
    val name: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val equivFocalMm: Double,
    val lowResWidthPx: Int,
    val lowResHeightPx: Int,
    val intervals50: List<Int> = listOf(5, 7, 10, 15, 20, 30, 60),
    val intervals12: List<Int> = listOf(2, 3, 5, 7, 10, 15, 20, 30, 60),
    val maxSpeed: Double = 15.0,
    val photoSizeMb50: Double = 22.0,
    val photoSizeMb12: Double = 6.0,
) {
    val hfovDeg get() = Math.toDegrees(2 * atan(sensorWidthMm / (2 * focalLengthMm)))
    val vfovDeg get() = Math.toDegrees(2 * atan(sensorHeightMm / (2 * focalLengthMm)))
    val dfovDeg get() = Math.toDegrees(2 * atan(sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm) / (2 * focalLengthMm)))
    fun widthPx(res: PhotoResolution) = if (res == PhotoResolution.MP50) imageWidthPx else lowResWidthPx
    fun heightPx(res: PhotoResolution) = if (res == PhotoResolution.MP50) imageHeightPx else lowResHeightPx
    fun intervals(res: PhotoResolution) = if (res == PhotoResolution.MP50) intervals50 else intervals12

    companion object {
        /** Build a profile from diagonal FOV and image aspect so that FOV geometry is exact. */
        fun fromDiagonalFov(name: String, dfovDeg: Double, wPx: Int, hPx: Int, sensorWidthMm: Double, equivFocal: Double, lowW: Int, lowH: Int): CameraProfile {
            val sensorH = sensorWidthMm * hPx / wPx
            val diag = sqrt(sensorWidthMm * sensorWidthMm + sensorH * sensorH)
            val f = diag / (2 * tan(Math.toRadians(dfovDeg / 2)))
            return CameraProfile(name, sensorWidthMm, sensorH, f, wPx, hPx, equivFocal, lowW, lowH)
        }

        /** DJI Mini 5 Pro main camera: 1-inch CMOS, 50 MP, 8192×6144 (4:3), 84° DFOV, 24 mm equivalent. */
        val MINI_5_PRO = fromDiagonalFov("DJI Mini 5 Pro (1\" 50 MP)", 84.0, 8192, 6144, 13.2, 24.0, 4096, 3072)
    }
}

@Serializable
data class FlightSettings(
    val heightM: Double = 50.0,
    val altitudeMode: AltitudeMode = AltitudeMode.TERRAIN_AGL,
    val speedMs: Double? = null,
    val frontOverlap: Double = 80.0,
    val sideOverlap: Double = 75.0,
    /** Fixed distance between flight lines (m). null = derived from side overlap. */
    val lineSpacingM: Double? = null,
    val bearingDeg: Double? = null,
    val gimbalPitch: Double = -90.0,
    val zoom: Double = 1.0,
    val intervalSec: Int = 5,
    val resolution: PhotoResolution = PhotoResolution.MP50,
    val pattern: FlightPattern = FlightPattern.LAWNMOWER,
    val capture: CaptureStrategy = CaptureStrategy.TIMED_INTERVAL,
    val bufferM: Double = 0.0,
    val lineExtensionM: Double = 0.0,
    val corridorWidthM: Double = 60.0,
    val maxWaypointsPerMission: Int = 200,
    val batteryMinutes: Double = 30.0,
    val batteryReservePct: Double = 25.0,
    val finishAction: FinishAction = FinishAction.GO_HOME,
    val lostAction: LostAction = LostAction.GO_BACK,
    val turnMode: TurnMode = TurnMode.STOP_AT_POINT,
    val transitSpeedMs: Double = 8.0,
    val terrainSampleM: Double = 40.0,
    val minClearanceM: Double = 30.0,
) {
    companion object { val DEFAULT = FlightSettings() }
}

/** Contiguous waypoint index range [start, end] (inclusive). Adjacent blocks may share one boundary waypoint. */
@Serializable
data class MissionBlock(val name: String, val start: Int, val end: Int)

@Serializable
data class ExportRecord(val time: Long, val kind: String, val target: String)

@Serializable
data class Project(
    val id: Long = 0,
    val name: String = "Untitled mission",
    val location: String = "",
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
    val boundaries: List<List<GeoPoint>> = emptyList(),
    val exclusions: List<List<GeoPoint>> = emptyList(),
    val corridor: List<GeoPoint> = emptyList(),
    val settings: FlightSettings = FlightSettings(),
    val camera: CameraProfile = CameraProfile.MINI_5_PRO,
    val waypoints: List<Waypoint> = emptyList(),
    val generated: List<Waypoint> = emptyList(),
    val blocks: List<MissionBlock> = emptyList(),
    val takeoff: GeoPoint? = null,
    val landing: GeoPoint? = null,
    val demPath: String? = null,
    val exportHistory: List<ExportRecord> = emptyList(),
    val transferState: String = "",
    val nextId: Long = 1,
    val gridHeightM: Double? = null,
    val gridBearingDeg: Double? = null,
) {
    fun heightOf(wp: Waypoint) = wp.heightOverride ?: settings.heightM
    val hasAdvancedConfig: Boolean
        get() = exclusions.isNotEmpty() || boundaries.size > 1 || settings.pattern != FlightPattern.LAWNMOWER ||
            settings.capture != CaptureStrategy.TIMED_INTERVAL || settings.bufferM != 0.0 || settings.lineExtensionM != 0.0 ||
            waypoints.any { it.speedOverride != null || it.headingMode != HeadingMode.FOLLOW_ROUTE || it.zoom != null || it.hoverSec > 0 || it.action != CameraAction.NONE && settings.capture == CaptureStrategy.TIMED_INTERVAL } ||
            camera != CameraProfile.MINI_5_PRO
}

/** DJI drone identification written into WPML; can be replaced by values read from a genuine DJI Fly mission. */
@Serializable
data class DroneInfo(
    val droneEnumValue: Int = 68,
    val droneSubEnumValue: Int = 0,
    val namespace: String = "http://www.uav.com/wpmz/1.0.2",
    val payloadEnumValue: Int? = null,
    val payloadSubEnumValue: Int? = null,
    val source: String = "AeroGrid default (community-observed DJI Fly value, unverified)",
)
