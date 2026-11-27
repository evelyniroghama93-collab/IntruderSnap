package com.intrudersnap.prototype.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.domain.Confidence
import com.intrudersnap.prototype.domain.IncidentTracker
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.Pill
import com.intrudersnap.prototype.ui.components.SectionCard
import com.intrudersnap.prototype.ui.components.formatTime
import com.intrudersnap.prototype.ui.components.requestOwnerAuth

fun Confidence.color(): Color = when (this) {
    Confidence.VERIFIED -> Color(0xFF1E8E3E)
    Confidence.ACTIVE_UNVERIFIED -> Color(0xFFB06000)
    Confidence.PERMISSION_REQUIRED -> Color(0xFFC5221F)
    Confidence.RESTRICTED -> Color(0xFF6A1B9A)
    Confidence.NOT_TESTED -> Color(0xFF5F6368)
}

@Composable
fun HealthScreen(vm: HealthViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var authMessage by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }

    val snap = state.snapshot
    val st = state.settings

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.pendingAlert?.let { alert ->
            IntrusionBanner(alert, authMessage) {
                if (!vm.acknowledgeAlert()) {
                    requestOwnerAuth(context, "Acknowledge alert", "Confirm it's you") { ok, msg ->
                        authMessage = if (ok && vm.acknowledgeAlert()) null else msg
                    }
                }
            }
        }

        // ---- Confidence
        val a = state.assessment
        SectionCard("Monitoring confidence") {
            if (a == null) {
                Text("Checking…")
            } else {
                Pill(a.confidence.label, a.confidence.color())
                a.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            Text(
                "IntruderSnap reports only what it has observed. Android and HyperOS can still delay or stop " +
                    "delivery; no setting guarantees it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Admin
        SectionCard("Device administrator") {
            InfoRow("Status", if (snap.adminActive) "Active (watch-login only)" else "Inactive")
            InfoRow("Watch-login policy", if (snap.watchLoginGranted) "Granted" else "Not granted")
            InfoRow("Secure screen lock", if (snap.deviceSecure) "Yes" else "No - set a PIN, pattern or password")
            if (!snap.adminActive && snap.loaded) {
                Button(
                    onClick = { adminLauncher.launch(AdminController.activationIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Activate device administrator") }
            }
        }

        // ---- Delivery
        SectionCard("Delivery") {
            InfoRow(
                "Last callback received",
                state.lastCallback?.let { "${formatTime(it.timestampMillis)} · ${it.type.label}" } ?: "None yet",
            )
            InfoRow("Lock-screen callbacks recorded", state.callbackCount.toString())
            InfoRow(
                "Background delivery test",
                if (state.lastColdDeliveryAt > 0) "Callback received while app was closed: ${formatTime(state.lastColdDeliveryAt)}" else "Not tested",
            )
            InfoRow(
                "Last successful reconciliation",
                if (st.lastReconciliationAt > 0) {
                    "${formatTime(st.lastReconciliationAt)} · " +
                        (IncidentTracker.ReconciliationResult.entries.firstOrNull { it.name == st.lastReconciliationResult }?.label
                            ?: st.lastReconciliationResult)
                } else {
                    "Never"
                },
            )
            InfoRow("Android's failed-attempt counter now", snap.systemCountNow)
            InfoRow("Current incident", if (st.incident.incidentOpen) "Open (${st.incident.appCount} counted)" else "None")
            InfoRow("Threshold", st.threshold.toString())
            InfoRow("Last admin-status check", formatTime(st.lastAdminCheckAt))
            OutlinedButton(onClick = vm::reconcileNow, modifier = Modifier.fillMaxWidth()) { Text("Reconcile with Android now") }
        }

        // ---- Power
        SectionCard("Battery & background state") {
            val p = snap.power
            InfoRow("App standby bucket", p?.standbyBucket ?: "-")
            InfoRow("Background restricted (Android)", p?.backgroundRestricted?.let { if (it) "Yes - Restricted" else "No" } ?: "Not readable")
            InfoRow("Battery optimization", p?.ignoringBatteryOptimizations?.let { if (it) "Not optimized" else "Optimized (default)" } ?: "Not readable")
            InfoRow("Alert notifications", if (snap.notificationsAllowed) "Allowed" else "Not allowed")
            Text(
                "HyperOS Autostart and its own battery saver cannot be read by apps. Check them in Setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Device
        SectionCard("Device") {
            InfoRow("Last reboot", formatTime(snap.lastBootAt))
            InfoRow("Boot count", if (snap.bootCount >= 0) snap.bootCount.toString() else "-")
            snap.device?.let { d ->
                InfoRow("Model", d.displayModel)
                InfoRow("Android / API", "${d.androidVersion} / ${d.apiLevel}")
                InfoRow("MIUI / HyperOS", d.romLabel)
                InfoRow("Security patch", d.securityPatch)
            }
        }
    }
}

@Composable
private fun IntrusionBanner(alert: SecurityEvent, message: String?, onAcknowledge: () -> Unit) {
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
            Text("Failed attempts in incident: ${if (alert.incidentCount > 0) alert.incidentCount else "-"} (threshold ${alert.threshold})")
            Text("Detected by: ${alert.source}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAcknowledge) { Text("Acknowledge (owner only)") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
