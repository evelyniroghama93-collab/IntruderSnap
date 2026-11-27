package com.intrudersnap.prototype.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intrudersnap.prototype.auth.OwnerSession
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.ui.health.HealthScreen
import com.intrudersnap.prototype.ui.history.HistoryScreen
import com.intrudersnap.prototype.ui.labs.LabsScreen
import com.intrudersnap.prototype.ui.settings.SettingsScreen
import com.intrudersnap.prototype.ui.setup.SetupScreen
import com.intrudersnap.prototype.ui.theme.IntruderSnapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeEnvironment.uiCreatedInProcess = true
        enableEdgeToEdge()
        setContent {
            IntruderSnapTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        RuntimeEnvironment.uiVisible = true
    }

    override fun onStop() {
        RuntimeEnvironment.uiVisible = false
        RuntimeEnvironment.lastUiStoppedElapsed = SystemClock.elapsedRealtime()
        // Protected screens lock again as soon as IntruderSnap leaves the screen.
        OwnerSession.end()
        super.onStop()
    }
}

private enum class AppTab(val label: String, val title: String, val icon: ImageVector) {
    HEALTH("Health", "Monitoring health", Icons.Filled.Home),
    HISTORY("History", "Security history", Icons.AutoMirrored.Filled.List),
    SETUP("Setup", "Guided setup", Icons.Filled.CheckCircle),
    SETTINGS("Settings", "Settings", Icons.Filled.Settings),
    LABS("Labs", "Experimental Labs", Icons.Filled.Warning),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(AppTab.HEALTH) }

    val tabs = AppTab.entries.filter { it != AppTab.LABS || settings.experimentalToolsEnabled }
    LaunchedEffect(settings.experimentalToolsEnabled) {
        if (!settings.experimentalToolsEnabled && selected == AppTab.LABS) selected = AppTab.SETTINGS
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(selected.title) }) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                AppTab.HEALTH -> HealthScreen()
                AppTab.HISTORY -> HistoryScreen()
                AppTab.SETUP -> SetupScreen()
                AppTab.SETTINGS -> SettingsScreen()
                AppTab.LABS -> LabsScreen()
            }
        }
    }
}
