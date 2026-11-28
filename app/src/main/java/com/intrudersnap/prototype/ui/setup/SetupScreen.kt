package com.intrudersnap.prototype.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.ManualCheck
import com.intrudersnap.prototype.ui.components.Pill
import com.intrudersnap.prototype.ui.components.SectionCard
import com.intrudersnap.prototype.ui.components.formatTime

private enum class StepState(val label: String, val color: Color) {
    DONE("Done", Color(0xFF1E8E3E)),
    ACTION_NEEDED("Action needed", Color(0xFFC5221F)),
    CONFIRMED("Confirmed by you", Color(0xFF0E8C82)),
    CHECK_MANUALLY("Check manually", Color(0xFFB06000)),
    NOT_TESTED("Not tested", Color(0xFF5F6368)),
    VERIFIED("Verified", Color(0xFF1E8E3E)),
}

@Composable
fun SetupScreen(vm: SetupViewModel = viewModel()) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val selfTestStart by vm.selfTestStartedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    val isXiaomi = snap.device?.isXiaomiFamily == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (isXiaomi) "Guided setup for ${snap.device?.displayModel} · ${snap.device?.romLabel}" else "Guided setup",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "These steps can improve how reliably Android and HyperOS deliver lock-screen events to IntruderSnap. " +
                "They do not guarantee delivery. None of them weakens your phone's security.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Step("1. Screen lock", if (snap.deviceSecure) StepState.DONE else StepState.ACTION_NEEDED,
            "A PIN, pattern or password is required. Failed attempts on it are what IntruderSnap records.") {
            if (!snap.deviceSecure) Shortcut("Open security settings") { open(context, vm.securitySettingsIntent(), null) }
        }

        Step("2. Device administrator", if (snap.adminActive) StepState.DONE else StepState.ACTION_NEEDED,
            "Grants only the watch-login policy: IntruderSnap is told about failed and successful unlock attempts. " +
                "It cannot wipe, lock or change your password. Android's activation screen uses a fixed description " +
                "for this policy that also mentions locking or erasing the phone; IntruderSnap does not have those " +
                "powers. Deactivate it before uninstalling.") {
            if (!snap.adminActive) {
                Button(onClick = { adminLauncher.launch(AdminController.activationIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Activate on the system screen")
                }
            }
        }

        Step("3. Notifications", if (snap.notificationsAllowed) StepState.DONE else StepState.ACTION_NEEDED,
            "Needed for intrusion and \"monitoring stopped\" alerts. Also allow lock-screen notifications if you want alerts there.") {
            if (!snap.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow notifications")
                }
            }
            Shortcut("Open notification settings") { open(context, vm.notificationSettingsIntent(context), vm.appDetailsIntent(context)) }
            ManualToggle(ManualCheck.LOCK_SCREEN_NOTIFICATIONS, settings.manualChecks, vm::setManualCheck)
        }

        val p = snap.power
        val batteryState = when {
            p?.backgroundRestricted == true || p?.standbyBucketRestricted == true -> StepState.ACTION_NEEDED
            ManualCheck.BATTERY_SAVER in settings.manualChecks -> StepState.CONFIRMED
            else -> StepState.CHECK_MANUALLY
        }
        Step("4. Battery restrictions", batteryState,
            "Android reports: background restricted = ${p?.backgroundRestricted ?: "unknown"}, standby bucket = ${p?.standbyBucket ?: "unknown"}, " +
                "battery optimization ${if (p?.ignoringBatteryOptimizations == true) "off" else "on (default)"}. " +
                "On HyperOS: App info › Battery saver › No restrictions. HyperOS's own setting cannot be read by apps.") {
            Shortcut("Open App info") { open(context, vm.appDetailsIntent(context), null) }
            if (isXiaomi) Shortcut("Open HyperOS battery saver for IntruderSnap") { open(context, vm.xiaomiBatterySaverIntent(context), vm.appDetailsIntent(context)) }
            Shortcut("Open Android battery optimization list") { open(context, vm.batteryOptimizationIntent(), vm.appDetailsIntent(context)) }
            ManualToggle(ManualCheck.BATTERY_SAVER, settings.manualChecks, vm::setManualCheck)
        }

        Step("5. Autostart", if (ManualCheck.AUTOSTART in settings.manualChecks) StepState.CONFIRMED else StepState.CHECK_MANUALLY,
            "HyperOS can block apps from starting in the background, including after a reboot. Allow Autostart for IntruderSnap " +
                "(Security app › Permissions › Autostart). Apps cannot read this setting, so confirm it here.") {
            Shortcut("Open Autostart settings") { open(context, vm.xiaomiAutostartIntent(), vm.appDetailsIntent(context)) }
            ManualToggle(ManualCheck.AUTOSTART, settings.manualChecks, vm::setManualCheck)
        }

        Step("6. Background app restrictions", if (ManualCheck.LOCKED_IN_RECENTS in settings.manualChecks) StepState.CONFIRMED else StepState.CHECK_MANUALLY,
            "Optional: open Recents, long-press IntruderSnap and tap the lock icon so HyperOS's cleaner does not close it. " +
                "\"Clear all\" in Recents and memory cleaners can still stop it.") {
            ManualToggle(ManualCheck.LOCKED_IN_RECENTS, settings.manualChecks, vm::setManualCheck)
        }

        val selfTestEvent = if (selfTestStart > 0) {
            events.firstOrNull { it.type == EventType.PASSWORD_FAILED && it.callbackReceived && it.timestampMillis >= selfTestStart }
        } else {
            null
        }
        Step("7. Self-test: one wrong PIN", when {
            selfTestEvent != null -> StepState.VERIFIED
            else -> StepState.NOT_TESTED
        }, "Tap Start, lock the phone, enter ONE wrong PIN, then unlock with your correct PIN and return here.") {
            if (selfTestEvent != null) {
                Text("Callback received at ${formatTime(selfTestEvent.timestampMillis)} (Android's count ${selfTestEvent.systemFailedCount}).")
            } else if (selfTestStart > 0) {
                Text("Waiting for a failed attempt after ${formatTime(selfTestStart)}…")
            }
            OutlinedButton(onClick = vm::startSelfTest, modifier = Modifier.fillMaxWidth()) {
                Text(if (selfTestStart > 0) "Restart self-test" else "Start self-test")
            }
        }

        Step("8. Closed-app delivery test (long idle)", if (settings.lastColdDeliveryAt > 0) StepState.VERIFIED else StepState.NOT_TESTED,
            "Close IntruderSnap fully (swipe it away in Recents) and leave the phone locked and idle for at least 2 hours " +
                "(overnight is better). Then enter ONE wrong PIN and unlock with your correct PIN. Open IntruderSnap: the " +
                "Health screen shows whether the callback arrived while the app was closed.") {
            if (settings.lastColdDeliveryAt > 0) {
                Text("Last callback received while IntruderSnap was closed: ${formatTime(settings.lastColdDeliveryAt)}")
            }
        }
    }
}

@Composable
private fun Step(title: String, state: StepState, explanation: String, actions: @Composable () -> Unit) {
    SectionCard(title) {
        Pill(state.label, state.color)
        Text(explanation, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}

@Composable
private fun Shortcut(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun ManualToggle(check: ManualCheck, confirmed: Set<ManualCheck>, onChange: (ManualCheck, Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = check in confirmed, onCheckedChange = { onChange(check, it) })
        Text("I have checked: ${check.label}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun open(context: Context, intent: Intent, fallback: Intent?) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
