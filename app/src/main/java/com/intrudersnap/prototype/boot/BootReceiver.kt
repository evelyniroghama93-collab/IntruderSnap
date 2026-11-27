package com.intrudersnap.prototype.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.health.HealthCheckJobService
import com.intrudersnap.prototype.health.MonitoringChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Boot handling:
 *  - records a boot marker (diagnoses Xiaomi Autostart behaviour);
 *  - checks device-admin status and reconciles with Android's counter;
 *  - makes sure the periodic health check is scheduled.
 * Device-admin lock-screen callbacks are delivered independently of this receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val name = action.substringAfterLast('.')
                EventRepository.get(appContext).record(
                    type = EventType.BOOT_COMPLETED,
                    callbackReceived = true,
                    callbackName = name,
                    source = "BootReceiver",
                    note = "Boot broadcast delivered to IntruderSnap.",
                )
                MonitoringChecks.checkAdminStatus(appContext, name)
                MonitoringChecks.reconcile(appContext, name)
                HealthCheckJobService.schedule(appContext)
            } catch (e: Exception) {
                Log.e("IntruderSnap/Boot", "Boot handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
