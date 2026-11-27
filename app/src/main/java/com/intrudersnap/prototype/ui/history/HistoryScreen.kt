package com.intrudersnap.prototype.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.Pill
import com.intrudersnap.prototype.ui.components.formatTime

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val history by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${history.size} record(s) · stored in private device-protected storage · no credentials stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) { Text("Clear") }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "No events yet. Activate the device administrator, lock the phone and enter a wrong PIN, pattern or password.",
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        items(history, key = { it.id }) { event -> EventCard(event) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history and test results?") },
            text = { Text("This deletes all security events, resets the incident counter and clears camera and acceptance test results. The threshold setting is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearAll()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun EventType.accent(): Color = when (this) {
    EventType.PASSWORD_FAILED -> Color(0xFFB06000)
    EventType.INTRUSION_THRESHOLD_REACHED -> Color(0xFFC5221F)
    EventType.PASSWORD_SUCCEEDED -> Color(0xFF1E8E3E)
    EventType.CAMERA_TEST -> Color(0xFF6A1B9A)
    else -> Color(0xFF5F6368)
}

@Composable
private fun EventCard(e: SecurityEvent) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${e.id}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Pill(e.type.label, e.type.accent())
            }
            InfoRow("Timestamp", formatTime(e.timestampMillis))
            InfoRow(
                "System failed-attempt count",
                when {
                    e.systemFailedCount >= 0 -> e.systemFailedCount.toString()
                    e.systemCountError != null -> "Unavailable: ${e.systemCountError}"
                    else -> "Not applicable"
                },
            )
            InfoRow("Callback received", if (e.callbackReceived) "Yes · ${e.callbackName}" else "No (app-generated record)")
            InfoRow("Device model", e.deviceModel)
            if (e.type.isLockScreenCallback || e.type == EventType.INTRUSION_THRESHOLD_REACHED) {
                InfoRow("Prototype count / threshold", "${e.prototypeFailCount} / ${e.threshold}")
            }
            if (e.type == EventType.PASSWORD_SUCCEEDED) {
                InfoRow("Previous failed attempts", if (e.previousFailedCount >= 0) e.previousFailedCount.toString() else "Unknown")
            }
            InfoRow("Recorded before first unlock", if (e.userUnlocked) "No" else "Yes (Direct Boot)")
            InfoRow("Boot # / process", "${e.bootCount} / ${e.sessionId}")
            if (e.hasDiagnostics) {
                InfoRow(
                    "App open in process",
                    if (e.uiInProcess) "Yes" else "No (process age ${e.processAgeMs} ms)",
                )
                InfoRow("Power state", e.backgroundState)
            }
            if (e.note.isNotBlank()) {
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
