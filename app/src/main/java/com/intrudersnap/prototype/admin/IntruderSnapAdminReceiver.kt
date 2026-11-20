package com.intrudersnap.prototype.admin

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import com.intrudersnap.prototype.R
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Device administrator receiver using ONLY the watch-login policy.
 *
 * Callback signatures (API 34):
 *  - onPasswordFailed(Context, Intent, UserHandle)      added in API 26
 *  - onPasswordSucceeded(Context, Intent, UserHandle)   added in API 26
 *
 * Deprecated signatures (deprecated in API 26, still invoked by default):
 *  - onPasswordFailed(Context, Intent)
 *  - onPasswordSucceeded(Context, Intent)
 * The framework's default 3-argument implementation calls the 2-argument one, so
 * overriding only the 3-argument versions (as below) is correct and avoids double counting.
 *
 * Behaviour notes:
 *  - onPasswordFailed fires for incorrect PIN / pattern / password on the lock screen.
 *    Fingerprint / face failures are NOT password failures and are not reported here.
 *  - onPasswordSucceeded is only sent after a successful credential entry that follows one
 *    or more failed attempts. It does NOT fire on every unlock.
 *
 * Work is done in goAsync() on Dispatchers.IO so that file writes do not block the main
 * thread and the process is kept alive until the record is persisted.
 */
class IntruderSnapAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val appContext = context.applicationContext
        runAsync { LoginEventProcessor.onPasswordFailed(appContext, user) }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        val appContext = context.applicationContext
        runAsync { LoginEventProcessor.onPasswordSucceeded(appContext, user) }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        runAsync {
            EventRepository.get(appContext).record(
                type = EventType.ADMIN_ENABLED,
                callbackReceived = true,
                callbackName = "onEnabled(Context, Intent)",
                note = "Device administrator activated through the system screen (watch-login only).",
            )
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val appContext = context.applicationContext
        runAsync {
            EventRepository.get(appContext).record(
                type = EventType.ADMIN_DISABLE_REQUESTED,
                callbackReceived = true,
                callbackName = "onDisableRequested(Context, Intent)",
            )
        }
        return context.getString(R.string.device_admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        runAsync {
            EventRepository.get(appContext).record(
                type = EventType.ADMIN_DISABLED,
                callbackReceived = true,
                callbackName = "onDisabled(Context, Intent)",
                note = "Monitoring stopped. The app can now be uninstalled.",
            )
        }
    }

    private fun runAsync(block: () -> Unit) {
        val pending: BroadcastReceiver.PendingResult = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Error while handling admin callback", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "IntruderSnap/Admin"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
