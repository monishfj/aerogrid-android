package com.aerogrid.app.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aerogrid.app.engine.Severity
import com.aerogrid.app.engine.formatArea
import com.aerogrid.app.engine.formatDistance
import com.aerogrid.app.engine.formatDuration
import com.aerogrid.app.model.PhotoResolution
import com.aerogrid.app.ui.map.MapController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(vm: PlannerViewModel, projectId: Long?, initialMode: Mode, settingsStore: com.aerogrid.app.data.SettingsStore, onBack: () -> Unit, onTransfer: (Long) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val controller = remember { MapController(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(projectId) { vm.open(projectId, initialMode) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> controller.onStart(); Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause(); Lifecycle.Event.ON_STOP -> { controller.onStop(); vm.pauseSimulation() }; else -> {}
            }
        }
        lifecycle.addObserver(obs)
        controller.callbacks = vm
        onDispose { lifecycle.removeObserver(obs); controller.callbacks = null; controller.onStop(); controller.onDestroy() }
    }
    LaunchedEffect(settings.mapStyle, settings.streetStyleUrl, settings.satelliteTilesUrl) { controller.setStyle(settings) }
    val scene = remember(ui, settings.showFootprints) { vm.scene(ui, settings.showFootprints) }
    LaunchedEffect(scene) { controller.render(scene) }
    var firstTilt by remember { mutableStateOf(true) }
    LaunchedEffect(ui.threeD) { if (!firstTilt || ui.threeD) controller.setThreeD(ui.threeD, ui.project.waypoints.map { it.point } + ui.project.boundaries.flatten() + ui.project.corridor); firstTilt = false }
    val camReq by vm.camera.collectAsStateWithLifecycle()
    LaunchedEffect(camReq?.seq) {
        camReq?.let { c -> if (c.zoom != null) controller.flyTo(c.points.first(), c.zoom) else controller.fit(c.points) }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> scope.launch { snack.showSnackbar(e.text) }
                is UiEvent.Fly -> controller.flyTo(e.point, e.zoom)
                is UiEvent.Fit -> controller.fit(e.points)
                is UiEvent.Launch -> runCatching { ctx.startActivity(e.intent) }
            }
        }
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) vm.locate() else scope.launch { snack.showSnackbar("Location permission denied") } }
    var importAsWaypoints by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importFile(it, importAsWaypoints) } }
    val demPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importDem(it) } }
    val doImport = { wps: Boolean -> importAsWaypoints = wps; importer.launch(arrayOf("*/*")) }
    val doImage = { pdf: Boolean ->
        val pts = ui.project.waypoints.map { it.point } + ui.project.boundaries.flatten()
        controller.fit(pts, animate = false)
        scope.launch { delay(1800); controller.snapshot { vm.exportImage(it, pdf) } }
        Unit
    }
    val doTransfer = { vm.save { id -> onTransfer(id) }; Unit }
    val doOffline = { controller.downloadOffline(ctx, settings.streetStyleUrl) { vm.offlineMessage(it) } }

    var showQuickSettings by remember { mutableStateOf(false) }
    var batchEdit by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    BackHandler {
        when {
            ui.sim != null -> vm.stopSimulation()
            ui.tool != Tool.NONE -> vm.cancelDraft()
            else -> { vm.close(); onBack() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize())

        // ---------- top overlay ----------
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftIconTile(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = { vm.close(); onBack() }, size = 46.dp)
                SoftSegmented(Mode.entries, ui.mode, { it.label }, { vm.setMode(it) }, Modifier.weight(1f).padding(horizontal = 10.dp))
                OverflowMenu(ui, onRename = { showRename = true }, onSave = { vm.save() }, onExport = { showExport = true }, onImport = doImport,
                    onReverse = vm::reverseRoute, onReset = vm::resetRoute, onStyle = { s -> settingsStore.update { it.copy(mapStyle = s) } },
                    onFootprints = { settingsStore.update { it.copy(showFootprints = !it.showFootprints) } }, footprints = settings.showFootprints, onOffline = doOffline)
            }
            SearchBox(ui, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (ui.mode == Mode.QUICK && ui.project.hasAdvancedConfig) SoftChip("Advanced settings applied", { vm.setMode(Mode.ADVANCED) }, leading = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) })
                ui.measureText?.let { SoftChip(it, {}) }
                if (ui.busy) LinearProgressIndicator(Modifier.width(80.dp).padding(top = 18.dp))
            }
        }

        // ---------- right-side map controls ----------
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 136.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var styleMenu by remember { mutableStateOf(false) }
            Box {
                SoftIconTile(Icons.Default.Layers, "Map style", onClick = { styleMenu = true })
                DropdownMenu(styleMenu, { styleMenu = false }) {
                    listOf("Street map", "Satellite", "Hybrid").forEachIndexed { i, l -> DropdownMenuItem(text = { Text(l + if (settings.mapStyle == i) "  ✓" else "") }, onClick = { styleMenu = false; settingsStore.update { it.copy(mapStyle = i) } }) }
                }
            }
            SoftIconTile(null, if (ui.threeD) "Switch to 2D" else "Switch to 3D", onClick = { vm.toggle3D() }, filled = ui.threeD, text = if (ui.threeD) "2D" else "3D")
            SoftIconTile(Icons.AutoMirrored.Filled.Undo, "Undo", onClick = { vm.undo() }, enabled = ui.canUndo)
            SoftIconTile(Icons.AutoMirrored.Filled.Redo, "Redo", onClick = { vm.redo() }, enabled = ui.canRedo)
            SoftIconTile(Icons.Default.MyLocation, "My location", onClick = { locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, selected = ui.myLocation != null)
        }

        // ---------- bottom panels ----------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (ui.mode != Mode.MANUAL) DrawingBar(ui, vm)
            SnackbarHost(snack)
            when {
                ui.sim != null -> SimulationPanel(ui, vm)
                ui.mode == Mode.QUICK -> QuickDock(ui, vm, onSettings = { showQuickSettings = true }, onTransfer = doTransfer, onBatch = { batchEdit = true })
                ui.mode == Mode.MANUAL -> ManualDock(ui, vm, onTransfer = doTransfer, onBatch = { batchEdit = true })
                else -> AdvancedPanel(ui, vm, settings, onBatch = { batchEdit = true }, onImport = doImport, onImage = doImage, onTransfer = doTransfer,
                    onDem = { demPicker.launch(arrayOf("*/*")) }, onOffline = doOffline, onFocus = { i -> ui.project.waypoints.getOrNull(i)?.let { controller.flyTo(it.point, 18.0) } })
            }
        }
    }

    if (ui.editingId != null && !batchEdit) WaypointSheet(vm, ui, batch = false) { vm.openEditor(null) }
    if (batchEdit) WaypointSheet(vm, ui, batch = true) { batchEdit = false }
    if (showQuickSettings) QuickSettingsSheet(ui, vm) { showQuickSettings = false }
    if (showRename) RenameDialog(ui.project.name, ui.project.location, onDismiss = { showRename = false }) { n, l -> vm.commit(ui.project.copy(name = n.ifBlank { "Untitled mission" }, location = l)); showRename = false }
    if (showExport) ModalBottomSheet(onDismissRequest = { showExport = false }) {
        Box(Modifier.heightIn(max = 560.dp)) { ExportTab(vm, ui, settings, doImport, { showExport = false; doImage(it) }, doTransfer, doOffline) }
    }
}

@Composable
private fun OverflowMenu(ui: PlannerUi, onRename: () -> Unit, onSave: () -> Unit, onExport: () -> Unit, onImport: (Boolean) -> Unit, onReverse: () -> Unit, onReset: () -> Unit,
                         onStyle: (Int) -> Unit, onFootprints: () -> Unit, footprints: Boolean, onOffline: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SoftIconTile(Icons.Default.MoreVert, "More", onClick = { open = true }, size = 46.dp)
        DropdownMenu(open, { open = false }) {
            val item = @Composable { t: String, f: () -> Unit -> DropdownMenuItem(text = { Text(t) }, onClick = { open = false; f() }) }
            item("Save mission", onSave); item("Rename / location…", onRename); item("Export / share…", onExport)
            item("Import boundary file…") { onImport(false) }; item("Import waypoints / DJI mission…") { onImport(true) }
            item("Reverse route", onReverse); item("Reset route to generated", onReset)
            item(if (footprints) "Hide photo footprints" else "Show photo footprints", onFootprints)
            item("Download offline street map", onOffline)
        }
    }
}

@Composable
private fun SearchBox(ui: PlannerUi, vm: PlannerViewModel) {
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp)) {
        if (!open) {
            val soft = com.aerogrid.app.ui.theme.LocalSoft.current
            Box(Modifier.neu(24.dp, 6.dp, soft.card).clip(RoundedCornerShape(24.dp)).background(soft.card).clickable { open = true }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientIcon(Icons.Default.Search, null, Modifier.size(20.dp)); HSpace(8)
                    Text(ui.project.name + if (ui.project.location.isNotBlank()) " · ${ui.project.location}" else " · search location", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        } else {
            OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Search e.g. Tavua, Fiji") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { open = false; vm.clearSearch() }) { Icon(Icons.Default.Close, "Close") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
                shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer))
            if (ui.searchResults.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ui.searchResults.forEach { r -> DropdownMenuItem(text = { Text(r.name, maxLines = 2) }, onClick = { vm.pickSearch(r); open = false }) }
            }
        }
    }
}

@Composable
private fun DrawingBar(ui: PlannerUi, vm: PlannerViewModel) {
    val drawing = ui.tool in setOf(Tool.DRAW_BOUNDARY, Tool.DRAW_EXCLUSION, Tool.DRAW_CORRIDOR, Tool.MEASURE_DISTANCE, Tool.MEASURE_AREA, Tool.FREEHAND)
    val editing = ui.tool in setOf(Tool.EDIT_SHAPES, Tool.EDIT_WAYPOINTS, Tool.ADD_WAYPOINT, Tool.SET_TAKEOFF, Tool.SET_LANDING)
    if (!drawing && !editing) return
    Card(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(10.dp)) {
            val hint = when (ui.tool) {
                Tool.DRAW_BOUNDARY -> "Tap the map to add vertices · tap the first vertex or Finish to close"
                Tool.FREEHAND -> "Draw the boundary with one finger — it closes automatically"
                Tool.DRAW_EXCLUSION -> "Tap to outline a no-fly exclusion zone"
                Tool.DRAW_CORRIDOR -> "Tap along the corridor centre line"
                Tool.MEASURE_DISTANCE, Tool.MEASURE_AREA -> "Tap points to measure"
                Tool.EDIT_SHAPES -> "Drag vertices · drag/tap midpoints to insert · long-press a vertex to delete"
                Tool.EDIT_WAYPOINTS -> "Tap to select · drag to move (moves all selected) · long-press map to insert"
                Tool.ADD_WAYPOINT -> "Tap the map to append waypoints"
                Tool.SET_TAKEOFF -> "Tap the takeoff location"
                Tool.SET_LANDING -> "Tap the landing location"
                else -> ""
            }
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (drawing) {
                    if (ui.tool !in setOf(Tool.MEASURE_AREA, Tool.MEASURE_DISTANCE, Tool.FREEHAND)) Button(onClick = vm::finishDraft) { Icon(Icons.Default.Check, null); Text("Finish") }
                    Text("${ui.draft.size} pts", style = MaterialTheme.typography.labelLarge)
                    if (ui.tool == Tool.DRAW_BOUNDARY || ui.tool == Tool.FREEHAND) {
                        FilterChip(selected = ui.tool == Tool.DRAW_BOUNDARY, onClick = { vm.setTool(Tool.DRAW_BOUNDARY) }, label = { Text("Tap") })
                        FilterChip(selected = ui.tool == Tool.FREEHAND, onClick = { vm.setTool(Tool.FREEHAND) }, label = { Text("Freehand") })
                    }
                    TextButton(onClick = vm::undoDraftPoint, enabled = ui.draft.isNotEmpty()) { Text("Undo point") }
                    TextButton(onClick = vm::cancelDraft) { Text("Cancel") }
                } else {
                    Button(onClick = { vm.setTool(Tool.NONE) }) { Text("Done") }
                    if (ui.tool == Tool.EDIT_WAYPOINTS) {
                        FilterChip(selected = ui.multiSelect, onClick = vm::toggleMultiSelect, label = { Text("Multi-select") })
                        TextButton(onClick = vm::selectAll) { Text("All") }
                        TextButton(onClick = vm::deleteSelected, enabled = ui.selected.isNotEmpty()) { Text("Delete (${ui.selected.size})") }
                        TextButton(onClick = { vm.setTool(Tool.ADD_WAYPOINT) }) { Text("Add") }
                        TextButton(onClick = vm::reverseRoute) { Text("Reverse") }
                        TextButton(onClick = vm::resetRoute) { Text("Reset route") }
                    }
                    if (ui.tool == Tool.EDIT_SHAPES) TextButton(onClick = vm::clearBoundary) { Text("Clear boundary") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickPanel(ui: PlannerUi, vm: PlannerViewModel, onSettings: () -> Unit, onTransfer: () -> Unit, onBatch: () -> Unit) {
    val st = ui.stats; val p = ui.project
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    StatCell("area", formatArea(st.areaM2)); StatCell("waypoints", "${st.waypointCount}"); StatCell("time", formatDuration(st.durationSec))
                    StatCell("photos", "${st.photoCount}"); StatCell("GSD", "%.2f cm".format(st.gsdCm)); StatCell("missions", "${st.missionCount}", warn = st.missionCount > 1)
                    StatCell("distance", formatDistance(st.distanceM))
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Fewer height options" else "More height options") }
            }
            val errs = ui.issues.count { it.severity == Severity.ERROR }; val warns = ui.issues.count { it.severity == Severity.WARNING }
            if (p.waypoints.isNotEmpty() && (errs + warns) > 0) Text(ui.issues.first { it.severity != Severity.INFO }.message, style = MaterialTheme.typography.bodySmall,
                color = if (errs > 0) MaterialTheme.colorScheme.error else Color(0xFFFF9100), maxLines = 2)
            HeightControl(ui, vm, onBatch, expanded)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                var drawMenu by remember { mutableStateOf(false) }
                Box {
                    FilledTonalButton(onClick = { if (p.boundaries.isEmpty()) vm.setTool(Tool.DRAW_BOUNDARY) else drawMenu = true }) { Icon(Icons.Default.Draw, null); HSpace(4); Text(if (p.boundaries.isEmpty()) "Draw Boundary" else "Boundary") }
                    DropdownMenu(drawMenu, { drawMenu = false }) {
                        DropdownMenuItem(text = { Text("Redraw (tap)") }, onClick = { drawMenu = false; vm.setTool(Tool.DRAW_BOUNDARY) })
                        DropdownMenuItem(text = { Text("Redraw (freehand)") }, onClick = { drawMenu = false; vm.setTool(Tool.FREEHAND) })
                        DropdownMenuItem(text = { Text("Edit vertices") }, onClick = { drawMenu = false; vm.setTool(Tool.EDIT_SHAPES) })
                        DropdownMenuItem(text = { Text("Clear boundary") }, onClick = { drawMenu = false; vm.clearBoundary() })
                    }
                }
                Button(onClick = { vm.generate() }, enabled = p.boundaries.isNotEmpty() && !ui.busy) { Icon(Icons.Default.GridOn, null); HSpace(4); Text("Generate Waypoints") }
                FilledTonalButton(onClick = { vm.setTool(if (ui.tool == Tool.EDIT_WAYPOINTS) Tool.NONE else Tool.EDIT_WAYPOINTS) }, enabled = p.waypoints.isNotEmpty()) { Icon(Icons.Default.Edit, null); HSpace(4); Text("Edit Waypoints") }
                FilledTonalButton(onClick = vm::startSimulation, enabled = p.waypoints.size >= 2) { Icon(Icons.Default.PlayArrow, null); HSpace(4); Text("Preview Flight") }
                Button(onClick = onTransfer, enabled = p.waypoints.size >= 2) { Icon(Icons.Default.SendToMobile, null); HSpace(4); Text("Transfer to DJI Fly") }
                FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Quick settings") }
            }
        }
    }
}

/** Compact one-line slider used in the Quick card to keep the map visible. */
@Composable
private fun CompactSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, text: (Double) -> String, color: Color = Color.Unspecified, onChange: (Double) -> Unit) {
    var live by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(92.dp))
        androidx.compose.material3.Slider(value = live, onValueChange = { live = it }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            onValueChangeFinished = { onChange(kotlin.math.round(live.toDouble())) }, modifier = Modifier.weight(1f))
        Text(text(live.toDouble()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.width(78.dp).padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun HeightControl(ui: PlannerUi, vm: PlannerViewModel, onBatch: () -> Unit, expanded: Boolean) {
    val p = ui.project; val st = ui.stats
    Column {
        CompactSlider("Flight height", p.settings.heightM, 10.0..120.0, { "%.0f m".format(it) }) { vm.setGlobalHeight(it) }
        val showSpacing = st.footprintW > 0 && p.settings.pattern != com.aerogrid.app.model.FlightPattern.MANUAL && (p.boundaries.isNotEmpty() || p.corridor.isNotEmpty())
        if (showSpacing) {
            val fw = st.footprintW
            val q = com.aerogrid.app.engine.OverlapAdvice.side(st.sideOverlap)
            val c = when (q.level) { 0 -> Color(0xFF2E7D32); 1 -> Color(0xFFB8860B); 2 -> Color(0xFFFF6D00); else -> MaterialTheme.colorScheme.error }
            CompactSlider("Line spacing", st.lineSpacing, 2.0..maxOf(150.0, kotlin.math.ceil(fw)), { "%.0f m".format(it) }, c) { v -> vm.updateSettings { it.copy(lineSpacingM = v.coerceAtLeast(2.0)) } }
            val recommended = fw * (1 - p.settings.sideOverlap.coerceAtLeast(70.0) / 100)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● " + q.text, style = MaterialTheme.typography.bodySmall, color = c, modifier = Modifier.weight(1f), maxLines = 2)
                if (q.level > 0) TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = kotlin.math.round(recommended).coerceAtLeast(2.0)) } }) { Text("Use %.0f m".format(recommended)) }
                else if (p.settings.lineSpacingM != null) TextButton(onClick = { vm.updateSettings { it.copy(lineSpacingM = null) } }) { Text("Auto") }
            }
        }
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberField("Exact height", p.settings.heightM, Modifier.width(140.dp), decimals = 0, suffix = "m") { vm.setGlobalHeight(it) }
                HSpace()
                Text("%s · %d individual height(s) kept".format(if (p.settings.altitudeMode.name == "TERRAIN_AGL") "AGL target" else "above takeoff", st.overriddenHeights), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.applyHeightToSelected(p.settings.heightM) }, enabled = ui.selected.isNotEmpty()) { Text("Apply to Selected (${ui.selected.size})") }
                TextButton(onClick = { vm.overwriteAllHeights(p.settings.heightM) }) { Text("Overwrite All Heights") }
                TextButton(onClick = vm::resetAllHeights, enabled = st.overriddenHeights > 0) { Text("Reset All Heights") }
                TextButton(onClick = vm::restoreDefaultHeight) { Text("Restore Default (50 m)") }
                if (ui.selected.size > 1) TextButton(onClick = onBatch) { Text("Edit selected…") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSettingsSheet(ui: PlannerUi, vm: PlannerViewModel, onDismiss: () -> Unit) {
    val s = ui.project.settings; val cam = ui.project.camera
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Quick settings", style = MaterialTheme.typography.titleLarge)
            LabeledSlider("Flight height", s.heightM, 10.0..120.0, { "%.0f m".format(it) }) { vm.setGlobalHeight(it) }
            SwitchRow("Automatic speed", s.speedMs == null, "Calculated for overlap: %.1f m/s".format(ui.stats.requiredSpeed)) { a -> vm.updateSettings(false) { it.copy(speedMs = if (a) null else ui.stats.speedMs) } }
            s.speedMs?.let { v -> LabeledSlider("Flight speed", v, 0.5..cam.maxSpeed, { "%.1f m/s".format(it) }) { x -> vm.updateSettings(false) { it.copy(speedMs = x) } } }
            LabeledSlider("Forward overlap", s.frontOverlap, 50.0..95.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings { it.copy(frontOverlap = v) } }
            LabeledSlider("Side overlap", s.sideOverlap, 40.0..95.0, { "%.0f%%".format(it) }) { v -> vm.updateSettings { it.copy(sideOverlap = v, lineSpacingM = null) } }
            LineSpacingControl(ui, vm)
            SwitchRow("Automatic flight direction", s.bearingDeg == null) { a -> vm.updateSettings { it.copy(bearingDeg = if (a) null else ui.project.gridBearingDeg ?: 0.0) } }
            s.bearingDeg?.let { b -> LabeledSlider("Flight direction", b, 0.0..179.0, { "%.0f°".format(it) }) { v -> vm.updateSettings { it.copy(bearingDeg = v) } } }
            LabeledSlider("Camera angle", s.gimbalPitch, -90.0..0.0, { "%.0f°".format(it) }) { v -> vm.updateSettings(false) { it.copy(gimbalPitch = v) } }
            Dropdown("Photo interval", cam.intervals(s.resolution), s.intervalSec, { "$it s" }) { v -> vm.updateSettings(false) { it.copy(intervalSec = v) } }
            Dropdown("Camera resolution", PhotoResolution.entries, s.resolution, { it.label }) { v -> vm.updateSettings(false) { it.copy(resolution = v, intervalSec = cam.intervals(v).minBy { i -> kotlin.math.abs(i - it.intervalSec) }) } }
            Text("Timed capture: before starting the mission set DJI Fly › Photo › Timed Shot ${s.intervalSec} s at ${s.resolution.label} and start it at WP001.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SimulationPanel(ui: PlannerUi, vm: PlannerViewModel) {
    val sim = ui.sim ?: return; val st = sim.state
    DockSurface {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFFF6D00), shape = RoundedCornerShape(6.dp)) { Text(" SIMULATION — not an actual flight ", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                Box(Modifier.weight(1f))
                IconButton(onClick = vm::stopSimulation) { Icon(Icons.Default.Close, "Close preview") }
            }
            if (st != null) {
                SoftProgress((st.elapsed / st.total.coerceAtLeast(1.0)).toFloat(), Modifier.padding(vertical = 10.dp))
                Row {
                    StatCell("waypoint", "WP%03d".format(st.wpIndex + 1), Modifier.weight(1f)); StatCell("heading", "%.0f°".format(st.headingDeg), Modifier.weight(1f))
                    StatCell("height", "%.0f m".format(st.heightM), Modifier.weight(1f)); StatCell("photos", "${st.photos.size}", Modifier.weight(1f))
                }
                Row {
                    StatCell("elapsed", formatDuration(st.elapsed), Modifier.weight(1f)); StatCell("remaining", formatDuration(st.total - st.elapsed), Modifier.weight(1f))
                    StatCell("camera", "%.0f° gimbal".format(ui.project.settings.gimbalPitch), Modifier.weight(2f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftIconTile(if (sim.running) Icons.Default.Pause else Icons.Default.PlayArrow, if (sim.running) "Pause" else "Resume", onClick = { if (sim.running) vm.pauseSimulation() else vm.resumeSimulation() }, filled = true, size = 50.dp)
                HSpace(12)
                SoftIconTile(Icons.Default.Replay, "Restart", onClick = vm::restartSimulation, size = 50.dp)
                HSpace(14)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 10f, 30f, 60f).forEach { x -> SoftChip("${x.toInt()}×", { vm.setSimSpeed(x) }, selected = sim.speed == x) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPanel(ui: PlannerUi, vm: PlannerViewModel, settings: com.aerogrid.app.data.AppSettings, onBatch: () -> Unit, onImport: (Boolean) -> Unit,
                          onImage: (Boolean) -> Unit, onTransfer: () -> Unit, onDem: () -> Unit, onOffline: () -> Unit, onFocus: (Int) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val tabs = listOf("Mapping", "Waypoints", "Analysis", "Terrain", "Blocks", "Export")
    DockSurface {
        Column {
            Box {
                StatStrip(listOf("area" to com.aerogrid.app.engine.formatArea(ui.stats.areaM2), "waypoints" to "${ui.stats.waypointCount}", "lines" to "${ui.stats.lineCount}",
                    "time" to formatDuration(ui.stats.durationSec), "GSD" to "%.2f cm".format(ui.stats.gsdCm), "overlap" to "%.0f/%.0f%%".format(ui.stats.frontOverlap, ui.stats.sideOverlap), "missions" to "${ui.stats.missionCount}"), ui)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Tool.NONE, Tool.DRAW_BOUNDARY, Tool.FREEHAND, Tool.EDIT_SHAPES, Tool.DRAW_EXCLUSION, Tool.DRAW_CORRIDOR, Tool.EDIT_WAYPOINTS, Tool.ADD_WAYPOINT,
                    Tool.SET_TAKEOFF, Tool.SET_LANDING, Tool.MEASURE_DISTANCE, Tool.MEASURE_AREA).forEach { t ->
                    SoftChip(t.label, { vm.setTool(if (ui.tool == t) Tool.NONE else t) }, selected = ui.tool == t)
                }
                SoftChip("Optimise start", vm::optimiseRoute, leading = { Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp)) })
                SoftChip("Preview", vm::startSimulation, leading = { Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icons = listOf(Icons.Default.Tune, Icons.Default.Edit, Icons.Default.Explore, Icons.Default.Layers, Icons.Default.GridOn, Icons.Default.SendToMobile)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tabs.forEachIndexed { i, t ->
                        SoftIconTile(icons[i], t, onClick = { if (tab == i) expanded = !expanded else { tab = i; expanded = true } }, size = 46.dp, filled = tab == i && expanded)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, "Toggle panel") }
            }
            AnimatedVisibility(expanded) {
                Text(tabs[tab], style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                Box(Modifier.height(330.dp).padding(top = 30.dp)) {
                    when (tab) {
                        0 -> MappingTab(vm, ui)
                        1 -> WaypointsTab(vm, ui, onBatch, onFocus)
                        2 -> AnalysisTab(ui)
                        3 -> TerrainTab(vm, ui, onDem)
                        4 -> BlocksTab(vm, ui)
                        else -> ExportTab(vm, ui, settings, onImport, onImage, onTransfer, onOffline)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(name: String, location: String, onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    // Old name is pre-selected so typing replaces it.
    var n by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    var l by remember { mutableStateOf(location) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = { onOk(n.text.trim().ifBlank { name }, l.trim()) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Mission details") }, text = {
            Column { OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.focusRequester(focus)); OutlinedTextField(l, { l = it }, label = { Text("Location") }, singleLine = true) }
        })
}
