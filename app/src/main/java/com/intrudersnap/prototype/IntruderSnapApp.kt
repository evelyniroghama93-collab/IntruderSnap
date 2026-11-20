package com.intrudersnap.prototype

import android.app.Application
import com.intrudersnap.prototype.notify.Notifier

/**
 * Application class. Runs in Direct Boot too (because the receivers are directBootAware),
 * so it must not touch credential-encrypted storage. It only creates notification channels.
 */
class IntruderSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
