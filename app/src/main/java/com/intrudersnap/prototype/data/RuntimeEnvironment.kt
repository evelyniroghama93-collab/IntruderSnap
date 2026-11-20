package com.intrudersnap.prototype.data

import android.content.Context
import android.provider.Settings
import androidx.core.os.UserManagerCompat
import java.util.UUID

/**
 * Facts about the current process / boot that are attached to every event.
 * They make persistence and reboot behaviour verifiable from the history alone.
 */
object RuntimeEnvironment {

    /** Random ID generated once per app process. A new process gets a new ID. */
    val processSessionId: String = UUID.randomUUID().toString().substring(0, 8)

    /** Settings.Global.BOOT_COUNT increases by one on every boot; -1 if unavailable. */
    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    /** False while the device is in Direct Boot (before the first unlock after reboot). */
    fun isUserUnlocked(context: Context): Boolean =
        UserManagerCompat.isUserUnlocked(context)
}
