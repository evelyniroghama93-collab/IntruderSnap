package com.intrudersnap.prototype.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.SectionCard
import com.intrudersnap.prototype.ui.components.StatusRow
import com.intrudersnap.prototype.ui.components.formatTime

@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDeactivate by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.pendingAlert?.let { alert ->
            IntrusionBanner(alert = alert, onAcknowledge = vm::acknowledgeAlert)
        }

        SectionCard("Device") {
            val d = state.live.device
            if (d == null) {
                Text("Reading device information…")
            } else {
                InfoRow("Manufacturer", "${d.manufacturer} (${d.brand})")
                InfoRow("Model", d.displayModel)
                InfoRow("Android version", d.androidVersion)
                InfoRow("API level", d.apiLevel.toString())
                InfoRow("MIUI / HyperOS", d.romLabel)
                InfoRow("Security patch", d.securityPatch)
                InfoRow("Build", d.buildIncremental)
            }
        }

        SectionCard("Native lock-screen monitoring") {
            StatusRow("Device administrator", state.adminStatus, if (state.live.adminActive) "Active (watch-login only)" else "Not active")
            StatusRow("Native monitoring", state.monitoringStatus, state.monitoringDetail)
            StatusRow("Success callback", state.successCallbackStatus, "Only sent after a correct credential that follows failed attempts.")
            if (!state.live.adminActive) {
                Button(
                    onClick = { adminLauncher.launch(AdminController.activationIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Activate device administrator") }
            } else {
                OutlinedButton(onClick = { confirmDeactivate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deactivate (required before uninstalling)")
                }
            }
        }

        SectionCard("Attempts") {
            InfoRow("Total recorded failed attempts", state.totalFailed.toString())
            InfoRow("Current incident (prototype count)", state.prototypeCount.toString())
            InfoRow("System counter now", state.live.systemCountNow)
            InfoRow("Configured threshold", state.threshold.toString())
            InfoRow("Last failed attempt", lastEventText(state.lastFailed))
            InfoRow("Last successful authentication", lastEventText(state.lastSucceeded, success = true))
        }

        SectionCard("Permissions & evidence capture") {
            StatusRow("Camera permission", state.cameraPermissionStatus)
            StatusRow("Background camera (test E)", state.backgroundCameraStatus, state.backgroundCameraDetail.ifBlank { null })
            StatusRow("Alert notifications", state.notificationStatus)
            if (!state.live.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
            }
        }

        Text(
            "Statuses only show “Supported” after the feature has been verified on this device. " +
                "Fingerprint and face failures are not password failures and are not counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDeactivate) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text("Deactivate device administrator?") },
            text = {
                Text(
                    "Lock-screen monitoring will stop. Android does not allow an active device administrator " +
                        "to be uninstalled, so deactivate first if you plan to remove IntruderSnap."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeactivate = false
                    vm.deactivateAdmin()
                }) { Text("Deactivate") }
            },
            dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IntrusionBanner(alert: SecurityEvent, onAcknowledge: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Intrusion threshold reached", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Time: ${formatTime(alert.timestampMillis)}")
            Text("System failed-attempt count: ${if (alert.systemFailedCount >= 0) alert.systemFailedCount else "unavailable"}")
            Text("Threshold: ${alert.threshold}")
            if (alert.note.isNotBlank()) Text(alert.note, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
        }
    }
}

private fun lastEventText(event: SecurityEvent?, success: Boolean = false): String {
    if (event == null) return "None recorded"
    val count = if (success) {
        "previous failures ${if (event.previousFailedCount >= 0) event.previousFailedCount else "?"}"
    } else {
        "system count ${if (event.systemFailedCount >= 0) event.systemFailedCount else "unavailable"}"
    }
    return "${formatTime(event.timestampMillis)} · $count"
}
