package com.intrudersnap.prototype.ui.labs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrudersnap.prototype.ui.camera.CameraTestScreen
import com.intrudersnap.prototype.ui.components.OwnerGate
import com.intrudersnap.prototype.ui.results.ResultsScreen

/**
 * Experimental Milestone 1 feasibility tools. Only reachable when an authenticated owner has
 * enabled "experimental tools" in Settings, and itself protected by owner authentication.
 */
@Composable
fun LabsScreen() {
    OwnerGate(what = "Experimental Labs") {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Text(
                    "EXPERIMENTAL - feasibility tests only. Background camera capture is not an IntruderSnap feature " +
                        "and is not used during normal monitoring. Frames are measured and discarded; nothing is stored.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Camera A–G") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("M1 results") })
            }
            when (tab) {
                0 -> CameraTestScreen()
                else -> ResultsScreen()
            }
        }
    }
}
