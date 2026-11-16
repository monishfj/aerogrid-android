package com.aerogrid.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerogrid.app.AeroGridApp
import com.aerogrid.app.engine.ElevationSource
import com.aerogrid.app.engine.GeoMath
import com.aerogrid.app.engine.Issue
import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.engine.MissionSplitter
import com.aerogrid.app.engine.MissionStats
import com.aerogrid.app.engine.Simulation
import com.aerogrid.app.engine.Stats
import com.aerogrid.app.engine.Terrain
import com.aerogrid.app.engine.Validator
import com.aerogrid.app.engine.formatArea
import com.aerogrid.app.io.BackupCodec
import com.aerogrid.app.io.GeoExport
import com.aerogrid.app.io.GeoImport
import com.aerogrid.app.io.Reports
import com.aerogrid.app.io.withImported
import com.aerogrid.app.kmz.KmzValidator
import com.aerogrid.app.kmz.WpmlReader
import com.aerogrid.app.kmz.WpmlWriter
import com.aerogrid.app.model.ExportRecord
import com.aerogrid.app.model.FlightPattern
import com.aerogrid.app.model.FlightSettings
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import com.aerogrid.app.model.Waypoint
import com.aerogrid.app.transfer.ExternalTransfer
import com.aerogrid.app.ui.map.Hit
import com.aerogrid.app.ui.map.MapCallbacks
import com.aerogrid.app.ui.map.MapScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Mode(val label: String) { QUICK("Quick"), MANUAL("Manual"), ADVANCED("Advanced") }
enum class Tool(val label: String) {
    NONE("Pan"), DRAW_BOUNDARY("Draw boundary"), FREEHAND("Freehand"), EDIT_SHAPES("Edit shapes"), DRAW_EXCLUSION("Exclusion zone"),
    DRAW_CORRIDOR("Corridor line"), EDIT_WAYPOINTS("Edit waypoints"), ADD_WAYPOINT("Add waypoints"), SET_TAKEOFF("Set takeoff"),
    SET_LANDING("Set landing"), MEASURE_DISTANCE("Measure distance"), MEASURE_AREA("Measure area"),
}

data class SimUi(val running: Boolean, val speed: Float, val state: Simulation.State?)
data class SearchResult(val name: String, val point: GeoPoint)

data class PlannerUi(
    val project: Project = Project(),
    val mode: Mode = Mode.QUICK,
    val tool: Tool = Tool.NONE,
    val draft: List<GeoPoint> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val multiSelect: Boolean = false,
    val editingId: Long? = null,
    val stats: MissionStats = MissionStats(),
    val issues: List<Issue> = emptyList(),
    val busy: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val sim: SimUi? = null,
    val demInfo: String? = null,
    val profile: List<Terrain.ProfileSample> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val myLocation: GeoPoint? = null,
    val highlightBlock: Int? = null,
    val threeD: Boolean = false,
    val savedAt: Long = 0,
    val loaded: Boolean = false,
) {
    val selectedWaypoints get() = project.waypoints.filter { it.id in selected }
    val measureText: String? get() = when {
        tool == Tool.MEASURE_DISTANCE && draft.size >= 2 -> "Distance: %.1f m".format(GeoMath.pathLength(draft))
        tool == Tool.MEASURE_AREA && draft.size >= 3 -> "Area: " + formatArea(GeoMath.area(draft))
        tool in setOf(Tool.DRAW_BOUNDARY, Tool.DRAW_EXCLUSION) && draft.size >= 3 -> "Area: " + formatArea(GeoMath.area(draft))
        else -> null
    }
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Fly(val point: GeoPoint, val zoom: Double = 15.5) : UiEvent
    data class Fit(val points: List<GeoPoint>) : UiEvent
    data class Launch(val intent: Intent) : UiEvent
}

class PlannerViewModel(app: Application) : AndroidViewModel(app), MapCallbacks {
    private val aero = app as AeroGridApp
    private val repo = aero.repo
    val settings = aero.settings.state
    private val _ui = MutableStateFlow(PlannerUi())
    val ui: StateFlow<PlannerUi> = _ui
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    data class CameraReq(val seq: Long, val points: List<GeoPoint>, val zoom: Double?)
    private val _camera = MutableStateFlow<CameraReq?>(null)
    val camera: StateFlow<CameraReq?> = _camera
    private var camSeq = 0L
    private fun emitCamera(e: UiEvent) {
        when (e) {
            is UiEvent.Fly -> _camera.value = CameraReq(++camSeq, listOf(e.point), e.zoom)
            is UiEvent.Fit -> _camera.value = CameraReq(++camSeq, e.points, null)
            else -> _events.tryEmit(e)
        }
    }

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var dragBase: Project? = null
    private var dragAnchor: GeoPoint? = null
    private var statsJob: Job? = null
    private var saveJob: Job? = null
    private var regenJob: Job? = null
    private var simJob: Job? = null
    private var simulation: Simulation? = null
    private var dem: ElevationSource? = null
    private var demPathLoaded: String? = null

    private fun msg(t: String) { _events.tryEmit(UiEvent.Message(t)) }
    private val p get() = _ui.value.project

    // ---------------- project lifecycle ----------------
    fun open(id: Long?, requestedMode: Mode) {
        viewModelScope.launch {
            // Persist unsaved edits of whatever was open, then always read fresh from the database so
            // changes made elsewhere (rename/duplicate/delete in the Library) are never overwritten.
            if (dirty) saveNow()
            saveJob?.cancel()
            val stored = id?.let { repo.load(it) }
            val mode = if (stored?.settings?.pattern == FlightPattern.MANUAL && requestedMode == Mode.QUICK) Mode.MANUAL else requestedMode
            val proj = stored ?: Project(settings = settings.value.defaultSettings, camera = settings.value.camera).let {
                if (mode == Mode.MANUAL) it.copy(name = "Manual route", settings = it.settings.copy(pattern = FlightPattern.MANUAL, speedMs = 5.0, altitudeMode = com.aerogrid.app.model.AltitudeMode.RELATIVE_TAKEOFF)) else it
            }
            undo.clear(); redo.clear()
            _ui.value = PlannerUi(project = proj, mode = mode, loaded = true, tool = if (mode == Mode.MANUAL && proj.waypoints.isEmpty()) Tool.ADD_WAYPOINT else Tool.NONE)
            loadDem(proj.demPath)
            recompute()
            val pts = proj.boundaries.flatten() + proj.waypoints.map { it.point } + proj.corridor
            if (pts.isNotEmpty()) emitCamera(UiEvent.Fit(pts)) else emitCamera(UiEvent.Fly(TAVUA, 15.0))
        }
    }

    fun setMode(m: Mode) { _ui.update { it.copy(mode = m, tool = when { m == Mode.QUICK && it.tool !in QUICK_TOOLS -> Tool.NONE; m == Mode.MANUAL && it.tool !in MANUAL_TOOLS -> Tool.NONE; else -> it.tool }) } }

    // ---------------- manual route ----------------
    fun clearWaypoints() { commit(p.copy(waypoints = emptyList(), blocks = emptyList())); _ui.update { it.copy(selected = emptySet(), editingId = null) }; msg("All waypoints cleared") }
    fun removeLastWaypoint() { p.waypoints.lastOrNull()?.let { deleteWaypoint(it.id) } }
    fun setPhotoAtEachWaypoint(on: Boolean) {
        val s = p.settings.copy(capture = if (on) com.aerogrid.app.model.CaptureStrategy.WAYPOINT_PHOTO else com.aerogrid.app.model.CaptureStrategy.TIMED_INTERVAL)
        commit(MissionEditor.withWaypoints(p.copy(settings = s), p.waypoints.map { w ->
            if (on && w.action == com.aerogrid.app.model.CameraAction.NONE && !w.transit) w.copy(action = com.aerogrid.app.model.CameraAction.TAKE_PHOTO)
            else if (!on && w.action == com.aerogrid.app.model.CameraAction.TAKE_PHOTO) w.copy(action = com.aerogrid.app.model.CameraAction.NONE) else w }))
    }

    /** Apply a change with undo history, recompute stats and autosave. */
    fun commit(np: Project, recordUndo: Boolean = true) {
        val old = p
        if (np == old) return
        if (recordUndo) { undo.addLast(old); if (undo.size > 100) undo.removeFirst(); redo.clear() }
        _ui.update { it.copy(project = np, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), selected = it.selected.filter { id -> np.waypoints.any { w -> w.id == id } }.toSet()) }
        if (simulation != null) stopSimulation()
        recompute(); scheduleSave()
    }

    fun undo() { val prev = undo.removeLastOrNull() ?: return; redo.addLast(p); _ui.update { it.copy(project = prev, canUndo = undo.isNotEmpty(), canRedo = true) }; recompute(); scheduleSave() }
    fun redo() { val nxt = redo.removeLastOrNull() ?: return; undo.addLast(p); _ui.update { it.copy(project = nxt, canUndo = true, canRedo = redo.isNotEmpty()) }; recompute(); scheduleSave() }

    private fun recompute() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch(Dispatchers.Default) {
            delay(80)
            val proj = p
            val st = Stats.compute(proj)
            val issues = Validator.validate(proj, dem, st)
            val prof = if (_ui.value.mode == Mode.ADVANCED && proj.waypoints.size in 2..5000) Terrain.profile(proj, dem, 25.0) else emptyList()
            if (isActive) _ui.update { it.copy(stats = st, issues = issues, profile = prof) }
        }
    }

    /** True when the in-memory mission has changes that are not yet in the database. */
    @Volatile private var dirty = false
    private val saveMutex = Mutex()

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(1200); saveNow() }
    }

    /**
     * Writes the current mission. Saves are serialised so a new mission is inserted exactly once
     * (autosave racing an explicit save used to create duplicate rows).
     */
    suspend fun saveNow(): Project = saveMutex.withLock {
        val snapshot = p
        val stored = repo.save(snapshot)
        _ui.update { u ->
            val withId = if (u.project.id == 0L) u.project.copy(id = stored.id) else u.project
            u.copy(project = withId, savedAt = System.currentTimeMillis())
        }
        if (p.copy(id = 0) == snapshot.copy(id = 0)) dirty = false
        _ui.value.project
    }

    /** Explicit save; also guarantees an id for screens that need one (e.g. Transfer). */
    fun save(then: ((Long) -> Unit)? = null) = viewModelScope.launch {
        val s = if (dirty || p.id == 0L) saveNow() else p
        msg("Mission saved"); then?.invoke(s.id)
    }

    /** Leaving the planner: flush pending edits only — never rewrite an unchanged mission. */
    fun close() {
        saveJob?.cancel()
        if (dirty) CoroutineScope(Dispatchers.IO + NonCancellable).launch { saveNow() }
    }
    fun rename(name: String) = commit(p.copy(name = name.ifBlank { "Untitled mission" }))

    // ---------------- tools & drawing ----------------
    fun setTool(t: Tool) {
        _ui.update { it.copy(tool = t, draft = if (t == it.tool) it.draft else emptyList(), editingId = null) }
    }
    fun undoDraftPoint() = _ui.update { it.copy(draft = it.draft.dropLast(1)) }
    fun cancelDraft() = _ui.update { it.copy(draft = emptyList(), tool = Tool.NONE) }

    fun finishDraft() {
        val u = _ui.value; val d = u.draft
        when (u.tool) {
            Tool.DRAW_BOUNDARY, Tool.FREEHAND -> if (d.size >= 3) {
                val replace = u.mode == Mode.QUICK
                commit(p.copy(boundaries = if (replace) listOf(d) else p.boundaries + listOf(d), location = p.location))
                msg("Boundary closed · ${formatArea(GeoMath.area(d))}")
            } else { msg("A boundary needs at least 3 points"); return }
            Tool.DRAW_EXCLUSION -> if (d.size >= 3) commit(p.copy(exclusions = p.exclusions + listOf(d))) else { msg("An exclusion zone needs at least 3 points"); return }
            Tool.DRAW_CORRIDOR -> if (d.size >= 2) commit(p.copy(corridor = d, settings = p.settings.copy(pattern = FlightPattern.CORRIDOR))) else { msg("A corridor needs at least 2 points"); return }
            else -> {}
        }
        _ui.update { it.copy(draft = emptyList(), tool = Tool.NONE) }
    }

    fun clearBoundary() = commit(p.copy(boundaries = emptyList(), exclusions = if (_ui.value.mode == Mode.QUICK) p.exclusions else emptyList(), corridor = emptyList()))
    fun deleteRing(kind: Int, index: Int) = commit(MissionEditor.withRings(p, kind, MissionEditor.rings(p, kind).filterIndexed { i, _ -> i != index }))

    override fun onFreehand(points: List<GeoPoint>) {
        if (points.size < 3) return
        // simplify freehand stroke (~2 m) and close automatically
        val proj = com.aerogrid.app.engine.LocalProjection.around(points)
        val ls = proj.line(points)
        val simp = DouglasPeuckerSimplifier.simplify(ls, 2.0).coordinates.map { proj.toGeo(it) }
        _ui.update { it.copy(draft = simp) }
        if (simp.size >= 3) finishDraft()
    }

    override fun onTap(pt: GeoPoint, hit: Hit?) {
        val u = _ui.value
        when (u.tool) {
            Tool.DRAW_BOUNDARY, Tool.DRAW_EXCLUSION, Tool.DRAW_CORRIDOR, Tool.MEASURE_DISTANCE, Tool.MEASURE_AREA -> {
                // Tapping the first vertex closes the polygon.
                if (u.draft.size >= 3 && u.tool != Tool.DRAW_CORRIDOR && u.tool.name.startsWith("DRAW") && GeoMath.distance(u.draft.first(), pt) < closeRadius()) { finishDraft(); return }
                _ui.update { it.copy(draft = it.draft + pt) }
            }
            Tool.ADD_WAYPOINT -> {
                val manual = p.boundaries.isEmpty() || u.mode == Mode.MANUAL && p.generated.isEmpty()
                commit(MissionEditor.insertAfter(p.copy(settings = if (manual) p.settings.copy(pattern = FlightPattern.MANUAL, speedMs = p.settings.speedMs ?: 5.0) else p.settings), p.waypoints.lastIndex, pt))
            }
            Tool.SET_TAKEOFF -> { commit(p.copy(takeoff = pt)); _ui.update { it.copy(tool = Tool.NONE) } }
            Tool.SET_LANDING -> { commit(p.copy(landing = pt)); _ui.update { it.copy(tool = Tool.NONE) } }
            Tool.EDIT_SHAPES -> if (hit is Hit.Mid) commit(MissionEditor.insertVertex(p, hit.kind, hit.ring, hit.afterV, pt))
            else -> if (hit is Hit.Wp) selectWaypoint(hit.id, openEditor = !u.multiSelect) else if (u.tool == Tool.EDIT_WAYPOINTS || u.tool == Tool.NONE) _ui.update { it.copy(selected = emptySet(), editingId = null) }
        }
    }

    private fun closeRadius() = 15.0

    override fun onLongPress(pt: GeoPoint, hit: Hit?) {
        when {
            hit is Hit.Vertex && _ui.value.tool == Tool.EDIT_SHAPES -> { commit(MissionEditor.deleteVertex(p, hit.kind, hit.ring, hit.v)); msg("Vertex deleted") }
            hit is Hit.Wp -> { _ui.update { it.copy(multiSelect = true, selected = it.selected + hit.id) } }
            _ui.value.tool == Tool.EDIT_WAYPOINTS -> { commit(MissionEditor.addNearest(p, pt)); msg("Waypoint inserted") }
        }
    }

    override fun onDragStart(hit: Hit, pt: GeoPoint) {
        dragBase = p; dragAnchor = pt
        if (hit is Hit.Wp && hit.id !in _ui.value.selected) _ui.update { it.copy(selected = if (it.multiSelect) it.selected + hit.id else setOf(hit.id)) }
        if (hit is Hit.Mid) { val np = MissionEditor.insertVertex(p, hit.kind, hit.ring, hit.afterV, pt); _ui.update { it.copy(project = np) } }
    }

    override fun onDrag(hit: Hit, pt: GeoPoint) {
        val base = dragBase ?: return
        val np = when (hit) {
            is Hit.Wp -> { val a = dragAnchor!!; MissionEditor.moveWaypoints(base, _ui.value.selected, pt.lat - a.lat, pt.lon - a.lon) }
            is Hit.Vertex -> MissionEditor.moveVertex(base, hit.kind, hit.ring, hit.v, pt)
            is Hit.Mid -> MissionEditor.moveVertex(MissionEditor.insertVertex(base, hit.kind, hit.ring, hit.afterV, pt), hit.kind, hit.ring, hit.afterV + 1, pt)
        }
        _ui.update { it.copy(project = np) }
    }

    override fun onDragEnd(hit: Hit, pt: GeoPoint) {
        onDrag(hit, pt)
        val base = dragBase ?: return
        dragBase = null
        undo.addLast(base); redo.clear()
        _ui.update { it.copy(canUndo = true, canRedo = false) }
        recompute(); scheduleSave()
    }

    // ---------------- generation ----------------
    fun generate(silent: Boolean = false) {
        if (p.settings.pattern != FlightPattern.CORRIDOR && p.boundaries.isEmpty()) { msg("Draw a boundary first"); return }
        regenJob?.cancel()
        regenJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val (np, r) = withContext(Dispatchers.Default) { MissionEditor.regenerate(p, dem) }
            _ui.update { it.copy(busy = false, selected = emptySet(), tool = if (it.tool in DRAW_TOOLS) Tool.NONE else it.tool) }
            if (r.waypoints.isEmpty()) { msg(r.warnings.firstOrNull() ?: "No waypoints generated"); return@launch }
            commit(np)
            if (!silent) {
                msg("${np.waypoints.size} waypoints · ${r.lineCount} lines · ${np.blocks.size} mission(s)" + (r.warnings.firstOrNull()?.let { " — $it" } ?: ""))
                emitCamera(UiEvent.Fit(np.waypoints.map { it.point }))
            }
        }
    }

    /** Settings that change grid geometry trigger regeneration (debounced) when a generated grid exists. */
    fun updateSettings(regenerate: Boolean = true, f: (FlightSettings) -> FlightSettings) {
        val np = MissionEditor.withWaypoints(p.copy(settings = f(p.settings)), p.waypoints)
        commit(np)
        if (regenerate && p.generated.isNotEmpty() && p.settings.pattern != FlightPattern.MANUAL) {
            regenJob?.cancel()
            regenJob = viewModelScope.launch { delay(350); generate(silent = true) }
        }
    }

    fun updateCamera(c: com.aerogrid.app.model.CameraProfile) = commit(p.copy(camera = c))

    // ---------------- altitude ----------------
    fun setGlobalHeight(h: Double) = commit(MissionEditor.setGlobalHeight(p, h))
    fun applyHeightToSelected(h: Double) { if (_ui.value.selected.isEmpty()) msg("Select waypoints first") else commit(MissionEditor.applyHeightToSelected(p, _ui.value.selected, h)) }
    fun overwriteAllHeights(h: Double) = commit(MissionEditor.overwriteAllHeights(p, h)).also { msg("All waypoints set to ${h.toInt()} m") }
    fun resetAllHeights() = commit(MissionEditor.resetAllHeights(p)).also { msg("Individual heights cleared") }
    fun restoreDefaultHeight() = commit(MissionEditor.restoreDefaultHeight(p))

    // ---------------- waypoint editing ----------------
    fun selectWaypoint(id: Long, openEditor: Boolean) {
        _ui.update { u ->
            val sel = if (u.multiSelect) (if (id in u.selected) u.selected - id else u.selected + id) else setOf(id)
            u.copy(selected = sel, editingId = if (openEditor) id else u.editingId)
        }
    }
    fun openEditor(id: Long?) { _ui.update { it.copy(editingId = id, selected = if (id != null && !it.multiSelect) setOf(id) else it.selected) } }
    fun toggleMultiSelect() = _ui.update { it.copy(multiSelect = !it.multiSelect, selected = if (it.multiSelect) emptySet() else it.selected) }
    fun selectAll() = _ui.update { it.copy(selected = it.project.waypoints.map { w -> w.id }.toSet(), multiSelect = true) }
    fun selectLine(id: Long) { val li = p.waypoints.firstOrNull { it.id == id }?.lineIndex ?: return; _ui.update { it.copy(selected = p.waypoints.filter { w -> w.lineIndex == li }.map { w -> w.id }.toSet(), multiSelect = true) } }
    fun clearSelection() = _ui.update { it.copy(selected = emptySet(), editingId = null) }
    fun updateWaypoints(ids: Set<Long>, f: (Waypoint) -> Waypoint) = commit(MissionEditor.update(p, ids, f))
    fun updateSelected(f: (Waypoint) -> Waypoint) = updateWaypoints(_ui.value.selected, f)
    fun deleteSelected() { val n = _ui.value.selected.size; commit(MissionEditor.delete(p, _ui.value.selected)); _ui.update { it.copy(selected = emptySet(), editingId = null) }; msg("$n waypoint(s) deleted") }
    fun deleteWaypoint(id: Long) { commit(MissionEditor.delete(p, setOf(id))); _ui.update { it.copy(editingId = null) } }
    fun insertAfter(id: Long) {
        val i = p.waypoints.indexOfFirst { it.id == id }; if (i < 0) return
        val a = p.waypoints[i]; val b = p.waypoints.getOrNull(i + 1)
        val pt = if (b != null) GeoMath.interpolate(a.point, b.point, 0.5) else GeoPoint(a.lat + 0.0001, a.lon)
        commit(MissionEditor.insertAfter(p, i, pt))
    }
    fun resetOverrides(ids: Set<Long>) = commit(MissionEditor.resetOverrides(p, ids))
    fun reverseRoute() = commit(MissionEditor.reverse(p)).also { msg("Route reversed") }
    fun reorder(from: Int, to: Int) = commit(MissionEditor.reorder(p, from, to))
    fun resetRoute() { if (p.generated.isEmpty()) msg("No generated route to restore") else { commit(MissionEditor.resetToGenerated(p)); msg("Route reset to generated grid") } }
    fun optimiseRoute() { val np = MissionEditor.optimise(p); if (np === p) msg(if (p.takeoff == null) "Set a takeoff point to optimise the start" else "Route already optimal for takeoff/landing") else { commit(np); msg("Route direction optimised for takeoff/landing") } }

    // ---------------- blocks ----------------
    fun resplit(maxWp: Int, batteryMin: Double) {
        val np = p.copy(settings = p.settings.copy(maxWaypointsPerMission = maxWp, batteryMinutes = batteryMin))
        commit(np.copy(blocks = MissionSplitter.split(np))); msg("${_ui.value.project.blocks.size} mission block(s)")
    }
    fun highlightBlock(i: Int?) {
        _ui.update { it.copy(highlightBlock = i) }
        i?.let { p.blocks.getOrNull(it) }?.let { b -> emitCamera(UiEvent.Fit(MissionSplitter.blockWaypoints(p, b).map { w -> w.point })) }
    }
    fun selectBlock(i: Int) { val b = p.blocks.getOrNull(i) ?: return; _ui.update { it.copy(selected = MissionSplitter.blockWaypoints(p, b).map { w -> w.id }.toSet(), multiSelect = true) } }

    // ---------------- simulation ----------------
    fun startSimulation() {
        if (p.waypoints.size < 2) { msg("Generate waypoints before previewing"); return }
        val sim = Simulation(p); simulation = sim
        _ui.update { it.copy(sim = SimUi(true, 10f, sim.at(0.0)), tool = Tool.NONE) }
        emitCamera(UiEvent.Fit(p.waypoints.map { it.point }))
        runSim(0.0)
    }
    private fun runSim(from: Double) {
        simJob?.cancel()
        simJob = viewModelScope.launch(Dispatchers.Default) {
            var t = from; var last = System.nanoTime()
            while (isActive) {
                delay(66)
                val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now
                val s = _ui.value.sim ?: break
                if (!s.running) break
                t += dt * s.speed
                val st = simulation?.at(t) ?: break
                _ui.update { it.copy(sim = it.sim?.copy(state = st)) }
                if (st.done) { _ui.update { it.copy(sim = it.sim?.copy(running = false)) }; break }
            }
        }
    }
    fun toggle3D() = _ui.update { it.copy(threeD = !it.threeD) }
    fun pauseSimulation() { simJob?.cancel(); _ui.update { it.copy(sim = it.sim?.copy(running = false)) } }
    fun resumeSimulation() { val s = _ui.value.sim ?: return; if (s.state?.done == true) { restartSimulation(); return }; _ui.update { it.copy(sim = s.copy(running = true)) }; runSim(s.state?.elapsed ?: 0.0) }
    fun restartSimulation() { _ui.update { it.copy(sim = it.sim?.copy(running = true, state = simulation?.at(0.0))) }; runSim(0.0) }
    fun setSimSpeed(x: Float) = _ui.update { it.copy(sim = it.sim?.copy(speed = x)) }
    fun stopSimulation() { simJob?.cancel(); simulation = null; _ui.update { it.copy(sim = null) } }

    fun scene(u: PlannerUi, showFootprints: Boolean): MapScene = MapScene(
        project = u.project, draft = u.draft,
        draftClosed = u.tool in setOf(Tool.DRAW_BOUNDARY, Tool.DRAW_EXCLUSION, Tool.MEASURE_AREA, Tool.FREEHAND),
        selected = u.selected,
        hitWaypoints = u.sim == null && u.tool in setOf(Tool.NONE, Tool.EDIT_WAYPOINTS),
        hitShapes = u.sim == null && u.tool == Tool.EDIT_SHAPES,
        freehand = u.tool == Tool.FREEHAND,
        drone = u.sim?.state?.let { it.pos to it.headingDeg },
        capturedPhotos = u.sim?.state?.photos ?: emptyList(),
        showFootprints = showFootprints, myLocation = u.myLocation, highlightBlock = u.highlightBlock, threeD = u.threeD,
    )

    // ---------------- terrain ----------------
    private suspend fun loadDem(path: String?) {
        if (path == demPathLoaded) return
        dem = withContext(Dispatchers.IO) { Terrain.load(path) }
        demPathLoaded = path
        _ui.update { it.copy(demInfo = dem?.description ?: if (path != null) "DEM file missing or unreadable" else null) }
    }

    fun importDem(uri: Uri) = viewModelScope.launch {
        try {
            val name = displayName(uri) ?: "dem.hgt"
            val dir = File(getApplication<Application>().filesDir, "dem").apply { mkdirs() }
            val f = File(dir, name)
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
            val src = withContext(Dispatchers.IO) { if (name.endsWith(".asc", true)) com.aerogrid.app.engine.AscDem(f) else com.aerogrid.app.engine.HgtDem(f) }
            demPathLoaded = null
            commit(p.copy(demPath = f.absolutePath))
            loadDem(f.absolutePath)
            recompute()
            msg("Terrain loaded: ${src.description}")
        } catch (e: Exception) { msg("DEM import failed: ${e.message}") }
    }
    fun removeDem() = viewModelScope.launch { commit(p.copy(demPath = null)); loadDem(null); recompute() }
    fun demSource() = dem

    // ---------------- search & location ----------------
    fun search(q: String) = viewModelScope.launch {
        if (q.isBlank()) return@launch
        _ui.update { it.copy(busy = true) }
        val res = withContext(Dispatchers.IO) {
            val out = ArrayList<SearchResult>()
            runCatching {
                if (Geocoder.isPresent()) @Suppress("DEPRECATION") Geocoder(getApplication()).getFromLocationName(q, 5)?.forEach { a ->
                    out += SearchResult(listOfNotNull(a.featureName, a.locality, a.adminArea, a.countryName).distinct().joinToString(", "), GeoPoint(a.latitude, a.longitude))
                }
            }
            if (out.isEmpty()) runCatching {
                val c = URL("https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "AeroGrid-Android/1.0"); c.connectTimeout = 8000; c.readTimeout = 8000
                val arr = com.aerogrid.app.io.AppJson.parseToJsonElement(c.inputStream.bufferedReader().readText()) as kotlinx.serialization.json.JsonArray
                arr.forEach { e -> val o = e as kotlinx.serialization.json.JsonObject
                    out += SearchResult(o["display_name"]!!.toString().trim('"'), GeoPoint(o["lat"]!!.toString().trim('"').toDouble(), o["lon"]!!.toString().trim('"').toDouble())) }
            }
            out
        }
        _ui.update { it.copy(busy = false, searchResults = res) }
        if (res.isEmpty()) msg("No results for \"$q\" (offline or not found)")
    }
    fun pickSearch(r: SearchResult) {
        _ui.update { it.copy(searchResults = emptyList()) }
        if (p.location.isBlank()) commit(p.copy(location = r.name.substringBefore(',')), recordUndo = false)
        emitCamera(UiEvent.Fly(r.point, 16.0))
    }
    fun clearSearch() = _ui.update { it.copy(searchResults = emptyList()) }

    @SuppressLint("MissingPermission")
    fun locate() {
        val lm = getApplication<Application>().getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { msg("Location is turned off"); return }
        val use = { l: android.location.Location? ->
            if (l != null) { val g = GeoPoint(l.latitude, l.longitude); _ui.update { it.copy(myLocation = g) }; emitCamera(UiEvent.Fly(g, 16.5)) } else msg("Waiting for GPS fix…")
        }
        runCatching {
            lm.getLastKnownLocation(provider)?.let(use)
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, getApplication<Application>().mainExecutor) { use(it) }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { use(it) }, null)
        }.onFailure { msg("Location unavailable: ${it.message}") }
    }

    // ---------------- import ----------------
    private fun displayName(uri: Uri): String? = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Import KML/KMZ/GeoJSON/GPX (boundaries, exclusions, corridor, points) or a DJI mission into the current project. */
    fun importFile(uri: Uri, asWaypoints: Boolean) = viewModelScope.launch {
        try {
            val name = displayName(uri) ?: "import"
            val bytes = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            when (GeoImport.detect(name, bytes)) {
                "dji" -> {
                    val m = withContext(Dispatchers.Default) { WpmlReader.read(bytes, p.nextId) }
                    val gh = m.globalHeight ?: m.waypoints.mapNotNull { it.heightOverride }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: p.settings.heightM
                    val wps = m.waypoints.map { if (it.heightOverride == gh) it.copy(heightOverride = null) else it }
                    val np = p.copy(waypoints = wps, nextId = p.nextId + wps.size + 1, generated = emptyList(), settings = p.settings.copy(pattern = FlightPattern.MANUAL, heightM = gh,
                        altitudeMode = com.aerogrid.app.model.AltitudeMode.RELATIVE_TAKEOFF, speedMs = m.autoSpeed, finishAction = m.finish ?: p.settings.finishAction, lostAction = m.lost ?: p.settings.lostAction))
                    commit(np.copy(blocks = MissionSplitter.split(np)))
                    msg("Imported DJI mission: ${wps.size} waypoints (drone enum ${m.drone.droneEnumValue})")
                }
                "aerogrid" -> { val ps = BackupCodec.decode(bytes); ps.forEach { repo.save(it.copy(id = 0)) }; msg("${ps.size} project(s) added to the library") }
                else -> {
                    val g = withContext(Dispatchers.Default) { GeoImport.parse(name, bytes) }
                    commit(p.withImported(g, asWaypoints))
                    msg("Imported ${g.boundaries.size} boundary, ${g.exclusions.size} exclusion, ${g.lines.size} line, ${g.points.size} point feature(s)")
                }
            }
            val pts = p.boundaries.flatten() + p.waypoints.map { it.point } + p.corridor
            if (pts.isNotEmpty()) emitCamera(UiEvent.Fit(pts))
        } catch (e: Exception) { msg("Import failed: ${e.message}") }
    }

    // ---------------- export ----------------
    fun kmzFor(blockIndex: Int?): Pair<String, WpmlWriter.Output> {
        val proj = p
        val b = blockIndex?.let { proj.blocks[it] }
        val wps = b?.let { MissionSplitter.blockWaypoints(proj, it) } ?: proj.waypoints
        val name = (b?.name ?: MissionSplitter.blockName(proj.name, 1).removeSuffix("_Block_01")) + ".kmz"
        return name to WpmlWriter.build(proj, wps, settings.value.drone, dem)
    }

    /** Export one block (or all blocks) as DJI Fly KMZ to Downloads/AeroGrid after validation. */
    fun exportKmz(blockIndex: Int?, share: Boolean) = viewModelScope.launch {
        val issues = Validator.validate(p, dem)
        val blocking = issues.filter { it.severity == com.aerogrid.app.engine.Severity.ERROR && !it.message.contains("waypoint mission limit") }
        if (blocking.isNotEmpty()) { msg("Cannot export: ${blocking.first().message}"); return@launch }
        try {
            val idxs: List<Int?> = when { blockIndex != null -> listOf(blockIndex); p.blocks.size > 1 -> p.blocks.indices.toList(); else -> listOf(null) }
            val files = ArrayList<File>()
            val saved = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (i in idxs) {
                    val (name, out) = kmzFor(i)
                    val v = KmzValidator.validate(out.bytes, p.settings.maxWaypointsPerMission, settings.value.drone.namespace)
                    if (!v.ok) error("$name failed validation: ${v.errors.first()}")
                    saved += ExternalTransfer.saveToDownloads(getApplication(), name, out.bytes, "application/vnd.google-earth.kmz")
                    files += File(getApplication<Application>().cacheDir, name).apply { writeBytes(out.bytes) }
                }
            }
            commit(p.copy(exportHistory = p.exportHistory + saved.map { ExportRecord(System.currentTimeMillis(), "DJI KMZ", it) }), recordUndo = false)
            msg("Validated & saved ${saved.size} KMZ to Download/AeroGrid")
            if (share) _events.tryEmit(UiEvent.Launch(Intent.createChooser(ExternalTransfer.shareIntent(getApplication(), files, "application/vnd.google-earth.kmz"), "Share DJI mission")))
        } catch (e: Exception) { msg("Export failed: ${e.message}") }
    }

    fun exportFormat(kind: String) = viewModelScope.launch {
        try {
            val proj = p; val base = proj.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")
            val (name, bytes, mime) = withContext(Dispatchers.Default) {
                when (kind) {
                    "kml" -> Triple("$base.kml", GeoExport.kml(proj, dem).toByteArray(), "application/vnd.google-earth.kml+xml")
                    "geojson" -> Triple("$base.geojson", GeoExport.geojson(proj).toByteArray(), "application/geo+json")
                    "csv" -> Triple("$base.csv", GeoExport.csv(proj, dem).toByteArray(), "text/csv")
                    "gpx" -> Triple("$base.gpx", GeoExport.gpx(proj).toByteArray(), "application/gpx+xml")
                    else -> Triple("$base.aerogrid", BackupCodec.projectJson(proj).toByteArray(), "application/json")
                }
            }
            saveExport(name, bytes, mime, kind.uppercase())
        } catch (e: Exception) { msg("Export failed: ${e.message}") }
    }

    fun exportImage(bitmap: Bitmap, pdf: Boolean) = viewModelScope.launch {
        try {
            val proj = p; val base = proj.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")
            val bytes = withContext(Dispatchers.Default) { try { if (pdf) Reports.pdf(proj, Stats.compute(proj), Validator.validate(proj, dem), bitmap, dem) else Reports.png(bitmap) } finally { bitmap.recycle() } }
            saveExport(if (pdf) "$base.pdf" else "$base.png", bytes, if (pdf) "application/pdf" else "image/png", if (pdf) "PDF" else "PNG")
        } catch (e: Exception) { msg("Export failed: ${e.message}") }
    }

    private suspend fun saveExport(name: String, bytes: ByteArray, mime: String, kind: String) {
        val path = withContext(Dispatchers.IO) { ExternalTransfer.saveToDownloads(getApplication(), name, bytes, mime) }
        val f = withContext(Dispatchers.IO) { File(getApplication<Application>().cacheDir, name).apply { writeBytes(bytes) } }
        commit(p.copy(exportHistory = p.exportHistory + ExportRecord(System.currentTimeMillis(), kind, path)), recordUndo = false)
        msg("Saved $path")
        _events.tryEmit(UiEvent.Launch(Intent.createChooser(ExternalTransfer.shareIntent(getApplication(), listOf(f), mime), "Share $kind")))
    }

    fun offlineMessage(t: String) = msg(t)

    override fun onCleared() { simJob?.cancel(); close() }

    companion object {
        val TAVUA = GeoPoint(-17.4442, 177.8646)
        val QUICK_TOOLS = setOf(Tool.NONE, Tool.DRAW_BOUNDARY, Tool.FREEHAND, Tool.EDIT_SHAPES, Tool.EDIT_WAYPOINTS)
        val MANUAL_TOOLS = setOf(Tool.NONE, Tool.ADD_WAYPOINT, Tool.EDIT_WAYPOINTS, Tool.SET_TAKEOFF, Tool.MEASURE_DISTANCE)
        val DRAW_TOOLS = setOf(Tool.DRAW_BOUNDARY, Tool.FREEHAND, Tool.DRAW_EXCLUSION, Tool.DRAW_CORRIDOR)
    }
}
