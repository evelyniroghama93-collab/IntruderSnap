package com.intrudersnap.prototype.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringHealthTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun inputs(
        adminActive: Boolean = true,
        watchLogin: Boolean = true,
        secure: Boolean = true,
        bgRestricted: Boolean? = false,
        bucketRestricted: Boolean? = false,
        callbacks: Int = 5,
        lastCold: Long = now - day,
        lastMissed: Long = 0L,
    ) = HealthInputs(adminActive, watchLogin, secure, bgRestricted, bucketRestricted, callbacks, lastCold, lastMissed, now)

    @Test fun verifiedWhenColdDeliveryRecent() =
        assertEquals(Confidence.VERIFIED, MonitoringHealth.assess(inputs()).confidence)

    @Test fun permissionRequiredWhenAdminInactive() =
        assertEquals(Confidence.PERMISSION_REQUIRED, MonitoringHealth.assess(inputs(adminActive = false)).confidence)

    @Test fun permissionRequiredWithoutSecureLock() =
        assertEquals(Confidence.PERMISSION_REQUIRED, MonitoringHealth.assess(inputs(secure = false)).confidence)

    @Test fun permissionRequiredWhenWatchLoginMissing() =
        assertEquals(Confidence.PERMISSION_REQUIRED, MonitoringHealth.assess(inputs(watchLogin = false)).confidence)

    @Test fun restrictedWhenBackgroundRestricted() =
        assertEquals(Confidence.RESTRICTED, MonitoringHealth.assess(inputs(bgRestricted = true)).confidence)

    @Test fun restrictedWhenRestrictedBucket() =
        assertEquals(Confidence.RESTRICTED, MonitoringHealth.assess(inputs(bucketRestricted = true)).confidence)

    @Test fun restrictedWhenMissedFailuresDetectedAfterVerification() =
        assertEquals(Confidence.RESTRICTED, MonitoringHealth.assess(inputs(lastMissed = now - 1000)).confidence)

    @Test fun missedFailuresBeforeLaterVerificationDoNotRestrict() =
        assertEquals(Confidence.VERIFIED, MonitoringHealth.assess(inputs(lastMissed = now - 2 * day)).confidence)

    @Test fun notTestedWithoutAnyCallback() =
        assertEquals(Confidence.NOT_TESTED, MonitoringHealth.assess(inputs(callbacks = 0, lastCold = 0)).confidence)

    @Test fun activeUnverifiedWithoutColdDelivery() =
        assertEquals(Confidence.ACTIVE_UNVERIFIED, MonitoringHealth.assess(inputs(lastCold = 0)).confidence)

    @Test fun activeUnverifiedWhenVerificationIsOld() =
        assertEquals(Confidence.ACTIVE_UNVERIFIED, MonitoringHealth.assess(inputs(lastCold = now - 31 * day)).confidence)

    @Test fun unknownRestrictionStateDoesNotClaimRestricted() =
        assertEquals(Confidence.VERIFIED, MonitoringHealth.assess(inputs(bgRestricted = null, bucketRestricted = null)).confidence)
}
