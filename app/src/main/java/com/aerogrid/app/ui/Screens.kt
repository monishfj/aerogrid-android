package com.aerogrid.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerogrid.app.AeroGridApp
import com.aerogrid.app.data.ProjectRepository
import com.aerogrid.app.engine.formatArea
import com.aerogrid.app.io.GeoImport
import com.aerogrid.app.io.withImported
import com.aerogrid.app.kmz.WpmlReader
import com.aerogrid.app.model.CameraProfile
import com.aerogrid.app.model.FlightSettings
import com.aerogrid.app.model.Project
import com.aerogrid.app.transfer.ExternalTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(app: AeroGridApp, onPlan: (Long?, Mode) -> Unit, onLibrary: () -> Unit, onTransfer: () -> Unit, onSettings: () -> Unit) {
    val projects by app.repo.summaries.collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    Scaffold(containerColor = soft.bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AeroGrid", style = MaterialTheme.typography.displaySmall, modifier = Modifier.gradientTint(soft.gradient))
                    Text("Drone mapping missions for DJI Fly", style = MaterialTheme.typography.bodyMedium, color = soft.muted)
                }
                SoftIconTile(Icons.Default.Settings, "Settings", onClick = onSettings, size = 50.dp)
            }
            // Hero: start a Quick plan
            SoftCard(Modifier.fillMaxWidth(), radius = 32.dp, padding = 22.dp) {
                Text("Plan a mapping flight", style = MaterialTheme.typography.titleLarge)
                Text("Draw an area, get an optimised grid, send it to DJI Fly.", style = MaterialTheme.typography.bodyMedium, color = soft.muted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                GradientButton("Start Quick plan", { onPlan(null, Mode.QUICK) }, Modifier.fillMaxWidth(), icon = Icons.Default.GridOn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ModeCard("Manual", "Plot your own waypoints", Icons.Default.Place, Modifier.weight(1f)) { onPlan(null, Mode.MANUAL) }
                ModeCard("Advanced", "Full mission control", Icons.Default.Tune, Modifier.weight(1f)) { onPlan(null, Mode.ADVANCED) }
            }
            SoftPillButton("Mission Library", Icons.Default.Folder, onLibrary, Modifier.fillMaxWidth(), accent = true)
            SoftPillButton("DJI Fly Transfer", Icons.Default.SendToMobile, onTransfer, Modifier.fillMaxWidth())
            if (!ExternalTransfer.djiFlyInstalled(ctx)) Text("DJI Fly isn't installed on this device — KMZ export and PC transfer still work.", style = MaterialTheme.typography.bodySmall, color = soft.muted)
            if (projects.isNotEmpty()) Text("Recent missions", style = MaterialTheme.typography.titleMedium)
            projects.take(6).forEach { s -> ProjectRow(s, onOpen = { onPlan(s.id, Mode.QUICK) }) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeCard(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    SoftCard(modifier.height(150.dp).clickable(onClick = onClick), radius = 28.dp, padding = 18.dp) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (primary) soft.gradientV else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(soft.bg, soft.bg))),
            contentAlignment = Alignment.Center) { if (primary) Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White) else GradientIcon(icon, null) }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = soft.muted, maxLines = 2)
    }
}

@Composable
private fun ProjectRow(s: ProjectRepository.Summary, onOpen: () -> Unit, menu: (@Composable () -> Unit)? = null) {
    val f = remember { SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()) }
    val soft = com.aerogrid.app.ui.theme.LocalSoft.current
    SoftCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), radius = 24.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(soft.bg), contentAlignment = Alignment.Center) { GradientIcon(Icons.Default.GridOn, null) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(listOfNotNull(s.location.ifBlank { null }, formatArea(s.areaM2), "${s.waypoints} WP").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = soft.muted, maxLines = 1)
                Text(f.format(Date(s.modified)), style = MaterialTheme.typography.labelSmall, color = soft.muted)
            }
            menu?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: AeroGridApp, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val all by app.repo.summaries.collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<ProjectRepository.Summary?>(null) }
    var deleting by remember { mutableStateOf<ProjectRepository.Summary?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val name = uri.lastPathSegment ?: "file"
                val n = when (val kind = GeoImport.detect(name, bytes)) {
                    "aerogrid" -> app.repo.restore(bytes)
                    "dji" -> { val m = WpmlReader.read(bytes); app.repo.save(Project(name = "Imported DJI mission", waypoints = m.waypoints, nextId = m.waypoints.size + 1L,
                        settings = app.settings.state.value.defaultSettings.copy(pattern = com.aerogrid.app.model.FlightPattern.MANUAL, altitudeMode = com.aerogrid.app.model.AltitudeMode.RELATIVE_TAKEOFF))); 1 }
                    else -> { val g = GeoImport.parse(name, bytes); app.repo.save(Project(name = g.name ?: "Imported $kind", settings = app.settings.state.value.defaultSettings).withImported(g, false)); 1 }
                }
                snack.showSnackbar("Imported $n project(s)")
            } catch (e: Exception) { snack.showSnackbar("Import failed: ${e.message}") }
        }
    }
    val list = all.filter { q.isBlank() || it.name.contains(q, true) || it.location.contains(q, true) }.let { l ->
        when (sort) { 1 -> l.sortedBy { it.name.lowercase() }; 2 -> l.sortedByDescending { it.created }; 3 -> l.sortedByDescending { it.areaM2 }; else -> l.sortedByDescending { it.modified } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Mission Library") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search missions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf("Modified", "Name", "Created", "Area").forEachIndexed { i, l -> FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(l) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }) { Text("Import / restore") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val bytes = app.repo.backupAll()
                        val name = "AeroGrid_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
                        val path = withContext(Dispatchers.IO) { ExternalTransfer.saveToDownloads(ctx, name, bytes, "application/zip") }
                        snack.showSnackbar("Backup saved: $path")
                    }
                }) { Text("Back up all") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(list, key = { it.id }) { s ->
                    ProjectRow(s, onOpen = { onOpen(s.id) }) {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; renaming = s })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { open = false; scope.launch { app.repo.duplicate(s.id) } })
                            DropdownMenuItem(text = { Text("Export project file") }, onClick = {
                                open = false
                                scope.launch { app.repo.load(s.id)?.let { p -> val path = withContext(Dispatchers.IO) { ExternalTransfer.saveToDownloads(ctx, p.name.replace(Regex("[^A-Za-z0-9_-]+"), "_") + ".aerogrid", com.aerogrid.app.io.BackupCodec.projectJson(p).toByteArray(), "application/json") }; snack.showSnackbar("Saved $path") } }
                            })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; deleting = s })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { s ->
        var n by remember(s) { mutableStateOf(s.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename mission") }, text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.rename(s.id, n) }; renaming = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${s.name}\"?") }, text = { Text("This permanently removes the mission from this device. Export a backup first if you may need it.") },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.delete(s.id) }; deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AeroGridApp, onBack: () -> Unit) {
    val s by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val m = WpmlReader.read(bytes)
                app.settings.update { it.copy(drone = m.drone.copy(source = "Reference mission from DJI Fly (${m.waypoints.size} WP)")) }
                snack.showSnackbar("Reference applied: drone ${m.drone.droneEnumValue}/${m.drone.droneSubEnumValue}, namespace ${m.drone.namespace}")
            } catch (e: Exception) { snack.showSnackbar("Not a DJI waypoint KMZ: ${e.message}") }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle("Appearance")
            Dropdown("Theme", listOf(0, 1, 2), s.theme, { listOf("System", "Light", "Dark")[it] }) { v -> app.settings.update { it.copy(theme = v) } }
            SwitchRow("Use wallpaper colours (Material You)", s.materialYou, "Replaces the AeroGrid soft blue palette · Android 12+") { v -> app.settings.update { it.copy(materialYou = v) } }
            SectionTitle("DJI Fly mission format")
            Text("Drone enum ${s.drone.droneEnumValue} / sub ${s.drone.droneSubEnumValue}\nNamespace ${s.drone.namespace}\nSource: ${s.drone.source}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refPicker.launch(arrayOf("*/*")) }) { Text("Import DJI Fly reference KMZ") }
                OutlinedButton(onClick = { app.settings.update { it.copy(drone = com.aerogrid.app.model.DroneInfo()) } }) { Text("Reset") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Drone enum", s.drone.droneEnumValue.toDouble(), Modifier.weight(1f), 0) { v -> app.settings.update { it.copy(drone = it.drone.copy(droneEnumValue = v.toInt(), source = "Manually entered")) } }
                NumberField("Sub enum", s.drone.droneSubEnumValue.toDouble(), Modifier.weight(1f), 0) { v -> app.settings.update { it.copy(drone = it.drone.copy(droneSubEnumValue = v.toInt(), source = "Manually entered")) } }
            }
            Text("Tip: save any mission in DJI Fly with your Mini 5 Pro connected, copy its KMZ (Transfer Manager › Shizuku, or via PC) and import it here so AeroGrid copies the exact identifiers.", style = MaterialTheme.typography.bodySmall)
            SectionTitle("Default mission settings (new projects)")
            val d = s.defaultSettings
            NumberField("Default height", d.heightM, Modifier.fillMaxWidth(), suffix = "m") { v -> app.settings.update { it.copy(defaultSettings = it.defaultSettings.copy(heightM = v)) } }
            LabeledSlider("Forward overlap", d.frontOverlap, 50.0..95.0, { "%.0f%%".format(it) }) { v -> app.settings.update { it.copy(defaultSettings = it.defaultSettings.copy(frontOverlap = v)) } }
            LabeledSlider("Side overlap", d.sideOverlap, 40.0..95.0, { "%.0f%%".format(it) }) { v -> app.settings.update { it.copy(defaultSettings = it.defaultSettings.copy(sideOverlap = v)) } }
            NumberField("Max waypoints per mission", d.maxWaypointsPerMission.toDouble(), Modifier.fillMaxWidth(), 0) { v -> app.settings.update { it.copy(defaultSettings = it.defaultSettings.copy(maxWaypointsPerMission = v.toInt().coerceIn(2, 200))) } }
            NumberField("Battery flight time", d.batteryMinutes, Modifier.fillMaxWidth(), 0, "min") { v -> app.settings.update { it.copy(defaultSettings = it.defaultSettings.copy(batteryMinutes = v)) } }
            TextButton(onClick = { app.settings.update { it.copy(defaultSettings = FlightSettings()) } }) { Text("Restore DJI Mini 5 Pro mapping defaults") }
            SectionTitle("Camera profile")
            CameraEditor(s.camera) { c -> app.settings.update { it.copy(camera = c) } }
            SectionTitle("Map sources")
            Text("Street map: OpenFreeMap (OpenStreetMap data, no key). Satellite: Esri World Imagery tiles with attribution — review Esri's terms for your use, or set your own licensed XYZ provider (e.g. MapTiler with your key).", style = MaterialTheme.typography.bodySmall)
            UrlField("Street style URL", s.streetStyleUrl) { v -> app.settings.update { it.copy(streetStyleUrl = v) } }
            UrlField("Satellite XYZ tiles", s.satelliteTilesUrl) { v -> app.settings.update { it.copy(satelliteTilesUrl = v) } }
            UrlField("Satellite attribution", s.satelliteAttribution) { v -> app.settings.update { it.copy(satelliteAttribution = v) } }
            UrlField("Hybrid labels XYZ tiles", s.labelsTilesUrl) { v -> app.settings.update { it.copy(labelsTilesUrl = v) } }
            SectionTitle("About")
            Text("AeroGrid 1.0 · Map data © OpenStreetMap contributors. Routes are not guaranteed collision-free; the remote pilot is responsible for airspace rules (CAAF Fiji), obstacles, terrain and DJI Fly settings.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun UrlField(label: String, value: String, onValue: (String) -> Unit) {
    var t by remember(value) { mutableStateOf(value) }
    OutlinedTextField(t, { t = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        trailingIcon = { if (t != value) TextButton(onClick = { onValue(t.trim()) }) { Text("Set") } })
}

@Suppress("unused") private val keepCamera = CameraProfile.MINI_5_PRO
