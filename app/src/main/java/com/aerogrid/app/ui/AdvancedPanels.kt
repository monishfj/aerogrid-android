package com.aerogrid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerogrid.app.data.AppSettings
import com.aerogrid.app.engine.MissionSplitter
import com.aerogrid.app.engine.Photogrammetry
import com.aerogrid.app.engine.Severity
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.engine.Timeline
import com.aerogrid.app.engine.formatDuration
import com.aerogrid.app.io.Reports
import com.aerogrid.app.model.AltitudeMode
import com.aerogrid.app.model.CameraProfile
import com.aerogrid.app.model.CaptureStrategy
import com.aerogrid.app.model.FinishAction
import com.aerogrid.app.model.FlightPattern
import com.aerogrid.app.model.LostAction
import com.aerogrid.app.model.PhotoResolution
import com.aerogrid.app.model.TurnMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val PATTERN_LABEL = mapOf(FlightPattern.LAWNMOWER to "Lawnmower grid", FlightPattern.CROSSHATCH to "Crosshatch / double grid", FlightPattern.CORRIDOR to "Corridor",
    FlightPattern.PERIMETER to "Perimeter", FlightPattern.MANUAL to "Manual route")

@Composable
fun MappingTab(vm: PlannerViewModel, ui: PlannerUi) {
    val p = ui.project; val s = p.settings; val cam = p.camera
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Dropdown("Flight pattern", FlightPattern.entries, s.pattern, { PATTERN_LABEL[it]!! }) { v -> vm.updateSettings { it.copy(pattern = v) } }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CaptureStrategy.entries.forEachIndexed { i, c -> SegmentedButton(selected = s.capture == c, onClick = { vm.updateSettings { it.copy(capture = c) } }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (c == CaptureStrategy.TIMED_INTERVAL) "Timed photos" else "Photo per waypoint") } }
        }
        SectionTitle("Altitude & resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Flight height", s.heightM, Modifier.weight(1f), suffix = "m") { v -> vm.setGlobalHeight(v) }
            NumberField("Target GSD", ui.stats.gsdCm, Modifier.weight(1f), decimals = 2, suffix = "cm/px") { g -> vm.setGlobalHeight(Photogrammetry.heightForGsd(cam, s.resolution, g)) }
        }
        Dropdown("Altitude reference", AltitudeMode.entries, s.altitudeMode, { if (it == AltitudeMode.TERRAIN_AGL) "Above ground (terrain-adjusted)" else "Relative to takeoff" }) { v -> vm.updateSettings { it.copy(altitudeMode = v) } }
        Dropdown("Photo resolution", PhotoResolution.entries, s.resolution, { it.label }) { v -> vm.updateSettings(false) { it.copy(resolution = v, intervalSec = cam.intervals(v).minBy { iv -> kotlin.math.abs(iv - it.intervalSec) }) } }
        SectionTitle("Overlap & spacing")
        LabeledSlider("Forward overlap", s.frontOverlap, 50.0..95.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings { it.copy(frontOverlap = v) } }
        LabeledSlider("Side overlap", s.sideOverlap, 40.0..95.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings { it.copy(sideOverlap = v, lineSpacingM = null) } }
        LineSpacingControl(ui, vm)
        Text("Footprint %.0f × %.0f m · line spacing %.1f m · photo spacing %.1f m".format(ui.stats.footprintW, ui.stats.footprintH, ui.stats.lineSpacing, ui.stats.photoSpacing), style = MaterialTheme.typography.bodySmall)
        SwitchRow("Automatic grid bearing", s.bearingDeg == null, "Aligned with the longest side to minimise turns" + (p.gridBearingDeg?.let { " (%.0f°)".format(it) } ?: "")) { auto -> vm.updateSettings { it.copy(bearingDeg = if (auto) null else p.gridBearingDeg ?: 0.0) } }
        s.bearingDeg?.let { b -> LabeledSlider("Grid bearing / rotation", b, 0.0..179.0, { "%.0f°".format(it) }) { v -> vm.updateSettings { it.copy(bearingDeg = v) } } }
        LabeledSlider("Survey buffer", s.bufferM, -50.0..50.0, { "%+.0f m".format(it) }) { v -> vm.updateSettings { it.copy(bufferM = v) } }
        LabeledSlider("Line extension (overshoot)", s.lineExtensionM, 0.0..50.0, { "%.0f m".format(it) }) { v -> vm.updateSettings { it.copy(lineExtensionM = v) } }
        if (s.pattern == FlightPattern.CORRIDOR) LabeledSlider("Corridor width", s.corridorWidthM, 10.0..300.0, { "%.0f m".format(it) }) { v -> vm.updateSettings { it.copy(corridorWidthM = v) } }
        SectionTitle("Speed & camera timing")
        SwitchRow("Automatic speed", s.speedMs == null, "Required for overlap: %.1f m/s".format(ui.stats.requiredSpeed)) { auto -> vm.updateSettings(false) { it.copy(speedMs = if (auto) null else ui.stats.speedMs) } }
        s.speedMs?.let { v0 -> LabeledSlider("Flight speed", v0, 0.5..cam.maxSpeed, { "%.1f m/s".format(it) }) { v -> vm.updateSettings(false) { it.copy(speedMs = v) } } }
        Dropdown("Photo interval", cam.intervals(s.resolution), s.intervalSec, { "$it s" }) { v -> vm.updateSettings(false) { it.copy(intervalSec = v) } }
        LabeledSlider("Gimbal pitch", s.gimbalPitch, -90.0..0.0, { "%.0f°".format(it) }) { v -> vm.updateSettings(false) { it.copy(gimbalPitch = v) } }
        Dropdown("Turn behaviour", TurnMode.entries, s.turnMode, ::turnLabel) { v -> vm.updateSettings(false) { it.copy(turnMode = v) } }
        LabeledSlider("Transit speed", s.transitSpeedMs, 2.0..15.0, { "%.0f m/s".format(it) }) { v -> vm.updateSettings(false) { it.copy(transitSpeedMs = v) } }
        SectionTitle("Mission end & safety")
        Dropdown("Finish action", FinishAction.entries, s.finishAction, { it.label }) { v -> vm.updateSettings(false) { it.copy(finishAction = v) } }
        Dropdown("Signal-loss action", LostAction.entries, s.lostAction, { it.label }) { v -> vm.updateSettings(false) { it.copy(lostAction = v) } }
        LabeledSlider("Battery flight time", s.batteryMinutes, 10.0..45.0, { "%.0f min".format(it) }) { v -> vm.resplit(s.maxWaypointsPerMission, v) }
        LabeledSlider("Battery reserve", s.batteryReservePct, 10.0..50.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings(false) { it.copy(batteryReservePct = v) } }
        LabeledSlider("Min. terrain clearance", s.minClearanceM, 5.0..100.0, { "%.0f m".format(it) }) { v -> vm.updateSettings(false) { it.copy(minClearanceM = v) } }
        CameraEditor(cam) { vm.updateCamera(it) }
        Button(onClick = { vm.generate() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Regenerate waypoints") }
    }
}

@Composable
fun CameraEditor(cam: CameraProfile, onChange: (CameraProfile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide camera sensor parameters" else "Camera sensor parameters…") }
    if (!open) return
    Text("${cam.name}: HFOV %.1f° · VFOV %.1f° · DFOV %.1f°".format(cam.hfovDeg, cam.vfovDeg, cam.dfovDeg), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Sensor W", cam.sensorWidthMm, Modifier.weight(1f), 2, "mm") { onChange(cam.copy(sensorWidthMm = it)) }
        NumberField("Sensor H", cam.sensorHeightMm, Modifier.weight(1f), 2, "mm") { onChange(cam.copy(sensorHeightMm = it)) }
        NumberField("Focal", cam.focalLengthMm, Modifier.weight(1f), 2, "mm") { onChange(cam.copy(focalLengthMm = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Image W", cam.imageWidthPx.toDouble(), Modifier.weight(1f), 0, "px") { onChange(cam.copy(imageWidthPx = it.toInt())) }
        NumberField("Image H", cam.imageHeightPx.toDouble(), Modifier.weight(1f), 0, "px") { onChange(cam.copy(imageHeightPx = it.toInt())) }
        NumberField("Max speed", cam.maxSpeed, Modifier.weight(1f), 1, "m/s") { onChange(cam.copy(maxSpeed = it)) }
    }
    TextButton(onClick = { onChange(CameraProfile.MINI_5_PRO) }) { Text("Restore DJI Mini 5 Pro profile") }
}

@Composable
fun WaypointsTab(vm: PlannerViewModel, ui: PlannerUi, onEditBatch: () -> Unit, onFocus: (Int) -> Unit) {
    val p = ui.project
    val list = rememberLazyListState()
    LaunchedEffect(ui.editingId) { val i = p.waypoints.indexOfFirst { it.id == ui.editingId }; if (i >= 0) list.animateScrollToItem(i) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.selected.size}/${p.waypoints.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (ui.selected.size == p.waypoints.size) vm.clearSelection() else vm.selectAll() }) { Text(if (ui.selected.size == p.waypoints.size && p.waypoints.isNotEmpty()) "None" else "All") }
            IconButton(onClick = onEditBatch, enabled = ui.selected.isNotEmpty()) { Icon(Icons.Default.Edit, "Edit selected") }
            IconButton(onClick = { vm.deleteSelected() }, enabled = ui.selected.isNotEmpty()) { Icon(Icons.Default.Delete, "Delete selected") }
        }
        HorizontalDivider()
        LazyColumn(state = list) {
            itemsIndexed(p.waypoints, key = { _, w -> w.id }) { i, w ->
                val sel = w.id in ui.selected
                Row(Modifier.fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { vm.openEditor(w.id); onFocus(i) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sel, onCheckedChange = { if (!ui.multiSelect) vm.toggleMultiSelect(); vm.selectWaypoint(w.id, false) })
                    Column(Modifier.weight(1f)) {
                        Text("WP%03d%s".format(i + 1, if (w.transit) " · transit" else ""), fontWeight = FontWeight.SemiBold)
                        Text("%.0f m %s · %s · %.0f°%s".format(p.heightOf(w), if (w.heightOverride != null) "(indiv.)" else "(global)",
                            w.speedOverride?.let { "%.1f m/s".format(it) } ?: "auto speed", w.gimbalPitch ?: p.settings.gimbalPitch,
                            if (w.action != com.aerogrid.app.model.CameraAction.NONE) " · " + w.action.name.lowercase().replace('_', ' ') else ""),
                            style = MaterialTheme.typography.bodySmall, color = if (w.heightOverride != null) Color(0xFFFF9100) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.reorder(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                    IconButton(onClick = { vm.reorder(i, i + 1) }, enabled = i < p.waypoints.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                }
            }
        }
    }
}

@Composable
fun AnalysisTab(ui: PlannerUi) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Mission analysis")
        Reports.statsLines(ui.project, ui.stats).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(v, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
        }
        Text("Individually overridden heights: ${ui.stats.overriddenHeights}", style = MaterialTheme.typography.bodySmall)
        SectionTitle("Validation")
        IssuesList(ui)
    }
}

@Composable
fun IssuesList(ui: PlannerUi) {
    ui.issues.forEach { i ->
        val c = when (i.severity) { Severity.ERROR -> MaterialTheme.colorScheme.error; Severity.WARNING -> Color(0xFFFF9100); Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant }
        Row(Modifier.padding(vertical = 4.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 5.dp).size(8.dp).background(c, CircleShape))
            Column(Modifier.padding(start = 8.dp)) {
                Text(i.message, style = MaterialTheme.typography.bodyMedium, color = if (i.severity == Severity.INFO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (i.fix.isNotEmpty()) Text(i.fix, style = MaterialTheme.typography.bodySmall, color = c)
            }
        }
    }
}

@Composable
fun TerrainTab(vm: PlannerViewModel, ui: PlannerUi, onImportDem: () -> Unit) {
    val p = ui.project
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Dropdown("Altitude reference", AltitudeMode.entries, p.settings.altitudeMode, { if (it == AltitudeMode.TERRAIN_AGL) "Above ground (terrain-adjusted)" else "Relative to takeoff" }) { v -> vm.updateSettings { it.copy(altitudeMode = v) } }
        Text(ui.demInfo?.let { "Terrain model: $it" } ?: "No terrain data loaded. Heights are flown relative to the takeoff point; true AGL clearance is unknown.",
            style = MaterialTheme.typography.bodyMedium, color = if (ui.demInfo == null) Color(0xFFFF9100) else MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilledTonalButton(onClick = onImportDem) { Text("Import DEM (.hgt / .asc)") }
            if (p.demPath != null) OutlinedButton(onClick = { vm.removeDem() }) { Text("Remove") }
        }
        if (p.settings.altitudeMode == AltitudeMode.TERRAIN_AGL && ui.demInfo != null) {
            LabeledSlider("Terrain sample spacing", p.settings.terrainSampleM, 10.0..150.0, { "%.0f m".format(it) }) { v -> vm.updateSettings { it.copy(terrainSampleM = v) } }
            Text("Lines are densified at this spacing and each waypoint height is pre-computed from the DEM. DJI Fly flies these as fixed heights relative to takeoff — this is not live terrain following.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { vm.setTool(Tool.SET_TAKEOFF) }) { Text(if (p.takeoff == null) "Set takeoff point on map (recommended)" else "Move takeoff point") }
        SectionTitle("Terrain profile")
        TerrainProfile(ui.profile)
        Terrain.minClearance(ui.profile)?.let { Text("Minimum predicted clearance: %.0f m".format(it), fontWeight = FontWeight.SemiBold, color = if (it < p.settings.minClearanceM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
        Text("Terrain and obstacle data may be inaccurate. Trees, buildings and towers are not in the DEM.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TerrainProfile(samples: List<Terrain.ProfileSample>) {
    if (samples.size < 2) { Text("Generate a route to see the profile", style = MaterialTheme.typography.bodySmall); return }
    val ground = MaterialTheme.colorScheme.tertiary; val air = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 6.dp)) {
        val maxD = samples.last().distM.coerceAtLeast(1.0)
        val ys = samples.flatMap { listOfNotNull(it.groundM, it.aircraftM) }
        val lo = ys.min() - 5; val hi = ys.max() + 5
        fun x(d: Double) = (d / maxD * size.width).toFloat()
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        for (k in 0..4) { val yy = size.height * k / 4; drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1f) }
        val g = samples.filter { it.groundM != null }
        if (g.size >= 2) {
            val path = Path().apply { moveTo(x(g.first().distM), size.height); g.forEach { lineTo(x(it.distM), y(it.groundM!!)) }; lineTo(x(g.last().distM), size.height); close() }
            drawPath(path, ground.copy(alpha = 0.35f))
        }
        val a = Path().apply { moveTo(x(samples.first().distM), y(samples.first().aircraftM)); samples.forEach { lineTo(x(it.distM), y(it.aircraftM)) } }
        drawPath(a, air, style = Stroke(3f))
    }
    Text("Blue: aircraft altitude · Green: terrain (DEM)" + if (samples.all { it.groundM == null }) " — no DEM loaded" else "", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlocksTab(vm: PlannerViewModel, ui: PlannerUi) {
    val p = ui.project
    var maxWp by remember(p.settings.maxWaypointsPerMission) { mutableIntStateOf(p.settings.maxWaypointsPerMission) }
    var minutes by remember(p.settings.batteryMinutes) { mutableDoubleStateOf(p.settings.batteryMinutes) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Missions are split automatically to stay within the waypoint limit and usable battery time. DJI Fly flies each block as a separate mission; it does not resume across battery changes.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Max waypoints", maxWp.toDouble(), Modifier.weight(1f), 0) { maxWp = it.toInt().coerceIn(2, 200) }
            NumberField("Battery", minutes, Modifier.weight(1f), 0, "min") { minutes = it.coerceIn(5.0, 60.0) }
            Button(onClick = { vm.resplit(maxWp, minutes) }) { Text("Split") }
        }
        p.blocks.forEachIndexed { i, b ->
            val wps = MissionSplitter.blockWaypoints(p, b)
            val d = Timeline.duration(p, wps)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(if (ui.highlightBlock == i) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent).padding(8.dp)) {
                Text(b.name, fontWeight = FontWeight.Bold)
                Text("WP%03d–WP%03d · ${wps.size} waypoints · ${formatDuration(d)}".format(b.start + 1, b.end + 1), style = MaterialTheme.typography.bodySmall,
                    color = if (d > p.settings.batteryMinutes * 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { vm.highlightBlock(if (ui.highlightBlock == i) null else i) }, label = { Text("Show") })
                    AssistChip(onClick = { vm.selectBlock(i) }, label = { Text("Select / edit") })
                    AssistChip(onClick = { vm.exportKmz(i, share = false) }, label = { Text("Export KMZ") })
                }
            }
        }
        if (p.blocks.size > 1) Button(onClick = { vm.exportKmz(null, share = false) }, modifier = Modifier.fillMaxWidth()) { Text("Export all ${p.blocks.size} DJI missions") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportTab(vm: PlannerViewModel, ui: PlannerUi, settings: AppSettings, onImport: (Boolean) -> Unit, onImage: (Boolean) -> Unit, onTransfer: () -> Unit, onOffline: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("DJI Fly")
        Text("Drone ID ${settings.drone.droneEnumValue}/${settings.drone.droneSubEnumValue} · ${settings.drone.source}", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.exportKmz(null, share = false) }) { Text("Save DJI KMZ") }
            FilledTonalButton(onClick = { vm.exportKmz(null, share = true) }) { Text("Share KMZ") }
            FilledTonalButton(onClick = onTransfer) { Text("Transfer to DJI Fly") }
        }
        SectionTitle("Other formats")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("kml" to "KML", "geojson" to "GeoJSON", "csv" to "CSV", "gpx" to "GPX", "project" to "AeroGrid project").forEach { (k, l) -> OutlinedButton(onClick = { vm.exportFormat(k) }) { Text(l) } }
            OutlinedButton(onClick = { onImage(false) }) { Text("PNG image") }
            OutlinedButton(onClick = { onImage(true) }) { Text("PDF summary") }
        }
        SectionTitle("Import")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onImport(false) }) { Text("Boundary / zones (KML, KMZ, GeoJSON, GPX)") }
            OutlinedButton(onClick = { onImport(true) }) { Text("Waypoints / DJI mission") }
        }
        SectionTitle("Offline map")
        OutlinedButton(onClick = onOffline) { Text("Download street map for visible area") }
        if (ui.project.exportHistory.isNotEmpty()) {
            SectionTitle("Export history")
            val f = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
            ui.project.exportHistory.takeLast(12).reversed().forEach { Text("${f.format(Date(it.time))} · ${it.kind} · ${it.target}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Distance between flight lines in metres. "Auto" derives it from side overlap; a fixed value keeps the spacing when height changes. */
@Composable
fun LineSpacingControl(ui: PlannerUi, vm: PlannerViewModel) {
    val s = ui.project.settings; val fw = ui.stats.footprintW
    if (fw <= 0 || s.pattern == FlightPattern.MANUAL || (ui.project.boundaries.isEmpty() && ui.project.corridor.isEmpty())) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Line spacing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (s.lineSpacingM != null) TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = null) } }) { Text("Reset to auto") }
        else Text("Auto · from %.0f%% overlap".format(s.sideOverlap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LabeledSlider("Distance between flight lines", ui.stats.lineSpacing, 2.0..maxOf(150.0, kotlin.math.ceil(fw)),
        { "%.0f m · side overlap %.0f%%".format(it, (1 - it / fw) * 100) }) { v -> vm.updateSettings { it.copy(lineSpacingM = kotlin.math.round(v).coerceAtLeast(2.0)) } }
    val q = com.aerogrid.app.engine.OverlapAdvice.side(ui.stats.sideOverlap)
    val color = when (q.level) { 0 -> Color(0xFF2E7D32); 1 -> Color(0xFFB8860B); 2 -> Color(0xFFFF6D00); else -> MaterialTheme.colorScheme.error }
    val recommended = fw * (1 - s.sideOverlap.coerceAtLeast(70.0) / 100)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(color, CircleShape))
        Text("  " + q.text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        if (q.level > 0 && kotlin.math.abs(ui.stats.lineSpacing - recommended) > 0.5)
            TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = kotlin.math.round(recommended).coerceAtLeast(2.0)) } }) { Text("Use %.0f m".format(recommended)) }
    }
}
