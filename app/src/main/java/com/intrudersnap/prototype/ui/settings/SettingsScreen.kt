package com.intrudersnap.prototype.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.data.ThresholdMode
import com.intrudersnap.prototype.domain.RetentionPolicy
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.OwnerGate
import com.intrudersnap.prototype.ui.components.SectionCard
import com.intrudersnap.prototype.ui.components.requestOwnerAuth

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    OwnerGate(what = "Settings") { SettingsContent(vm) }
}

@Composable
private fun SettingsContent(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mode by remember { mutableStateOf(settings.thresholdMode) }
    var customText by remember { mutableStateOf(settings.threshold.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDeactivate by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(settings.thresholdMode, settings.threshold) {
        mode = settings.thresholdMode
        if (settings.thresholdMode == ThresholdMode.CUSTOM) customText = settings.threshold.toString()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Failed-attempt threshold") {
            InfoRow("Current", "${settings.threshold} (${settings.thresholdMode.label})")
            ThresholdMode.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == option, onClick = { mode = option }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == option, onClick = null)
                    Text(option.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (mode == ThresholdMode.CUSTOM) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Custom attempt limit (1–30)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                error = vm.saveThreshold(mode, customText)
                if (error == null) toast("Threshold saved")
            }, modifier = Modifier.fillMaxWidth()) { Text("Save threshold") }
            Text(
                "Creates an IntruderSnap alert only. It never changes Android's own lockout or wipe behaviour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Record retention") {
            Text("Security records older than this are deleted automatically (securely, on this device).", style = MaterialTheme.typography.bodySmall)
            RetentionPolicy.ALLOWED_DAYS.forEach { days ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.retentionDays == days, onClick = {
                            vm.saveRetention(days)?.let { toast(it) }
                        }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.retentionDays == days, onClick = null)
                    Text("$days days", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionCard("Storage & privacy") {
            InfoRow("Records stored", storage.recordCount.toString())
            InfoRow(
                "Encryption",
                when (storage.keystoreUsable) {
                    true -> "AES-256-GCM, key in Android Keystore"
                    false -> "Keystore unavailable - records are flagged and re-encrypted later"
                    null -> "Checking…"
                },
            )
            InfoRow("Network access", "None - IntruderSnap has no internet permission")
            Text(
                "Records cannot be protected against a factory reset, root access or other privileged system access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Experimental Labs") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show experimental feasibility tools (camera tests, Milestone 1 results)", modifier = Modifier.weight(1f))
                Switch(checked = settings.experimentalToolsEnabled, onCheckedChange = { v -> vm.setExperimental(v)?.let { toast(it) } })
            }
            Text(
                "Background camera capture is experimental and is not an IntruderSnap feature. It is not enabled for normal use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Device administrator") {
            InfoRow("Status", if (storage.adminActive) "Active" else "Inactive")
            if (storage.adminActive) {
                OutlinedButton(onClick = { confirmDeactivate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deactivate (stops monitoring; required before uninstalling)")
                }
            }
            Text(
                "Deactivation through Android's own Settings cannot be blocked by any app. If it happens, IntruderSnap " +
                    "records it and alerts you when it next gets a chance to run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDeactivate) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text("Deactivate device administrator?") },
            text = { Text("Lock-screen monitoring will stop. You will be asked to confirm it's you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeactivate = false
                    requestOwnerAuth(context, "Deactivate monitoring", "Confirm it's you") { ok, msg ->
                        if (!ok) toast(msg ?: "Authentication failed") else vm.deactivateAdmin()?.let { toast(it) }
                        vm.refresh()
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancel") } },
        )
    }
}
