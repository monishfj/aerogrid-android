package com.aerogrid.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aerogrid.app.engine.GeoMath
import com.aerogrid.app.io.AppJson
import com.aerogrid.app.io.BackupCodec
import com.aerogrid.app.model.CameraProfile
import com.aerogrid.app.model.DroneInfo
import com.aerogrid.app.model.FlightSettings
import com.aerogrid.app.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val location: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val waypointCount: Int,
    val areaM2: Double,
    val json: String,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC") fun observeAll(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: Long): ProjectEntity?
    @Query("SELECT * FROM projects") suspend fun all(): List<ProjectEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ProjectEntity): Long
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM projects WHERE name LIKE '%' || :q || '%' OR location LIKE '%' || :q || '%' ORDER BY modifiedAt DESC") suspend fun search(q: String): List<ProjectEntity>
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class AeroGridDb : RoomDatabase() {
    abstract fun projects(): ProjectDao
    companion object {
        fun create(ctx: Context, inMemory: Boolean = false) =
            (if (inMemory) Room.inMemoryDatabaseBuilder(ctx, AeroGridDb::class.java) else Room.databaseBuilder(ctx, AeroGridDb::class.java, "aerogrid.db")).build()
    }
}

class ProjectRepository(private val dao: ProjectDao) {
    data class Summary(val id: Long, val name: String, val location: String, val created: Long, val modified: Long, val waypoints: Int, val areaM2: Double)

    val summaries: Flow<List<Summary>> = dao.observeAll().map { l -> l.map { Summary(it.id, it.name, it.location, it.createdAt, it.modifiedAt, it.waypointCount, it.areaM2) } }

    private fun decode(e: ProjectEntity) = AppJson.decodeFromString(Project.serializer(), e.json).copy(id = e.id)

    suspend fun load(id: Long): Project? = dao.get(id)?.let(::decode)

    /** Insert or update; returns the stored project (with id). */
    suspend fun save(p: Project): Project {
        val area = p.boundaries.sumOf { GeoMath.area(it) }
        val stored = p.copy(modified = System.currentTimeMillis())
        val e = ProjectEntity(stored.id, stored.name, stored.location, stored.created, stored.modified, stored.waypoints.size, area, AppJson.encodeToString(stored))
        val id = dao.upsert(e)
        return stored.copy(id = if (stored.id == 0L) id else stored.id)
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun duplicate(id: Long): Project? = load(id)?.let { save(it.copy(id = 0, name = it.name + " (copy)", created = System.currentTimeMillis())) }
    suspend fun rename(id: Long, name: String) { load(id)?.let { save(it.copy(name = name)) } }
    suspend fun backupAll(): ByteArray = BackupCodec.encode(dao.all().map(::decode))
    suspend fun restore(bytes: ByteArray): Int { val ps = BackupCodec.decode(bytes); ps.forEach { save(it.copy(id = 0)) }; return ps.size }
}

@Serializable
data class AppSettings(
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val materialYou: Boolean = false,
    val streetStyleUrl: String = "https://tiles.openfreemap.org/styles/liberty",
    val satelliteTilesUrl: String = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    val satelliteAttribution: String = "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community",
    val labelsTilesUrl: String = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
    val defaultSettings: FlightSettings = FlightSettings(),
    val camera: CameraProfile = CameraProfile.MINI_5_PRO,
    val drone: DroneInfo = DroneInfo(),
    val safTreeUri: String? = null,
    val showFootprints: Boolean = true,
    val mapStyle: Int = 1, // 0 street, 1 satellite, 2 hybrid
)

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("aerogrid", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(runCatching { AppJson.decodeFromString(AppSettings.serializer(), prefs.getString("settings", null)!!) }.getOrDefault(AppSettings()))
    val state: StateFlow<AppSettings> = _state
    fun update(f: (AppSettings) -> AppSettings) { val n = f(_state.value); _state.value = n; prefs.edit().putString("settings", AppJson.encodeToString(n)).apply() }
}
