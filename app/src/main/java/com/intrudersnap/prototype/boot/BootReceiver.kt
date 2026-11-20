package com.intrudersnap.prototype.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records that a boot broadcast reached the app. It does not start anything.
 *
 * Purpose: on Xiaomi devices, Autostart restrictions can stop boot broadcasts from being
 * delivered. Seeing (or not seeing) these markers helps interpret Test 14 (reboot).
 * Device admin callbacks are delivered independently of this receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                EventRepository.get(appContext).record(
                    type = EventType.BOOT_COMPLETED,
                    callbackReceived = true,
                    callbackName = action.substringAfterLast('.'),
                    note = "Boot broadcast delivered to IntruderSnap.",
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
