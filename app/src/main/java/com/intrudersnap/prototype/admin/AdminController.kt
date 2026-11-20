package com.intrudersnap.prototype.admin

import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.intrudersnap.prototype.R

/** Result of reading DevicePolicyManager.getCurrentFailedPasswordAttempts(). */
sealed class SystemFailedCount {
    data class Value(val count: Int) : SystemFailedCount()
    data class Unavailable(val reason: String) : SystemFailedCount()

    val countOrNull: Int? get() = (this as? Value)?.count
    val countOrMinusOne: Int get() = countOrNull ?: -1
    val errorOrNull: String? get() = (this as? Unavailable)?.reason
}

/** Thin wrapper around DevicePolicyManager for the watch-login policy. */
object AdminController {

    fun component(context: Context): ComponentName =
        ComponentName(context, IntruderSnapAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isAdminActive(context: Context): Boolean = try {
        dpm(context).isAdminActive(component(context))
    } catch (e: Exception) {
        false
    }

    /** True only when the admin is active AND the watch-login policy was granted. */
    fun isWatchLoginGranted(context: Context): Boolean = try {
        isAdminActive(context) &&
            dpm(context).hasGrantedPolicy(component(context), DeviceAdminInfo.USES_POLICY_WATCH_LOGIN)
    } catch (e: SecurityException) {
        false
    }

    /**
     * Opens Android's OFFICIAL device-admin activation screen. The owner must confirm there;
     * the app cannot activate itself.
     */
    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_explanation))

    /**
     * An app may always remove its own admin. This must be done before uninstalling:
     * Android blocks uninstalling an app while it is an active device administrator.
     */
    fun deactivate(context: Context) {
        try {
            dpm(context).removeActiveAdmin(component(context))
        } catch (e: SecurityException) {
            // Not active; nothing to do.
        }
    }

    /**
     * Reads the system failed-attempt counter for the current user.
     * Requires an active admin with USES_POLICY_WATCH_LOGIN, otherwise SecurityException.
     */
    fun readSystemFailedAttempts(context: Context): SystemFailedCount = try {
        SystemFailedCount.Value(dpm(context).currentFailedPasswordAttempts)
    } catch (e: SecurityException) {
        SystemFailedCount.Unavailable("SecurityException: ${e.message ?: "watch-login not granted"}")
    } catch (e: Exception) {
        SystemFailedCount.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }
}
