package com.intrudersnap.prototype.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.intrudersnap.prototype.R
import com.intrudersnap.prototype.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {

    const val CHANNEL_ALERTS = "intrusion_alerts"
    const val CHANNEL_CAMERA = "camera_feasibility"
    const val ID_INTRUSION = 1001
    const val ID_CAMERA_SERVICE = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Intrusion alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown when the failed-unlock threshold is reached."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAMERA, "Camera feasibility tests", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible notification while a camera feasibility test is running."
            }
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Posts the threshold alert. Returns a short status string that is stored in the
     * intrusion event so the history shows whether the notification was actually posted.
     */
    @SuppressLint("MissingPermission") // checked by canPostNotifications()
    fun showIntrusion(context: Context, effectiveCount: Int, threshold: Int, timestampMillis: Long): String {
        if (!canPostNotifications(context)) {
            return "Notification NOT posted: notification permission not granted or notifications disabled"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMillis))
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("IntruderSnap security alert")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Possible intrusion detected")
            .setContentText("$effectiveCount failed unlock attempts (threshold $threshold) at $time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(ID_INTRUSION, notification)
            "Notification posted"
        } catch (e: Exception) {
            "Notification NOT posted: ${e.javaClass.simpleName}"
        }
    }

    /** Ongoing, visible notification for the camera foreground service. */
    fun cameraServiceNotification(context: Context, backgroundExperiment: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_CAMERA)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(if (backgroundExperiment) "IntruderSnap camera experiment (test E)" else "IntruderSnap camera test (test C)")
            .setContentText("Camera feasibility test in progress. One frame is measured and discarded; no photo is saved.")
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()
}
