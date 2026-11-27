package com.intrudersnap.prototype

import android.app.Application
import com.intrudersnap.prototype.health.HealthCheckJobService
import com.intrudersnap.prototype.notify.Notifier

/**
 * Application class. Also runs in Direct Boot (the receivers are directBootAware), so it
 * only touches device-protected state: notification channels and the job schedule.
 */
class IntruderSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        try {
            HealthCheckJobService.schedule(this)
        } catch (e: Exception) {
            // Scheduling is retried at boot and on every app open.
        }
    }
}
