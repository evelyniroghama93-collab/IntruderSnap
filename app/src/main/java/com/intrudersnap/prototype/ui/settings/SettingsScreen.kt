package com.intrudersnap.prototype.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.SectionCard

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mode by remember { mutableStateOf(settings.thresholdMode) }
    var customText by remember { mutableStateOf(settings.threshold.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    // Keep the form in sync when the stored value loads or changes.
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
            InfoRow("Current threshold", "${settings.threshold} (${settings.thresholdMode.label})")
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
            Button(
                onClick = {
                    error = vm.saveThreshold(mode, customText)
                    if (error == null) Toast.makeText(context, "Threshold saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save threshold") }
            Text(
                "Use 3 for the first test. The threshold only creates an IntruderSnap alert; it never changes " +
                    "Android's own lockout or wipe behaviour. Keep your test count well below any lockout or " +
                    "wipe limit configured on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Xiaomi / Redmi setup (MIUI & HyperOS)") {
            val isXiaomi = device?.isXiaomiFamily == true
            Text(
                if (isXiaomi) "Xiaomi-family device detected: ${device?.romLabel}" else "These steps are for Xiaomi / Redmi / POCO devices.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Xiaomi's battery and autostart management can stop or delay apps that are not open. Device admin " +
                    "broadcasts are sent by the system, but whether MIUI/HyperOS delivers them to a stopped app has to " +
                    "be tested. The settings below may improve delivery; they do NOT guarantee it. They do not weaken " +
                    "any Android security protection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GuideStep("1. Autostart: allow IntruderSnap to start automatically (Security app › Permissions › Autostart).")
            ShortcutButton("Open Autostart settings") { open(context, vm.xiaomiAutostartIntent(), vm.appDetailsIntent(context)) }
            GuideStep("2. Battery saver: App info › Battery saver › No restrictions. Record which option you chose in the test report.")
            ShortcutButton("Open App info") { open(context, vm.appDetailsIntent(context), null) }
            ShortcutButton("Open battery optimization list") { open(context, vm.batteryOptimizationIntent(), vm.appDetailsIntent(context)) }
            GuideStep("3. Notifications: allow notifications and lock-screen notifications for “Intrusion alerts”.")
            ShortcutButton("Open notification settings") { open(context, vm.notificationSettingsIntent(context), vm.appDetailsIntent(context)) }
            GuideStep("4. Device admin apps: Settings › Passwords & security › Privacy › Special app access › Device admin apps (path varies by version).")
            ShortcutButton("Open Device admin apps") { open(context, vm.deviceAdminSettingsIntent(), null) }
            GuideStep("5. Optional: lock IntruderSnap in Recents (open Recents, long-press the app card, tap the lock icon) so MIUI's memory cleaner skips it.")
            GuideStep("To uninstall: deactivate the device administrator first (Dashboard › Deactivate). Android blocks uninstalling an active admin.")
        }
    }
}

@Composable
private fun GuideStep(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ShortcutButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

private fun open(context: Context, intent: Intent, fallback: Intent?) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        context.startActivity(intent.addFlags(flags))
    } catch (e: ActivityNotFoundException) {
        openFallback(context, fallback)
    } catch (e: SecurityException) {
        openFallback(context, fallback)
    }
}

private fun openFallback(context: Context, fallback: Intent?) {
    if (fallback == null) {
        Toast.makeText(context, "This settings screen is not available on this device. Open it manually.", Toast.LENGTH_LONG).show()
        return
    }
    try {
        context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Settings screen not available on this device.", Toast.LENGTH_LONG).show()
    }
}
