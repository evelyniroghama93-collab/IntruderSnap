package com.intrudersnap.prototype.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.ui.camera.CameraTestScreen
import com.intrudersnap.prototype.ui.dashboard.DashboardScreen
import com.intrudersnap.prototype.ui.history.HistoryScreen
import com.intrudersnap.prototype.ui.results.ResultsScreen
import com.intrudersnap.prototype.ui.settings.SettingsScreen
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
}

private enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", "IntruderSnap · Test dashboard", Icons.Filled.Home),
    HISTORY("History", "Security event history", Icons.AutoMirrored.Filled.List),
    THRESHOLD("Threshold", "Threshold & device setup", Icons.Filled.Settings),
    CAMERA("Camera", "Camera feasibility (A–G)", Icons.Filled.Face),
    RESULTS("Results", "Acceptance results", Icons.Filled.CheckCircle),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    var selected by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(selected.title) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
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
                Tab.DASHBOARD -> DashboardScreen()
                Tab.HISTORY -> HistoryScreen()
                Tab.THRESHOLD -> SettingsScreen()
                Tab.CAMERA -> CameraTestScreen()
                Tab.RESULTS -> ResultsScreen()
            }
        }
    }
}
