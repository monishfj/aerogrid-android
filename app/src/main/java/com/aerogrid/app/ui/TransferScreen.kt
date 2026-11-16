package com.aerogrid.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aerogrid.app.AeroGridApp
import com.aerogrid.app.engine.MissionSplitter
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.kmz.KmzValidator
import com.aerogrid.app.kmz.WpmlWriter
import com.aerogrid.app.model.ExportRecord
import com.aerogrid.app.model.Project
import com.aerogrid.app.transfer.DjiMission
import com.aerogrid.app.transfer.ExternalTransfer
import com.aerogrid.app.transfer.MissionFs
import com.aerogrid.app.transfer.SafFs
import com.aerogrid.app.transfer.ShizukuFs
import com.aerogrid.app.transfer.StepLog
import com.aerogrid.app.transfer.TransferEngine
import com.aerogrid.app.transfer.TransferStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StagedKmz(val name: String, val file: File, val sha: String, val waypoints: Int, val ok: Boolean, val errors: List<String>, val warnings: List<String>)

data class TransferUi(
    val project: Project? = null, val block: Int? = null, val staged: List<StagedKmz> = emptyList(),
    val djiInstalled: Boolean = false, val shizukuInstalled: Boolean = false, val shizukuRunning: Boolean = false, val shizukuGranted: Boolean = false,
    val safSupported: Boolean = SafFs.supported, val safGranted: Boolean = false, val method: String? = null,
    val root: String? = null, val missions: List<DjiMission> = emptyList(), val target: DjiMission? = null, val backups: List<String> = emptyList(),
    val log: List<StepLog> = emptyList(), val busy: Boolean = false, val closeDjiFirst: Boolean = true,
)

class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val aero = app as AeroGridApp
    val ui = MutableStateFlow(TransferUi())
    private var fs: MissionFs? = null
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init { runCatching { Shizuku.addRequestPermissionResultListener(permListener) } }
    override fun onCleared() { runCatching { Shizuku.removeRequestPermissionResultListener(permListener) } }

    private fun log(step: TransferStep, d: String = "") = ui.update { it.copy(log = it.log + StepLog(step, d)) }

    fun load(projectId: Long?) = viewModelScope.launch {
        val p = projectId?.let { aero.repo.load(it) }
        ui.update { it.copy(project = p, staged = emptyList(), log = emptyList(), block = null) }
        refresh()
        if (p != null && p.waypoints.size >= 2) prepare(null)
    }

    fun refresh() {
        val ctx = getApplication<Application>()
        val saf = aero.settings.state.value.safTreeUri
        ui.update { it.copy(djiInstalled = ExternalTransfer.djiFlyInstalled(ctx), shizukuInstalled = ShizukuFs.installed(ctx), shizukuRunning = ShizukuFs.running(),
            shizukuGranted = ShizukuFs.granted(), safGranted = saf != null && SafFs.supported && ctx.contentResolver.persistedUriPermissions.any { p -> p.uri.toString() == saf && p.isWritePermission }) }
    }

    fun requestShizuku() { if (!ShizukuFs.request(7001)) log(TransferStep.METHOD_UNAVAILABLE, "Shizuku is not running — start it via Wireless debugging in the Shizuku app") }

    fun setSafTree(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        aero.settings.update { it.copy(safTreeUri = uri.toString()) }
        refresh()
    }

    fun selectBlock(i: Int?) { ui.update { it.copy(block = i) }; prepare(i) }

    /** Generate + validate + stage KMZ(s) for the chosen block (null = all blocks / whole mission). */
    fun prepare(block: Int?) = viewModelScope.launch {
        val p = ui.value.project ?: return@launch
        ui.update { it.copy(busy = true, log = emptyList()) }
        val staged = withContext(Dispatchers.Default) {
            val dem = Terrain.load(p.demPath)
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "staging").apply { mkdirs() }
            val blocks = if (p.blocks.isEmpty()) listOf(null) else if (block != null) listOf(block) else p.blocks.indices.toList()
            blocks.map { bi ->
                val b = bi?.let { p.blocks[it] }
                val wps = b?.let { MissionSplitter.blockWaypoints(p, it) } ?: p.waypoints
                val name = (b?.name ?: MissionSplitter.blockName(p.name, 1)) + ".kmz"
                val out = WpmlWriter.build(p, wps, aero.settings.state.value.drone, dem)
                val v = KmzValidator.validate(out.bytes, p.settings.maxWaypointsPerMission, aero.settings.state.value.drone.namespace)
                val f = File(dir, name).apply { writeBytes(out.bytes) }
                StagedKmz(name, f, out.sha256, out.waypointCount, v.ok, v.errors, v.warnings)
            }
        }
        ui.update { it.copy(staged = staged, busy = false) }
        if (staged.isNotEmpty()) log(TransferStep.KMZ_GENERATED, staged.joinToString { "${it.name} (${it.waypoints} WP)" })
        if (staged.all { it.ok }) log(TransferStep.KMZ_VALIDATED, "Structure, indices, coordinates, heights and limits checked")
        else log(TransferStep.FAILED, staged.flatMap { it.errors }.first())
    }

    fun connect(useShizuku: Boolean) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val f: MissionFs? = if (useShizuku) ShizukuFs().takeIf { ShizukuFs.granted() } else aero.settings.state.value.safTreeUri?.let { SafFs(ctx, Uri.parse(it)) }
        if (f == null) { log(TransferStep.METHOD_UNAVAILABLE, if (useShizuku) "Shizuku permission not granted" else "No folder access granted"); return@launch }
        ui.update { it.copy(busy = true) }
        log(TransferStep.PERMISSION_GRANTED, f.name)
        val root = f.probe()
        if (root == null) { ui.update { it.copy(busy = false, root = null) }; log(TransferStep.METHOD_UNAVAILABLE, "DJI Fly waypoint folder not found or not writable. Open DJI Fly and save at least one waypoint mission first."); return@launch }
        fs = f
        val missions = f.list(root)
        val backups = runCatching { f.listBackups() }.getOrDefault(emptyList())
        ui.update { it.copy(busy = false, root = root, missions = missions, method = f.name, backups = backups) }
        log(TransferStep.FOLDER_FOUND, "$root — ${missions.size} mission(s)")
    }

    fun pickTarget(m: DjiMission) = ui.update { it.copy(target = m) }
    fun setCloseDji(v: Boolean) = ui.update { it.copy(closeDjiFirst = v) }

    fun transfer() = viewModelScope.launch {
        val u = ui.value; val f = fs ?: return@launch; val t = u.target ?: return@launch
        val k = u.staged.singleOrNull() ?: run { log(TransferStep.FAILED, "Choose a single mission block to place into one placeholder"); return@launch }
        if (!k.ok) { log(TransferStep.FAILED, "KMZ failed validation: ${k.errors.first()}"); return@launch }
        ui.update { it.copy(busy = true) }
        if (u.closeDjiFirst && f is ShizukuFs) f.sh("am force-stop ${TransferEngine.DJI_FLY_PACKAGE}")
        val r = TransferEngine(f).replace(t, k.file.absolutePath, k.sha)
        ui.update { it.copy(busy = false, log = it.log + r.log, backups = f.listBackups()) }
        u.project?.let { p ->
            val np = p.copy(transferState = r.log.last().step.label, exportHistory = p.exportHistory + ExportRecord(System.currentTimeMillis(), if (r.success) "DJI Fly transfer" else "DJI Fly transfer FAILED", "${k.name} → ${t.id}"))
            aero.repo.save(np); ui.update { it.copy(project = np) }
        }
    }

    fun restore(backup: String) = viewModelScope.launch {
        val f = fs ?: return@launch; val t = ui.value.target ?: run { log(TransferStep.FAILED, "Select the placeholder to restore into"); return@launch }
        ui.update { it.copy(busy = true) }
        val ok = TransferEngine(f).restore(backup, t)
        ui.update { it.copy(busy = false) }
        log(if (ok) TransferStep.RESTORED else TransferStep.FAILED, if (ok) "Restored $backup" else "Restore failed")
    }

    fun exportDownloads(share: Boolean): Intent? {
        val ctx = getApplication<Application>()
        val st = ui.value.staged.filter { it.ok }
        if (st.isEmpty()) return null
        val saved = st.map { ExternalTransfer.saveToDownloads(ctx, it.name, it.file.readBytes(), "application/vnd.google-earth.kmz") }
        log(TransferStep.FILE_COPIED, "Saved to ${saved.joinToString()}")
        ui.value.project?.let { p -> viewModelScope.launch { aero.repo.save(p.copy(exportHistory = p.exportHistory + saved.map { ExportRecord(System.currentTimeMillis(), "DJI KMZ", it) })) } }
        return if (share) Intent.createChooser(ExternalTransfer.shareIntent(ctx, st.map { it.file }, "application/vnd.google-earth.kmz"), "Send mission") else null
    }

    fun openDji(): Boolean = ExternalTransfer.openDjiFly(getApplication())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(vm: TransferViewModel, projectId: Long?, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(projectId) { vm.load(projectId) }
    DisposableEffect(Unit) { onDispose { } }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let { vm.setSafTree(it) } }
    val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    Scaffold(topBar = { TopAppBar(title = { Text("DJI Fly Transfer Manager") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val p = ui.project
            if (p == null) Text("Open a mission from the library first, then choose Transfer to DJI Fly.")
            else {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("1 · Mission KMZ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${p.name} · ${p.waypoints.size} waypoints · ${p.blocks.size.coerceAtLeast(1)} block(s)", style = MaterialTheme.typography.bodySmall)
                        if (p.blocks.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = ui.block == null, onClick = { vm.selectBlock(null) }, label = { Text("All blocks") })
                            p.blocks.forEachIndexed { i, b -> FilterChip(selected = ui.block == i, onClick = { vm.selectBlock(i) }, label = { Text(b.name.substringAfterLast("_Block_").let { "Block $it" }) }) }
                        }
                        ui.staged.forEach { s ->
                            Text("${if (s.ok) "✓" else "✗"} ${s.name} — ${s.waypoints} WP — SHA-256 ${s.sha.take(12)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = if (s.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            (s.errors + s.warnings).forEach { Text("  · $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Compatibility: generated in DJI WPML layout with drone ID from Settings. It has not been verified on your DJI Fly version — always inspect the mission in DJI Fly before flight.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9100))
                    }
                }
            }
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("2 · Direct transfer (Method A — Shizuku, no root)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusLine("DJI Fly installed", ui.djiInstalled); StatusLine("Shizuku installed", ui.shizukuInstalled); StatusLine("Shizuku running", ui.shizukuRunning); StatusLine("AeroGrid authorised in Shizuku", ui.shizukuGranted)
                    Text("Android 11+ blocks apps from DJI Fly's folder. Shizuku (free, on Google Play/GitHub) is started through Developer options › Wireless debugging and lets you explicitly authorise AeroGrid to use ADB-shell file access. Nothing is bypassed and no root is used.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!ui.shizukuInstalled) OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) } }) { Text("Get Shizuku") }
                        if (ui.shizukuRunning && !ui.shizukuGranted) Button(onClick = vm::requestShizuku) { Text("Authorise AeroGrid") }
                        OutlinedButton(onClick = vm::refresh) { Text("Re-check") }
                        Button(onClick = { vm.connect(true) }, enabled = ui.shizukuGranted) { Text("Find DJI Fly missions") }
                    }
                    if (ui.safSupported) {
                        Text("Method B — folder access (this Android version allows granting DJI Fly's data folder):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { safPicker.launch(SafFs.openTreeIntent()) }) { Text(if (ui.safGranted) "Change folder" else "Grant folder access") }
                            Button(onClick = { vm.connect(false) }, enabled = ui.safGranted) { Text("Use folder access") }
                        }
                    } else Text("Document-provider access to Android/data is not available on Android 13+ (this device), so Method B is unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (ui.root != null) Card {
                Column(Modifier.padding(14.dp)) {
                    Text("3 · Choose the placeholder to replace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("In DJI Fly create a temporary 2-waypoint mission and save it. It appears here as the newest entry. Only the selected file is replaced; a verified backup is made first.", style = MaterialTheme.typography.bodySmall)
                    if (ui.missions.isEmpty()) Text("No saved DJI Fly missions found.", color = MaterialTheme.colorScheme.error)
                    ui.missions.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { vm.pickTarget(m) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = ui.target == m, onClick = { vm.pickTarget(m) })
                            Column { Text(m.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); Text("${fmt.format(Date(m.modifiedSec * 1000))} · ${m.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    SwitchRow("Close DJI Fly before replacing", ui.closeDjiFirst, "Prevents DJI Fly overwriting the file while open") { vm.setCloseDji(it) }
                    Button(onClick = vm::transfer, enabled = ui.target != null && ui.staged.size == 1 && ui.staged.first().ok && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ui.staged.size > 1) "Select one block above to transfer" else "Back up & replace placeholder")
                    }
                    if (ui.backups.isNotEmpty()) {
                        SectionTitle("Backups (restore into selected placeholder)")
                        ui.backups.take(6).forEach { b -> Row(verticalAlignment = Alignment.CenterVertically) { Text(b.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = { vm.restore(b) }, enabled = ui.target != null) { Text("Restore") } } }
                    }
                }
            }
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Method C — export / external transfer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.exportDownloads(false) }, enabled = ui.staged.any { it.ok }) { Text("Save to Downloads") }
                        FilledTonalButton(onClick = { vm.exportDownloads(true)?.let { ctx.startActivity(it) } }, enabled = ui.staged.any { it.ok }) { Text("Share") }
                    }
                    Text(ExternalTransfer.WINDOWS_GUIDE, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Transfer status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (ui.log.isEmpty()) Text("No actions yet", style = MaterialTheme.typography.bodySmall)
                    ui.log.forEach { l ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(if (l.step == TransferStep.FAILED || l.step == TransferStep.METHOD_UNAVAILABLE) Icons.Default.Error else Icons.Default.CheckCircle, null,
                                tint = if (l.step == TransferStep.FAILED || l.step == TransferStep.METHOD_UNAVAILABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 6.dp))
                            Column { Text(l.step.label, fontWeight = FontWeight.SemiBold); if (l.detail.isNotEmpty()) Text(l.detail, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (ui.log.any { it.step == TransferStep.READY }) Button(onClick = { vm.openDji() }, modifier = Modifier.padding(top = 8.dp)) { Text("Open DJI Fly to review") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
    Text("  $label", style = MaterialTheme.typography.bodyMedium)
}
