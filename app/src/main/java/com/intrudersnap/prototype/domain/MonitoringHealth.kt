package com.intrudersnap.prototype.domain

/** Honest monitoring-confidence states required for Milestone 2. */
enum class Confidence(val label: String) {
    VERIFIED("Verified"),
    ACTIVE_UNVERIFIED("Active but delivery unverified"),
    PERMISSION_REQUIRED("Permission required"),
    RESTRICTED("Restricted"),
    NOT_TESTED("Not tested"),
}

/** Inputs gathered from Android and from the app's own records. Pure data. */
data class HealthInputs(
    val adminActive: Boolean,
    val watchLoginGranted: Boolean,
    val deviceSecure: Boolean,
    val backgroundRestricted: Boolean?,
    val standbyBucketRestricted: Boolean?,
    val callbacksEverReceived: Int,
    /** Time of the latest callback delivered while IntruderSnap was NOT open (0 = never). */
    val lastColdDeliveryAt: Long,
    /** Time missed failures were last detected by reconciliation (0 = never). */
    val lastMissedDetectedAt: Long,
    val now: Long,
    /** Non-null when IntruderSnap could not save or read its records. */
    val storageError: String? = null,
)

data class HealthAssessment(val confidence: Confidence, val reasons: List<String>)

object MonitoringHealth {

    /** Background-delivery evidence older than this no longer counts as "Verified". */
    const val VERIFICATION_VALIDITY_MS = 30L * 24 * 60 * 60 * 1000

    fun assess(i: HealthInputs): HealthAssessment {
        val reasons = mutableListOf<String>()

        if (!i.deviceSecure) reasons += "No secure screen lock (PIN, pattern or password) is set."
        if (!i.adminActive) reasons += "Device administrator is not active."
        else if (!i.watchLoginGranted) reasons += "Watch-login policy is not granted."
        if (reasons.isNotEmpty()) return HealthAssessment(Confidence.PERMISSION_REQUIRED, reasons)

        i.storageError?.let { reasons += "Records are not being saved reliably: $it" }
        if (i.backgroundRestricted == true) reasons += "Android reports IntruderSnap as background-restricted."
        if (i.standbyBucketRestricted == true) reasons += "IntruderSnap is in the RESTRICTED app-standby bucket."
        val missedAfterVerification = i.lastMissedDetectedAt > 0 && i.lastMissedDetectedAt >= i.lastColdDeliveryAt
        if (missedAfterVerification) reasons += "Reconciliation found failed attempts that were never delivered to IntruderSnap."
        if (reasons.isNotEmpty()) return HealthAssessment(Confidence.RESTRICTED, reasons)

        if (i.callbacksEverReceived == 0) {
            return HealthAssessment(
                Confidence.NOT_TESTED,
                listOf("No lock-screen callback has been received yet. Run the self-test in Setup."),
            )
        }

        val verifiedRecently = i.lastColdDeliveryAt > 0 && i.now - i.lastColdDeliveryAt <= VERIFICATION_VALIDITY_MS
        return if (verifiedRecently) {
            HealthAssessment(
                Confidence.VERIFIED,
                listOf("A lock-screen callback reached IntruderSnap while the app was closed, within the last 30 days."),
            )
        } else {
            HealthAssessment(
                Confidence.ACTIVE_UNVERIFIED,
                listOf(
                    if (i.lastColdDeliveryAt == 0L) {
                        "Callbacks have only been observed while IntruderSnap was running. Run the closed-app delivery test."
                    } else {
                        "The last closed-app delivery test is older than 30 days. Repeat it."
                    },
                ),
            )
        }
    }
}
