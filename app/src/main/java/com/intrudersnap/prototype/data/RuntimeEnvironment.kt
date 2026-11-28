package com.intrudersnap.prototype.data

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.os.UserManagerCompat
import java.util.UUID

/**
 * Facts about the current process / boot that are attached to every event.
 * They make persistence, reboot and background-delivery behaviour verifiable from
 * the history alone.
 */
object RuntimeEnvironment {

    /** Random ID generated once per app process. A new process gets a new ID. */
    val processSessionId: String = UUID.randomUUID().toString().substring(0, 8)

    /**
     * Set by MainActivity.onCreate. Stays false in a process that the system started
     * only to deliver a broadcast (e.g. a lock-screen callback while the app was closed).
     */
    @Volatile
    var uiCreatedInProcess: Boolean = false

    /** True between MainActivity.onStart and onStop. */
    @Volatile
    var uiVisible: Boolean = false

    /** elapsedRealtime when MainActivity last stopped being visible (-1 = never in this process). */
    @Volatile
    var lastUiStoppedElapsed: Long = -1L

    /**
     * Milliseconds since IntruderSnap's screen was last visible in this process:
     * 0 if visible now, null if it was never shown in this process.
     */
    fun msSinceUiVisible(): Long? = when {
        uiVisible -> 0L
        lastUiStoppedElapsed < 0 -> null
        else -> SystemClock.elapsedRealtime() - lastUiStoppedElapsed
    }

    fun isDeviceLocked(context: Context): Boolean? = try {
        context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    } catch (e: Exception) {
        null
    }

    /** Context of a background-camera attempt, so the result can be judged correctly. */
    fun triggerContext(context: Context): String {
        val since = msSinceUiVisible()
        val sinceText = when (since) {
            null -> "app screen never shown in this process"
            0L -> "app screen VISIBLE"
            else -> "app screen last visible $since ms earlier"
        }
        return "deviceLocked=${isDeviceLocked(context) ?: "unknown"}, $sinceText, processAge=${processAgeMs()} ms"
    }

    /** Settings.Global.BOOT_COUNT increases by one on every boot; -1 if unavailable. */
    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    /** False while the device is in Direct Boot (before the first unlock after reboot). */
    fun isUserUnlocked(context: Context): Boolean =
        UserManagerCompat.isUserUnlocked(context)

    /** Milliseconds since this app process was started by the system. */
    fun processAgeMs(): Long =
        SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    data class PowerState(
        val standbyBucket: String,
        val standbyBucketRestricted: Boolean?,
        val backgroundRestricted: Boolean?,
        val ignoringBatteryOptimizations: Boolean?,
    )

    /** Power-management state readable through public APIs (own app only). */
    fun powerState(context: Context): PowerState {
        val bucket = try {
            context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        } catch (e: Exception) {
            null
        }
        return PowerState(
            standbyBucket = bucket?.let { bucketName(it) } ?: "unknown",
            standbyBucketRestricted = bucket?.let { it == UsageStatsManager.STANDBY_BUCKET_RESTRICTED },
            backgroundRestricted = try {
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            } catch (e: Exception) {
                null
            },
            ignoringBatteryOptimizations = try {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                null
            },
        )
    }

    /** Wall-clock time of the current boot. */
    fun lastBootAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * One-line snapshot of the power-management state that can affect delivery on
     * Xiaomi devices. Uses only public APIs and reads only this app's own state.
     */
    fun backgroundState(context: Context): String {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bucket = try {
                bucketName(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            } catch (e: Exception) {
                "unknown"
            }
            parts += "standbyBucket=$bucket"
            val restricted = try {
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted.toString()
            } catch (e: Exception) {
                "unknown"
            }
            parts += "backgroundRestricted=$restricted"
        }
        val ignoringOptimizations = try {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName).toString()
        } catch (e: Exception) {
            "unknown"
        }
        parts += "ignoringBatteryOptimizations=$ignoringOptimizations"
        return parts.joinToString(", ")
    }

    private fun bucketName(bucket: Int): String = when (bucket) {
        // 5 = STANDBY_BUCKET_EXEMPTED (hidden constant). Android puts apps here that are exempt
        // from app-standby limits, e.g. an active device administrator.
        5 -> "EXEMPTED"
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "OTHER($bucket)"
    }
}
