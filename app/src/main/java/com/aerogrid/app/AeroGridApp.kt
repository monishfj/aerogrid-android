package com.aerogrid.app

import android.app.Application
import com.aerogrid.app.data.AeroGridDb
import com.aerogrid.app.data.ProjectRepository
import com.aerogrid.app.data.SettingsStore
import org.maplibre.android.MapLibre

class AeroGridApp : Application() {
    lateinit var repo: ProjectRepository; private set
    lateinit var settings: SettingsStore; private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        repo = ProjectRepository(AeroGridDb.create(this).projects())
        settings = SettingsStore(this)
    }
}
