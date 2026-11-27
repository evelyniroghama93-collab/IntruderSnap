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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.domain.IncidentTracker
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.OwnerGate
import com.intrudersnap.prototype.ui.components.Pill
import com.intrudersnap.prototype.ui.components.formatTime
import com.intrudersnap.prototype.ui.components.requestOwnerAuth

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    OwnerGate(what = "Security history") { HistoryContent(vm) }
}

@Composable
private fun HistoryContent(vm: HistoryViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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
                    "${history.size} record(s) · encrypted on this device (Android Keystore) · never uploaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) { Text("Clear") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (history.isEmpty()) {
            item { Text("No records yet.", modifier = Modifier.padding(top = 24.dp)) }
        }
        items(history, key = { it.id }) { EventCard(it) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all security records?") },
            text = {
                Text(
                    "You will be asked to confirm with your fingerprint or screen-lock PIN again. A record that the " +
                        "history was cleared is kept. Settings, threshold and setup are not changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    requestOwnerAuth(context, "Delete security history", "Confirm it's you") { ok, msg ->
                        message = when {
                            !ok -> msg ?: "Authentication failed."
                            !vm.clearAll() -> "Authentication expired. Try again."
                            else -> null
                        }
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun EventType.accent(): Color = when (this) {
    EventType.PASSWORD_FAILED -> Color(0xFFB06000)
    EventType.INTRUSION_THRESHOLD_REACHED, EventType.ADMIN_LOST, EventType.ADMIN_DISABLED -> Color(0xFFC5221F)
    EventType.PASSWORD_SUCCEEDED -> Color(0xFF1E8E3E)
    EventType.RECONCILIATION -> Color(0xFF0E8C82)
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
            InfoRow("Time", formatTime(e.timestampMillis))
            InfoRow("Source", e.source.ifBlank { if (e.callbackReceived) e.callbackName else "IntruderSnap" })
            if (e.type.isLockScreenCallback || e.type == EventType.INTRUSION_THRESHOLD_REACHED || e.type == EventType.RECONCILIATION) {
                InfoRow(
                    "Android's failed-attempt count",
                    when {
                        e.systemFailedCount >= 0 -> e.systemFailedCount.toString()
                        e.systemCountError != null -> "Unavailable: ${e.systemCountError}"
                        else -> "-"
                    },
                )
            }
            if (e.incidentCount > 0) InfoRow("Failed attempts in incident", "${e.incidentCount} (threshold ${e.threshold})")
            if (e.classification.isNotBlank()) {
                val label = IncidentTracker.Classification.entries.firstOrNull { it.name == e.classification }?.label
                    ?: IncidentTracker.ReconciliationResult.entries.firstOrNull { it.name == e.classification }?.label
                    ?: e.classification
                InfoRow("Classification", label)
            }
            if (e.type == EventType.PASSWORD_SUCCEEDED) {
                InfoRow("Previous failed attempts", if (e.previousFailedCount >= 0) e.previousFailedCount.toString() else "Unknown")
            }
            if (e.type.isLockScreenCallback) {
                InfoRow("Before first unlock", if (e.userUnlocked) "No" else "Yes (Direct Boot)")
                if (e.hasDiagnostics) InfoRow("App open when received", if (e.uiInProcess) "Yes" else "No (process age ${e.processAgeMs} ms)")
            }
            InfoRow("Device", e.deviceModel)
            if (e.note.isNotBlank()) {
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
