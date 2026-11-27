package com.intrudersnap.prototype.domain

import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SecurityEvent

/** Acceptance criteria from the specification (section 14). */
enum class AcceptanceItem(val title: String, val howToTest: String) {
    NATIVE_FAILED_CALLBACK(
        "Native failed-password callback",
        "Lock the phone and enter one wrong PIN / pattern / password on the real lock screen.",
    ),
    SYSTEM_COUNTER(
        "System attempt counter",
        "Enter 2+ wrong credentials; each event should show the system count 1, 2, 3...",
    ),
    SUCCESS_CALLBACK(
        "Successful authentication callback",
        "After at least one wrong attempt, unlock with the correct PIN / pattern / password (not biometrics).",
    ),
    THRESHOLD(
        "Threshold detection",
        "Enter wrong credentials until the configured threshold (3 for the first test) is reached.",
    ),
    PERSISTENCE(
        "Event persistence",
        "After recording callbacks, swipe IntruderSnap away in Recents (or adb shell am kill) and reopen it.",
    ),
    REBOOT(
        "Reboot behaviour",
        "Reboot, enter a wrong credential on the first lock screen, unlock, then open the app.",
    ),
    BACKGROUND_DELIVERY(
        "Xiaomi/HyperOS background delivery",
        "Close IntruderSnap fully (adb shell am kill com.intrudersnap.prototype, or swipe it away and wait 10+ min), " +
            "lock the phone, enter one wrong credential, unlock with the PIN, then open the app.",
    ),
    BACKGROUND_CAMERA(
        "Background camera feasibility",
        "Arm test E on the Camera tab, lock the phone, enter one wrong credential.",
    ),
}

data class AcceptanceResult(
    val item: AcceptanceItem,
    val outcome: TestOutcome,
    val evidence: String,
    val markedByTester: Boolean = false,
)

/**
 * Derives acceptance results ONLY from recorded evidence. Without evidence an item is
 * NOT TESTED; the tester can explicitly mark an item FAIL after performing the test.
 * PASS can never be set manually.
 */
object AcceptanceEvaluator {

    fun evaluate(
        events: List<SecurityEvent>,
        settings: AppSettings,
        currentSessionId: String,
    ): List<AcceptanceResult> = AcceptanceItem.entries.map { item ->
        val automatic = when (item) {
            AcceptanceItem.NATIVE_FAILED_CALLBACK -> nativeCallback(events)
            AcceptanceItem.SYSTEM_COUNTER -> systemCounter(events)
            AcceptanceItem.SUCCESS_CALLBACK -> successCallback(events)
            AcceptanceItem.THRESHOLD -> threshold(events)
            AcceptanceItem.PERSISTENCE -> persistence(events, currentSessionId)
            AcceptanceItem.REBOOT -> reboot(events)
            AcceptanceItem.BACKGROUND_DELIVERY -> backgroundDelivery(events)
            AcceptanceItem.BACKGROUND_CAMERA -> backgroundCamera(settings)
        }
        if (item.name in settings.manualFailures) {
            AcceptanceResult(item, TestOutcome.FAIL, "Marked FAIL by the tester. Automatic evidence: ${automatic.evidence}", markedByTester = true)
        } else {
            automatic
        }
    }

    private fun failed(events: List<SecurityEvent>) =
        events.filter { it.type == EventType.PASSWORD_FAILED && it.callbackReceived }

    private fun nativeCallback(events: List<SecurityEvent>): AcceptanceResult {
        val f = failed(events)
        return if (f.isNotEmpty()) {
            AcceptanceResult(AcceptanceItem.NATIVE_FAILED_CALLBACK, TestOutcome.PASS, "${f.size} onPasswordFailed callback(s) recorded from the system.")
        } else {
            AcceptanceResult(AcceptanceItem.NATIVE_FAILED_CALLBACK, TestOutcome.NOT_TESTED, "No onPasswordFailed callback recorded yet.")
        }
    }

    private fun systemCounter(events: List<SecurityEvent>): AcceptanceResult {
        val f = failed(events)
        if (f.isEmpty()) {
            return AcceptanceResult(AcceptanceItem.SYSTEM_COUNTER, TestOutcome.NOT_TESTED, "No failed-password events yet.")
        }
        val withCount = f.filter { it.systemFailedCount >= 1 }
        val errors = f.mapNotNull { it.systemCountError }.distinct()
        return when {
            withCount.isNotEmpty() -> AcceptanceResult(
                AcceptanceItem.SYSTEM_COUNTER,
                TestOutcome.PASS,
                "System counter read in ${withCount.size}/${f.size} failed events (max ${withCount.maxOf { it.systemFailedCount }}).",
            )
            errors.isNotEmpty() -> AcceptanceResult(AcceptanceItem.SYSTEM_COUNTER, TestOutcome.FAIL, "Counter unavailable: ${errors.first()}")
            else -> AcceptanceResult(AcceptanceItem.SYSTEM_COUNTER, TestOutcome.FAIL, "Counter read but returned 0 after a failed attempt.")
        }
    }

    private fun successCallback(events: List<SecurityEvent>): AcceptanceResult {
        val s = events.filter { it.type == EventType.PASSWORD_SUCCEEDED && it.callbackReceived }
        return if (s.isNotEmpty()) {
            AcceptanceResult(AcceptanceItem.SUCCESS_CALLBACK, TestOutcome.PASS, "${s.size} onPasswordSucceeded callback(s) recorded (last previous-failed count: ${s.first().previousFailedCount}).")
        } else {
            AcceptanceResult(AcceptanceItem.SUCCESS_CALLBACK, TestOutcome.NOT_TESTED, "No onPasswordSucceeded callback recorded yet.")
        }
    }

    private fun threshold(events: List<SecurityEvent>): AcceptanceResult {
        val intrusions = events.filter { it.type == EventType.INTRUSION_THRESHOLD_REACHED }
        if (intrusions.isNotEmpty()) {
            val last = intrusions.first()
            return AcceptanceResult(AcceptanceItem.THRESHOLD, TestOutcome.PASS, "${intrusions.size} intrusion event(s); last at threshold ${last.threshold} (system count ${last.systemFailedCount}).")
        }
        val reached = failed(events).any { ev ->
            val count = ThresholdPolicy.effectiveCount(ev.systemFailedCount.takeIf { it >= 0 }, ev.prototypeFailCount)
            ev.threshold > 0 && count >= ev.threshold
        }
        return if (reached) {
            AcceptanceResult(AcceptanceItem.THRESHOLD, TestOutcome.FAIL, "A failed event reached its threshold but no intrusion event was recorded.")
        } else {
            AcceptanceResult(AcceptanceItem.THRESHOLD, TestOutcome.NOT_TESTED, "Threshold not reached yet.")
        }
    }

    private fun persistence(events: List<SecurityEvent>, currentSessionId: String): AcceptanceResult {
        val callbacks = events.filter { it.type.isLockScreenCallback }
        if (callbacks.isEmpty()) {
            return AcceptanceResult(AcceptanceItem.PERSISTENCE, TestOutcome.NOT_TESTED, "No lock-screen callbacks recorded yet.")
        }
        val fromEarlierProcess = callbacks.filter { it.sessionId != currentSessionId }
        return if (fromEarlierProcess.isNotEmpty()) {
            AcceptanceResult(
                AcceptanceItem.PERSISTENCE,
                TestOutcome.PASS,
                "${fromEarlierProcess.size} callback event(s) written by an earlier app process were reloaded from device-protected storage.",
            )
        } else {
            AcceptanceResult(AcceptanceItem.PERSISTENCE, TestOutcome.NOT_TESTED, "All callbacks were written by the current process. Swipe the app away in Recents (or adb shell am kill) and reopen it to verify.")
        }
    }

    private fun reboot(events: List<SecurityEvent>): AcceptanceResult {
        val callbacks = events.filter { it.type.isLockScreenCallback }
        val boots = callbacks.map { it.bootCount }.filter { it >= 0 }.distinct()
        val directBoot = failed(events).count { !it.userUnlocked }
        val directNote = if (directBoot > 0) " $directBoot failed callback(s) were recorded before the first unlock (Direct Boot)." else ""

        if (boots.size >= 2) {
            val firstBoot = boots.minOrNull()!!
            val failedAfterReboot = failed(events).any { it.bootCount > firstBoot }
            if (failedAfterReboot) {
                return AcceptanceResult(AcceptanceItem.REBOOT, TestOutcome.PASS, "Callbacks recorded across ${boots.size} boots.$directNote")
            }
        }
        // Fallback when BOOT_COUNT is unavailable: a failed callback after a boot marker,
        // with callbacks also recorded before that marker.
        val bootMarkers = events.filter { it.type == EventType.BOOT_COMPLETED }
        if (boots.size < 2 && bootMarkers.isNotEmpty()) {
            val marker = bootMarkers.minByOrNull { it.timestampMillis }!!
            val before = callbacks.any { it.timestampMillis < marker.timestampMillis }
            val after = failed(events).any { it.timestampMillis > marker.timestampMillis }
            if (before && after) {
                return AcceptanceResult(AcceptanceItem.REBOOT, TestOutcome.PASS, "Failed callbacks recorded before and after a boot broadcast.$directNote")
            }
        }
        return AcceptanceResult(AcceptanceItem.REBOOT, TestOutcome.NOT_TESTED, "No failed callback recorded after a reboot yet.$directNote")
    }

    /**
     * PASS only when a failed callback was written by a process in which IntruderSnap's screen
     * had never been created - i.e. Android/HyperOS started (or kept) the process purely to
     * deliver the lock-screen broadcast. Callbacks received while the app's UI process was
     * alive (even if the phone was locked) do not count, because they do not show what
     * happens after HyperOS has ended the app.
     */
    private fun backgroundDelivery(events: List<SecurityEvent>): AcceptanceResult {
        val f = failed(events)
        if (f.isEmpty()) {
            return AcceptanceResult(AcceptanceItem.BACKGROUND_DELIVERY, TestOutcome.NOT_TESTED, "No failed-password events yet.")
        }
        val withDiagnostics = f.filter { it.hasDiagnostics }
        if (withDiagnostics.isEmpty()) {
            return AcceptanceResult(
                AcceptanceItem.BACKGROUND_DELIVERY,
                TestOutcome.NOT_TESTED,
                "Existing callbacks were recorded by a build without background diagnostics. Repeat with this build.",
            )
        }
        val cold = withDiagnostics.filter { !it.uiInProcess }
        return if (cold.isNotEmpty()) {
            val latest = cold.first()
            val coldStart = cold.count { it.processAgeMs in 0..10_000 }
            AcceptanceResult(
                AcceptanceItem.BACKGROUND_DELIVERY,
                TestOutcome.PASS,
                "${cold.size} failed callback(s) delivered while IntruderSnap was not open " +
                    "($coldStart in a process started within 10 s of the callback). " +
                    "Latest: process age ${latest.processAgeMs} ms, ${latest.backgroundState}.",
            )
        } else {
            AcceptanceResult(
                AcceptanceItem.BACKGROUND_DELIVERY,
                TestOutcome.NOT_TESTED,
                "All ${withDiagnostics.size} diagnosed callback(s) arrived while the app's screen process was alive. " +
                    "Close the app fully and repeat.",
            )
        }
    }

    private fun backgroundCamera(settings: AppSettings): AcceptanceResult {
        val e = settings.cameraResult(CameraTestKey.E_BACKGROUND_AFTER_CALLBACK)
        val evidence = e.detail.ifBlank { "Test E has not been run." }
        // Spec allows PASS, BLOCKED or NOT TESTED here; anything else is reported as NOT TESTED with detail.
        val outcome = when (e.outcome) {
            TestOutcome.PASS, TestOutcome.BLOCKED -> e.outcome
            else -> TestOutcome.NOT_TESTED
        }
        return AcceptanceResult(AcceptanceItem.BACKGROUND_CAMERA, outcome, evidence)
    }
}
