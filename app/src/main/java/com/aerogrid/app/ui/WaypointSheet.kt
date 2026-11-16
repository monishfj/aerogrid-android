package com.aerogrid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.model.CameraAction
import com.aerogrid.app.model.HeadingMode
import com.aerogrid.app.model.TurnMode

/** Bottom sheet editing one waypoint, or a batch when several are selected. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WaypointSheet(vm: PlannerViewModel, ui: PlannerUi, batch: Boolean, onDismiss: () -> Unit) {
    val p = ui.project
    val targets = if (batch) ui.selectedWaypoints else p.waypoints.filter { it.id == ui.editingId }
    if (targets.isEmpty()) { onDismiss(); return }
    val ids = targets.map { it.id }.toSet()
    val w = targets.first()
    val index = p.waypoints.indexOfFirst { it.id == w.id }
    val advanced = ui.mode != Mode.QUICK
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (batch) "${targets.size} waypoints selected" else "WP%03d".format(index + 1), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (targets.any { it.heightOverride != null }) Text("Individual height", color = Color(0xFFFF9100), style = MaterialTheme.typography.labelMedium)
            }
            if (!advanced) Text("Automatic: speed %.1f m/s · gimbal %.0f° · heading follows route. Drag the waypoint on the map to move it.".format(ui.stats.speedMs, p.settings.gimbalPitch),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!batch && advanced) {
                Text("Relative to takeoff: %.1f m%s".format(Terrain.relativeHeight(p, w, vm.demSource()), if (w.transit) " · transit point" else ""), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    NumberField("Latitude", w.lat, Modifier.weight(1f), decimals = 7) { v -> if (v in -90.0..90.0) vm.updateWaypoints(ids) { it.copy(lat = v, moved = true) } }
                    NumberField("Longitude", w.lon, Modifier.weight(1f), decimals = 7) { v -> if (v in -180.0..180.0) vm.updateWaypoints(ids) { it.copy(lon = v, moved = true) } }
                }
            }
            SectionTitle("Altitude")
            val individual = targets.all { it.heightOverride != null }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !individual, onClick = { vm.updateWaypoints(ids) { it.copy(heightOverride = null) } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Global (%.0f m)".format(p.settings.heightM)) }
                SegmentedButton(selected = individual, onClick = { vm.updateWaypoints(ids) { it.copy(heightOverride = it.heightOverride ?: p.settings.heightM) } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Individual") }
            }
            val h = w.heightOverride ?: p.settings.heightM
            LabeledSlider("Height", h, 2.0..200.0, { "%.0f m".format(it) }) { v -> vm.updateWaypoints(ids) { it.copy(heightOverride = v) } }
            NumberField("Height (exact)", h, Modifier.fillMaxWidth(), suffix = "m") { v -> vm.updateWaypoints(ids) { it.copy(heightOverride = v.coerceIn(2.0, 500.0)) } }

            if (advanced) {
            SectionTitle("Speed")
            SwitchRow("Speed override", w.speedOverride != null, "Auto: %.1f m/s".format(ui.stats.speedMs)) { on -> vm.updateWaypoints(ids) { it.copy(speedOverride = if (on) ui.stats.speedMs else null) } }
            w.speedOverride?.let { sp -> LabeledSlider("Speed to next waypoint", sp, 0.5..p.camera.maxSpeed, { "%.1f m/s".format(it) }) { v -> vm.updateWaypoints(ids) { it.copy(speedOverride = v) } } }

            SectionTitle("Camera / gimbal")
            SwitchRow("Gimbal override", w.gimbalPitch != null, "Global: %.0f°".format(p.settings.gimbalPitch)) { on -> vm.updateWaypoints(ids) { it.copy(gimbalPitch = if (on) p.settings.gimbalPitch else null) } }
            w.gimbalPitch?.let { g -> LabeledSlider("Gimbal pitch", g, -90.0..35.0, { "%.0f°".format(it) }) { v -> vm.updateWaypoints(ids) { it.copy(gimbalPitch = v) } } }

            }
            if (advanced) {
                Dropdown("Camera action", CameraAction.entries, w.action, { it.name.lowercase().replace('_', ' ') }) { a -> vm.updateWaypoints(ids) { it.copy(action = a) } }
                LabeledSlider("Zoom", w.zoom ?: 1.0, 1.0..4.0, { "%.1f×".format(it) }) { v -> vm.updateWaypoints(ids) { it.copy(zoom = if (v <= 1.01) null else v) } }
                LabeledSlider("Hover time", w.hoverSec, 0.0..30.0, { "%.0f s".format(it) }, steps = 29) { v -> vm.updateWaypoints(ids) { it.copy(hoverSec = v) } }
                SectionTitle("Heading & turn")
                Dropdown("Aircraft heading", HeadingMode.entries, w.headingMode, { when (it) { HeadingMode.FOLLOW_ROUTE -> "Follow route"; HeadingMode.FIXED -> "Fixed angle"; HeadingMode.MANUAL -> "Manual (RC)" } }) { m -> vm.updateWaypoints(ids) { it.copy(headingMode = m) } }
                if (w.headingMode == HeadingMode.FIXED) LabeledSlider("Heading", w.headingDeg, -180.0..180.0, { "%.0f°".format(it) }) { v -> vm.updateWaypoints(ids) { it.copy(headingDeg = v) } }
                Dropdown("Turn behaviour", listOf<TurnMode?>(null) + TurnMode.entries, w.turnMode, { it?.let(::turnLabel) ?: "Mission default" }) { t -> vm.updateWaypoints(ids) { it.copy(turnMode = t) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (!batch) AssistChip(onClick = { vm.insertAfter(w.id) }, label = { Text("Insert after") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                if (!batch) AssistChip(onClick = { vm.selectLine(w.id) }, label = { Text("Select line") }, leadingIcon = { Icon(Icons.Default.Timeline, null) })
                AssistChip(onClick = { vm.resetOverrides(ids) }, label = { Text("Reset overrides") }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) })
                AssistChip(onClick = { if (batch) vm.deleteSelected() else vm.deleteWaypoint(w.id); onDismiss() }, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
}

fun turnLabel(t: TurnMode) = when (t) { TurnMode.STOP_AT_POINT -> "Stop at point"; TurnMode.COORDINATED_TURN -> "Coordinated turn"; TurnMode.CURVED_PASS -> "Curved pass-through" }
