package com.intrudersnap.prototype.domain

/**
 * Pure threshold logic, kept free of Android types so it can be unit tested.
 *
 * Documented incident logic:
 *  1. Every onPasswordFailed callback increments the prototype's own counter.
 *  2. The count used for the threshold is the SYSTEM count from
 *     DevicePolicyManager.getCurrentFailedPasswordAttempts() when it can be read,
 *     otherwise the prototype's own counter.
 *  3. An intrusion is recorded ONCE per incident, the first time the effective count
 *     reaches or exceeds the threshold.
 *  4. The incident (prototype counter + "already triggered" flag) is reset when
 *     onPasswordSucceeded is received.
 *  5. Nothing here influences Android's own lockout behaviour.
 */
object ThresholdPolicy {

    const val DEFAULT_THRESHOLD = 3
    const val MIN_CUSTOM_THRESHOLD = 1
    const val MAX_CUSTOM_THRESHOLD = 30

    /** @param systemCount system-reported count, or null if it could not be read. */
    fun effectiveCount(systemCount: Int?, prototypeCount: Int): Int =
        if (systemCount != null && systemCount >= 0) systemCount else prototypeCount

    fun shouldTrigger(effectiveCount: Int, threshold: Int, alreadyTriggered: Boolean): Boolean =
        !alreadyTriggered && threshold > 0 && effectiveCount >= threshold

    fun isValidCustomThreshold(value: Int): Boolean =
        value in MIN_CUSTOM_THRESHOLD..MAX_CUSTOM_THRESHOLD
}
