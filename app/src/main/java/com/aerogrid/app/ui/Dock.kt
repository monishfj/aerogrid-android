package com.aerogrid.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerogrid.app.engine.OverlapAdvice
import com.aerogrid.app.engine.Severity
import com.aerogrid.app.engine.formatArea
import com.aerogrid.app.engine.formatDistance
import com.aerogrid.app.engine.formatDuration
import com.aerogrid.app.model.CaptureStrategy
import com.aerogrid.app.model.FlightPattern
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// ───────────────────────── shared dock building blocks ─────────────────────────

private val GOOD = Color(0xFF2E7D32); private val OK = Color(0xFFB8860B); private val RISK = Color(0xFFFF6D00)
fun qualityColor(level: Int, err: Color) = when (level) { 0 -> GOOD; 1 -> OK; 2 -> RISK; else -> err }

@Composable
fun DockSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).neu(32.dp, 10.dp, soft.card)
        .clip(RoundedCornerShape(32.dp)).background(soft.card).animateContentSize().padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
}

/** Glass strip of key numbers with a tappable issues badge. */
@Composable
fun StatStrip(items: List<Pair<String, String>>, ui: PlannerUi, warnFirstOf: Set<String> = emptySet()) {
    var showIssues by remember { mutableStateOf(false) }
    val errs = ui.issues.count { it.severity == Severity.ERROR }; val warns = ui.issues.count { it.severity == Severity.WARNING }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, (label, value) ->
                if (i > 0) Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column {
                    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, color = if (label in warnFirstOf) RISK else MaterialTheme.colorScheme.onSurface)
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        if (ui.project.waypoints.isNotEmpty() && errs + warns > 0) {
            val c = if (errs > 0) MaterialTheme.colorScheme.error else RISK
            Surface(onClick = { showIssues = true }, shape = RoundedCornerShape(50), color = c.copy(alpha = 0.15f), border = BorderStroke(1.dp, c)) {
                Text(if (errs > 0) "$errs ✕" else "$warns !", color = c, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
    if (showIssues) AlertDialog(onDismissRequest = { showIssues = false }, confirmButton = { TextButton(onClick = { showIssues = false }) { Text("Close") } },
        title = { Text("Mission check") }, text = { Column(Modifier.horizontalScroll(rememberScrollState(), enabled = false)) { IssuesList(ui) } })
}

enum class StepState { DONE, NEXT, TODO, ACTIVE, DISABLED }
data class Step(val label: String, val icon: ImageVector, val state: StepState, val onClick: () -> Unit)

/**
 * Timeline stepper (kit 2019–2022 timeline): a gradient progress line through raised white knobs,
 * with label pills below; the recommended next / active step gets a gradient pill.
 */
@Composable
fun StepRail(steps: List<Step>) {
    val haptic = LocalHapticFeedback.current
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    val reach = steps.indexOfLast { it.state == StepState.DONE || it.state == StepState.NEXT || it.state == StepState.ACTIVE }
    Row(Modifier.fillMaxWidth().drawBehind {
        val n = steps.size; if (n < 2) return@drawBehind
        val y = 17.dp.toPx(); val c = { i: Int -> size.width * (i + 0.5f) / n }
        drawLine(soft.track, Offset(c(0), y), Offset(c(n - 1), y), 5.dp.toPx(), StrokeCap.Round)
        if (reach > 0) drawLine(soft.gradient, Offset(c(0), y), Offset(c(reach), y), 5.dp.toPx(), StrokeCap.Round)
    }) {
        steps.forEach { s ->
            val enabled = s.state != StepState.DISABLED
            val hot = s.state == StepState.NEXT || s.state == StepState.ACTIVE
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); s.onClick() }
                .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(30.dp).shadow(6.dp, CircleShape, ambientColor = soft.gradEnd, spotColor = soft.gradEnd).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center) {
                    when {
                        s.state == StepState.DONE -> Box(Modifier.size(18.dp).clip(CircleShape).background(soft.gradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, s.label, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(12.dp)) }
                        hot -> Box(Modifier.size(14.dp).clip(CircleShape).background(soft.gradient))
                        else -> Icon(s.icon, s.label, tint = if (enabled) soft.muted else soft.muted.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.wrapContentWidth(unbounded = true).then(if (hot) Modifier.shadow(8.dp, RoundedCornerShape(50), ambientColor = soft.gradEnd, spotColor = soft.gradEnd) else Modifier)
                    .clip(RoundedCornerShape(50)).background(if (hot) soft.gradient else Brush.horizontalGradient(listOf(soft.card, soft.card)))
                    .padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(s.label, fontSize = 11.sp, fontWeight = if (hot) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false,
                        color = if (hot) androidx.compose.ui.graphics.Color.White else if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else soft.muted.copy(alpha = 0.5f))
                }
            }
        }
    }
}

data class Param(
    val key: String, val label: String, val value: Double, val range: ClosedFloatingPointRange<Double>, val step: Double,
    val format: (Double) -> String, val color: Color? = null, val onCommit: (Double) -> Unit,
)

/** Row of parameter chips; one opens at a time into a ruler scrubber. */
@Composable
fun ParamBar(params: List<Param>, open: String?, onOpen: (String?) -> Unit, trailing: @Composable () -> Unit = {}, extra: @Composable (String) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, start = 2.dp, end = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        params.forEach { p ->
            val sel = p.key == open
            val soft = com.aerogrid.app.ui.theme.LocalSoft.current
            val fg = if (sel) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
            Column(Modifier.padding(vertical = 6.dp)
                .then(if (sel) Modifier.shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = soft.gradEnd, spotColor = soft.gradEnd) else Modifier.neu(18.dp, 5.dp, soft.card))
                .clip(RoundedCornerShape(18.dp)).background(if (sel) soft.gradient else Brush.horizontalGradient(listOf(soft.card, soft.card)))
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(if (sel) null else p.key) }
                .padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(p.label, fontSize = 10.sp, color = if (sel) fg.copy(alpha = 0.85f) else soft.muted, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.color != null) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (sel) androidx.compose.ui.graphics.Color.White else p.color)); Spacer(Modifier.width(4.dp)) }
                    Text(p.format(p.value), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = fg)
                }
            }
        }
        trailing()
    }
    val cur = params.firstOrNull { it.key == open }
    AnimatedVisibility(cur != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        if (cur != null) Column(Modifier.padding(top = 6.dp)) {
            RulerScrubber(cur)
            extra(cur.key)
        }
    }
}

/** Drag-to-scrub ruler with tick marks, haptic ticks and ± buttons. Commits when the finger lifts. */
@Composable
fun RulerScrubber(p: Param) {
    val haptic = LocalHapticFeedback.current
    var v by remember(p.key, p.value) { mutableDoubleStateOf(p.value) }
    val stepPx = with(LocalDensity.current) { 12.dp.toPx() }
    val tm = rememberTextMeasurer()
    val accent = p.color ?: MaterialTheme.colorScheme.primary
    val rulerGrad = com.aerogrid.app.ui.theme.LocalSoft.current.gradientV
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    fun snap(x: Double) = (floor(x / p.step + 0.5) * p.step).coerceIn(p.range)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { v = snap(v - p.step); p.onCommit(v) }) { Icon(Icons.Default.Remove, "Decrease ${p.label}") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(p.format(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = accent)
            Canvas(Modifier.fillMaxWidth().height(40.dp).pointerInput(p.key) {
                detectHorizontalDragGestures(onDragEnd = { p.onCommit(snap(v)) }) { change, dx ->
                    change.consume()
                    val before = snap(v)
                    v = (v - dx / stepPx * p.step).coerceIn(p.range)
                    if (snap(v) != before) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }) {
                val cx = size.width / 2
                val first = floor((v - cx / stepPx * p.step) / p.step).toInt(); val last = (first + size.width / stepPx).toInt() + 2
                for (k in first..last) {
                    val tv = k * p.step
                    if (tv < p.range.start - 1e-9 || tv > p.range.endInclusive + 1e-9) continue
                    val x = (cx + (tv - v) / p.step * stepPx).toFloat()
                    val major = k % 5 == 0
                    val fade = (1f - abs(x - cx) / cx).coerceIn(0.15f, 1f)
                    drawLine(tick.copy(alpha = fade), Offset(x, if (major) 4f else 14f), Offset(x, size.height * 0.6f), if (major) 3f else 1.5f)
                    if (major && k % 10 == 0) {
                        val t = tm.measure(p.format(tv).substringBefore(' '), labelStyle)
                        drawText(t, topLeft = Offset(x - t.size.width / 2, size.height * 0.62f), alpha = fade)
                    }
                }
                drawLine(if (p.color != null) Brush.verticalGradient(listOf(accent, accent)) else rulerGrad, Offset(cx, 0f), Offset(cx, size.height * 0.62f), 7f, StrokeCap.Round)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)), size = size)
            }
        }
        IconButton(onClick = { v = snap(v + p.step); p.onCommit(v) }) { Icon(Icons.Default.Add, "Increase ${p.label}") }
    }
}

// ───────────────────────── Quick dock ─────────────────────────

@Composable
fun QuickDock(ui: PlannerUi, vm: PlannerViewModel, onSettings: () -> Unit, onTransfer: () -> Unit, onBatch: () -> Unit) {
    val p = ui.project; val s = p.settings; val st = ui.stats
    var open by remember { mutableStateOf<String?>(null) }
    var boundaryMenu by remember { mutableStateOf(false) }
    DockSurface {
        StatStrip(listOf("area" to formatArea(st.areaM2), "waypoints" to "${st.waypointCount}", "time" to formatDuration(st.durationSec),
            "photos" to "${st.photoCount}", "GSD" to "%.2f cm".format(st.gsdCm), "missions" to "${st.missionCount}"), ui, if (st.missionCount > 1) setOf("missions") else emptySet())
        Spacer(Modifier.height(8.dp))
        val hasB = p.boundaries.isNotEmpty(); val hasW = p.waypoints.size >= 2
        fun state(done: Boolean, enabled: Boolean, isNext: Boolean, active: Boolean = false) = when {
            active -> StepState.ACTIVE; !enabled -> StepState.DISABLED; isNext -> StepState.NEXT; done -> StepState.DONE; else -> StepState.TODO }
        Box {
            StepRail(listOf(
                Step("Boundary", Icons.Default.Draw, state(hasB, true, !hasB, ui.tool in setOf(Tool.DRAW_BOUNDARY, Tool.FREEHAND, Tool.EDIT_SHAPES))) {
                    if (!hasB) vm.setTool(Tool.DRAW_BOUNDARY) else boundaryMenu = true },
                Step("Generate", Icons.Default.GridOn, state(hasW, hasB && !ui.busy, hasB && !hasW)) { vm.generate() },
                Step("Edit", Icons.Default.Edit, state(false, hasW, false, ui.tool == Tool.EDIT_WAYPOINTS)) { vm.setTool(if (ui.tool == Tool.EDIT_WAYPOINTS) Tool.NONE else Tool.EDIT_WAYPOINTS) },
                Step("Preview", Icons.Default.PlayArrow, state(false, hasW, false)) { vm.startSimulation() },
                Step("Transfer", Icons.Default.SendToMobile, state(false, hasW, hasW)) { onTransfer() },
            ))
            DropdownMenu(boundaryMenu, { boundaryMenu = false }) {
                DropdownMenuItem(text = { Text("Edit vertices") }, onClick = { boundaryMenu = false; vm.setTool(Tool.EDIT_SHAPES) })
                DropdownMenuItem(text = { Text("Redraw (tap)") }, onClick = { boundaryMenu = false; vm.setTool(Tool.DRAW_BOUNDARY) })
                DropdownMenuItem(text = { Text("Redraw (freehand)") }, onClick = { boundaryMenu = false; vm.setTool(Tool.FREEHAND) })
                DropdownMenuItem(text = { Text("Clear boundary") }, onClick = { boundaryMenu = false; vm.clearBoundary() })
            }
        }
        val fw = st.footprintW
        val spacingQ = OverlapAdvice.side(st.sideOverlap)
        val err = MaterialTheme.colorScheme.error
        val params = buildList {
            add(Param("h", "Height", s.heightM, 10.0..120.0, 1.0, { "%.0f m".format(it) }) { vm.setGlobalHeight(it) })
            if (fw > 0 && s.pattern != FlightPattern.MANUAL && (hasB || p.corridor.isNotEmpty()))
                add(Param("sp", "Line spacing", st.lineSpacing, 2.0..maxOf(150.0, kotlin.math.ceil(fw)), 1.0, { "%.0f m".format(it) }, qualityColor(spacingQ.level, err)) { v -> vm.updateSettings { it.copy(lineSpacingM = v) } })
            add(Param("fo", "Front overlap", s.frontOverlap, 50.0..95.0, 1.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings { it.copy(frontOverlap = v) } })
            add(Param("v", if (s.speedMs == null) "Speed · auto" else "Speed", st.speedMs, 0.5..p.camera.maxSpeed, 0.5, { "%.1f m/s".format(it) },
                if (st.frontOverlap < s.frontOverlap - 1 && s.capture == CaptureStrategy.TIMED_INTERVAL) RISK else null) { v -> vm.updateSettings(false) { it.copy(speedMs = v) } })
            add(Param("g", "Camera", s.gimbalPitch, -90.0..0.0, 5.0, { "%.0f°".format(it) }) { v -> vm.updateSettings(false) { it.copy(gimbalPitch = v) } })
        }
        ParamBar(params, open, { open = it }, trailing = {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, "More quick settings") }
        }) { key ->
            when (key) {
                "h" -> Column {
                    Text("Waypoints without an individual height follow this. ${st.overriddenHeights} individual height(s) kept." +
                        if (s.altitudeMode.name == "TERRAIN_AGL") " Target is above ground." else " Relative to takeoff.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { vm.applyHeightToSelected(s.heightM) }, enabled = ui.selected.isNotEmpty()) { Text("Apply to selected (${ui.selected.size})") }
                        TextButton(onClick = { vm.overwriteAllHeights(s.heightM) }) { Text("Overwrite all") }
                        TextButton(onClick = vm::resetAllHeights, enabled = st.overriddenHeights > 0) { Text("Reset individual") }
                        TextButton(onClick = vm::restoreDefaultHeight) { Text("Default 50 m") }
                        if (ui.selected.size > 1) TextButton(onClick = onBatch) { Text("Edit selected…") }
                    }
                }
                "sp" -> {
                    val rec = fw * (1 - s.sideOverlap.coerceAtLeast(70.0) / 100)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● " + spacingQ.text, color = qualityColor(spacingQ.level, err), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (spacingQ.level > 0) TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = kotlin.math.round(rec)) } }) { Text("Use %.0f m".format(rec)) }
                        else if (s.lineSpacingM != null) TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = null) } }) { Text("Auto") }
                    }
                }
                "fo" -> Text("Photo spacing %.1f m along each line".format(st.photoSpacing), style = MaterialTheme.typography.bodySmall)
                "v" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.capture == CaptureStrategy.TIMED_INTERVAL) "%.1f m/s keeps %.0f%% forward overlap at %d s photos — now %.0f%%".format(st.requiredSpeed, s.frontOverlap, s.intervalSec, st.frontOverlap) else "Photo at each waypoint",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (s.speedMs != null) TextButton(onClick = { vm.updateSettings(false) { it.copy(speedMs = null) } }) { Text("Auto") }
                }
                "g" -> Text("−90° points straight down for mapping", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ───────────────────────── Manual dock ─────────────────────────

@Composable
fun ManualDock(ui: PlannerUi, vm: PlannerViewModel, onTransfer: () -> Unit, onBatch: () -> Unit) {
    val p = ui.project; val s = p.settings; val st = ui.stats
    var open by remember { mutableStateOf<String?>(null) }
    var finishMenu by remember { mutableStateOf(false) }
    val ready = p.waypoints.size >= 2
    DockSurface {
        StatStrip(listOf("waypoints" to "${st.waypointCount}", "distance" to formatDistance(st.distanceM), "time" to formatDuration(st.durationSec),
            "height" to "%.0f m".format(s.heightM), "speed" to "%.1f m/s".format(st.speedMs)), ui)
        Spacer(Modifier.height(8.dp))
        StepRail(listOf(
            Step("Plot", Icons.Default.TouchApp, if (ui.tool == Tool.ADD_WAYPOINT) StepState.ACTIVE else if (!ready) StepState.NEXT else StepState.DONE) { vm.setTool(if (ui.tool == Tool.ADD_WAYPOINT) Tool.NONE else Tool.ADD_WAYPOINT) },
            Step("Move", Icons.Default.Edit, if (ui.tool == Tool.EDIT_WAYPOINTS) StepState.ACTIVE else if (p.waypoints.isEmpty()) StepState.DISABLED else StepState.TODO) { vm.setTool(if (ui.tool == Tool.EDIT_WAYPOINTS) Tool.NONE else Tool.EDIT_WAYPOINTS) },
            Step("Takeoff", Icons.Default.FlightTakeoff, if (ui.tool == Tool.SET_TAKEOFF) StepState.ACTIVE else if (p.takeoff != null) StepState.DONE else StepState.TODO) { vm.setTool(Tool.SET_TAKEOFF) },
            Step("Preview", Icons.Default.PlayArrow, if (ready) StepState.TODO else StepState.DISABLED) { vm.startSimulation() },
            Step("Save", Icons.Default.Save, if (ready) StepState.TODO else StepState.DISABLED) { vm.exportKmz(null, share = false) },
            Step("Transfer", Icons.Default.SendToMobile, if (ready && ui.tool == Tool.NONE) StepState.NEXT else if (ready) StepState.TODO else StepState.DISABLED) { onTransfer() },
        ))
        Text(when (ui.tool) {
            Tool.ADD_WAYPOINT -> "Tap the map to drop waypoints in flight order"
            Tool.EDIT_WAYPOINTS -> "Drag to move · long-press map to insert · tap to edit"
            Tool.SET_TAKEOFF -> "Tap your takeoff spot"
            else -> if (p.waypoints.isEmpty()) "Tap Plot, then tap the map" else "Tap a waypoint card to edit it"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        // Waypoint carousel
        if (p.waypoints.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            IconButton(onClick = vm::removeLastWaypoint) { Icon(Icons.AutoMirrored.Filled.Undo, "Remove last waypoint") }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(p.waypoints, key = { _, w -> w.id }) { i, w ->
                    val sel = w.id in ui.selected
                    val dot = when { i == 0 -> Color(0xFF00C853); i == p.waypoints.lastIndex -> Color(0xFFFF1744); w.hasOverrides -> Color(0xFFFF9100); else -> Color(0xFF1E88E5) }
                    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
                    Column(Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                        .then(if (sel) Modifier.shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = soft.gradEnd, spotColor = soft.gradEnd) else Modifier.neu(16.dp, 4.dp, soft.card))
                        .clip(RoundedCornerShape(16.dp)).background(if (sel) soft.gradient else Brush.horizontalGradient(listOf(soft.card, soft.card)))
                        .clickable { vm.openEditor(w.id) }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(4.dp)); Text("${i + 1}", fontWeight = FontWeight.Black) }
                        Text("%.0f m".format(p.heightOf(w)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = vm::reverseRoute, enabled = ready) { Icon(Icons.Default.SwapVert, "Reverse route") }
            IconButton(onClick = vm::clearWaypoints) { Icon(Icons.Default.Close, "Clear all waypoints") }
        }
        val params = listOf(
            Param("h", "Height", s.heightM, 5.0..120.0, 1.0, { "%.0f m".format(it) }) { vm.setGlobalHeight(it) },
            Param("v", "Speed", s.speedMs ?: st.speedMs, 1.0..p.camera.maxSpeed, 0.5, { "%.1f m/s".format(it) }) { v -> vm.updateSettings(false) { it.copy(speedMs = v) } },
            Param("g", "Gimbal", s.gimbalPitch, -90.0..0.0, 5.0, { "%.0f°".format(it) }) { v -> vm.updateSettings(false) { it.copy(gimbalPitch = v) } },
        )
        ParamBar(params, open, { open = it }, trailing = {
            val photo = s.capture == CaptureStrategy.WAYPOINT_PHOTO
            OptionPill("Photo at WPs", if (photo) "On" else "Off", selected = photo) { vm.setPhotoAtEachWaypoint(!photo) }
            Box {
                OptionPill("When finished", s.finishAction.label) { finishMenu = true }
                DropdownMenu(finishMenu, { finishMenu = false }) {
                    com.aerogrid.app.model.FinishAction.entries.forEach { f -> DropdownMenuItem(text = { Text(f.label) }, onClick = { finishMenu = false; vm.updateSettings(false) { it.copy(finishAction = f) } }) }
                }
            }
            IconButton(onClick = { vm.exportKmz(null, share = true) }, enabled = ready) { Icon(Icons.Default.Share, "Share DJI KMZ") }
            if (ui.selected.size > 1) TextButton(onClick = onBatch) { Text("Edit ${ui.selected.size}") }
        })
    }
}

@Suppress("unused") private fun pct(x: Double) = (x * 100).roundToInt()


/** Two-line option pill matching the parameter chips (label + value); gradient when [selected]. */
@Composable
fun OptionPill(label: String, value: String, selected: Boolean = false, onClick: () -> Unit) {
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(vertical = 6.dp)
        .then(if (selected) Modifier.shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = soft.gradEnd, spotColor = soft.gradEnd) else Modifier.neu(18.dp, 5.dp, soft.card))
        .clip(RoundedCornerShape(18.dp)).background(if (selected) soft.gradient else Brush.horizontalGradient(listOf(soft.card, soft.card)))
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, fontSize = 10.sp, color = if (selected) fg.copy(alpha = 0.85f) else soft.muted, maxLines = 1)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = fg)
    }
}
