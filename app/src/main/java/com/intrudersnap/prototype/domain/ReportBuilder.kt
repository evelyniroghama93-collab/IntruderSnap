package com.intrudersnap.prototype.domain

import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.device.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a plain-text report the tester can share and paste into docs/COMPATIBILITY_RESULTS.md. */
object ReportBuilder {

    private fun fmt(ms: Long): String =
        if (ms <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    fun build(
        device: DeviceInfo,
        adminActive: Boolean,
        watchLoginGranted: Boolean,
        settings: AppSettings,
        acceptance: List<AcceptanceResult>,
        events: List<SecurityEvent>,
    ): String = buildString {
        appendLine("IntruderSnap - Milestone 1 feasibility report")
        appendLine("Generated: ${fmt(System.currentTimeMillis())}")
        appendLine("Environment: PHYSICAL DEVICE or EMULATOR -> (tester: state which)")
        appendLine()
        appendLine("== Device ==")
        appendLine("Manufacturer: ${device.manufacturer} / Brand: ${device.brand}")
        appendLine("Model: ${device.displayModel} (device ${device.device})")
        appendLine("Android: ${device.androidVersion} (API ${device.apiLevel})")
        appendLine("ROM: ${device.romLabel}")
        appendLine("Build: ${device.buildDisplay} / incremental ${device.buildIncremental}")
        appendLine("Security patch: ${device.securityPatch}")
        appendLine("Device admin active: $adminActive / watch-login granted: $watchLoginGranted")
        appendLine("Threshold: ${settings.threshold} (${settings.thresholdMode.label})")
        appendLine()
        appendLine("== Acceptance results ==")
        acceptance.forEach { r ->
            appendLine("${r.item.title}: ${r.outcome.label}${if (r.markedByTester) " (tester)" else ""}")
            appendLine("   ${r.evidence}")
        }
        appendLine()
        appendLine("== Camera feasibility ==")
        CameraTestKey.entries.forEach { k ->
            val res = settings.cameraResult(k)
            appendLine("${k.code}. ${k.title}: ${res.outcome.label}")
            if (res.detail.isNotBlank()) appendLine("   ${res.detail}")
        }
        appendLine()
        appendLine("== Event history (newest first, max 100) ==")
        events.take(100).forEach { e ->
            appendLine(
                "#${e.id} ${e.type.name} ${fmt(e.timestampMillis)} sys=${e.systemFailedCount} " +
                    "proto=${e.prototypeFailCount} prev=${e.previousFailedCount} thr=${e.threshold} " +
                    "callback=${e.callbackReceived} unlocked=${e.userUnlocked} boot=${e.bootCount} " +
                    "proc=${e.sessionId} model=${e.deviceModel}" +
                    (e.systemCountError?.let { " err=$it" } ?: "") +
                    (if (e.note.isNotBlank()) " note=${e.note}" else "")
            )
        }
    }
}
