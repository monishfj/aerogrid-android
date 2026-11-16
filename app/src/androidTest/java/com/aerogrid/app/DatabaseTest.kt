package com.aerogrid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aerogrid.app.data.AeroGridDb
import com.aerogrid.app.data.ProjectRepository
import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val ring = listOf(GeoPoint(-17.444, 177.864), GeoPoint(-17.444, 177.868), GeoPoint(-17.441, 177.868), GeoPoint(-17.441, 177.864))

    @Test fun persistsProjectsWithEditsAndBackups() = runBlocking {
        val db = AeroGridDb.create(InstrumentationRegistry.getInstrumentation().targetContext, inMemory = true)
        val repo = ProjectRepository(db.projects())
        var p = MissionEditor.regenerate(Project(name = "Tavua_Block", location = "Tavua", boundaries = listOf(ring)), null).first
        p = MissionEditor.applyHeightToSelected(p, setOf(p.waypoints[1].id), 65.0)
        val saved = repo.save(p)
        val loaded = repo.load(saved.id)!!
        assertEquals(p.waypoints, loaded.waypoints)
        assertEquals(65.0, loaded.waypoints[1].heightOverride!!, 0.0)
        assertEquals(1, repo.summaries.first().size)
        repo.rename(saved.id, "Renamed"); assertEquals("Renamed", repo.load(saved.id)!!.name)
        val dup = repo.duplicate(saved.id)!!; assertEquals(2, repo.summaries.first().size)
        val backup = repo.backupAll()
        repo.delete(saved.id); repo.delete(dup.id); assertNull(repo.load(saved.id))
        assertEquals(2, repo.restore(backup)); assertEquals(2, repo.summaries.first().size)
        db.close()
    }
}
