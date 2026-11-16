package com.aerogrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aerogrid.app.ui.DashboardScreen
import com.aerogrid.app.ui.LibraryScreen
import com.aerogrid.app.ui.Mode
import com.aerogrid.app.ui.PlannerScreen
import com.aerogrid.app.ui.PlannerViewModel
import com.aerogrid.app.ui.SettingsScreen
import com.aerogrid.app.ui.TransferScreen
import com.aerogrid.app.ui.TransferViewModel
import com.aerogrid.app.ui.theme.AeroGridTheme

class MainActivity : ComponentActivity() {
    private val planner: PlannerViewModel by viewModels()
    private val transfer: TransferViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as AeroGridApp
        setContent {
            val s by app.settings.state.collectAsState()
            AeroGridTheme(s.theme, s.materialYou) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // route: "home" | "plan:<id>:<mode>" | "library" | "transfer:<id>" | "settings"
                    var route by rememberSaveable { mutableStateOf("home") }
                    var lastProject by rememberSaveable { mutableStateOf(-1L) }
                    if (route != "home") BackHandler(enabled = !route.startsWith("plan")) { route = "home" }
                    AnimatedContent(route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { r ->
                        when {
                            r == "home" -> DashboardScreen(app, onPlan = { id, m -> route = "plan:${id ?: -1}:${m.name}" }, onLibrary = { route = "library" },
                                onTransfer = { route = "transfer:$lastProject" }, onSettings = { route = "settings" })
                            r.startsWith("plan") -> {
                                val parts = r.split(':')
                                val id = parts[1].toLong().takeIf { it > 0 }
                                PlannerScreen(planner, id, Mode.valueOf(parts[2]), app.settings, onBack = { route = "home" },
                                    onTransfer = { pid -> lastProject = pid; route = "transfer:$pid" })
                            }
                            r == "library" -> LibraryScreen(app, onOpen = { id -> route = "plan:$id:QUICK" }, onBack = { route = "home" })
                            r.startsWith("transfer") -> TransferScreen(transfer, r.substringAfter(':').toLong().takeIf { it > 0 }, onBack = {
                                route = if (lastProject > 0) "plan:$lastProject:QUICK" else "home"
                            })
                            else -> SettingsScreen(app, onBack = { route = "home" })
                        }
                    }
                }
            }
        }
    }
}
