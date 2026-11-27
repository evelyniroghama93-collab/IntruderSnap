package com.intrudersnap.prototype.health

import android.content.Context
import android.util.Log
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.admin.LoginEventProcessor
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.domain.IncidentTracker
import com.intrudersnap.prototype.domain.RetentionPolicy
import com.intrudersnap.prototype.notify.Notifier

/**
 * Checks that run whenever IntruderSnap gets a chance to execute: app open, boot, and the
 * periodic JobScheduler job. Android/HyperOS decide when (and whether) these run, so the
 * Health screen always shows when each check last ran.
 *
 * Blocking I/O: call off the main thread.
 */
object MonitoringChecks {

    private const val TAG = "IntruderSnap/Health"

    fun runAll(context: Context, trigger: String) {
        checkAdminStatus(context, trigger)
        reconcile(context, trigger)
        applyRetention(context)
    }

    /**
     * Detects that device-admin access was removed or became unavailable since the last check.
     * onDisabled() normally reports this immediately; this catches cases where it was missed.
     */
    fun checkAdminStatus(context: Context, trigger: String) {
        val settings = SettingsRepository.get(context)
        settings.reload()
        val now = System.currentTimeMillis()
        val active = AdminController.isAdminActive(context)
        val wasActive = settings.current.adminWasActive
        if (wasActive && !active) {
            EventRepository.get(context).record(
                type = EventType.ADMIN_LOST,
                source = "AdminStatusCheck($trigger)",
                note = "Device administrator is no longer active. Lock-screen monitoring has stopped.",
            )
            Notifier.showAdminLost(context, detectedBy = trigger)
            Log.w(TAG, "Device admin found inactive (trigger=$trigger)")
        }
        settings.setAdminState(active, now)
    }

    /** Compares the stored incident with Android's counter; records and alerts on discrepancies. */
    fun reconcile(context: Context, trigger: String) {
        val settings = SettingsRepository.get(context)
        settings.reload()
        val now = System.currentTimeMillis()
        val system = AdminController.readSystemFailedAttempts(context)
        val threshold = settings.current.threshold

        val r = settings.updateIncident { s ->
            val rec = IncidentTracker.reconcile(s.incident, system.countOrNull, threshold, now)
            rec.state to rec
        }
        val missed = r.result == IncidentTracker.ReconciliationResult.MISSED_FAILURES
        settings.setReconciliation(now, r.result.name, missedDetected = missed)

        if (r.result != IncidentTracker.ReconciliationResult.CONSISTENT &&
            r.result != IncidentTracker.ReconciliationResult.UNAVAILABLE
        ) {
            EventRepository.get(context).record(
                type = EventType.RECONCILIATION,
                systemFailedCount = system.countOrMinusOne,
                systemCountError = system.errorOrNull,
                threshold = threshold,
                source = "Reconciler($trigger)",
                classification = r.result.name,
                incidentCount = r.incidentCount,
                note = r.result.label + if (missed) ": ${r.missedFailures} failed attempt(s)." else ".",
            )
        }
        if (r.trigger) {
            LoginEventProcessor.raiseIntrusion(context, r.incidentCount, threshold, system.countOrMinusOne, "Reconciler($trigger)", now)
        }
        Log.i(TAG, "Reconciliation ($trigger): ${r.result.name} missed=${r.missedFailures}")
    }

    fun applyRetention(context: Context) {
        val days = SettingsRepository.get(context).current.retentionDays
        EventRepository.get(context).purgeOlderThan(RetentionPolicy.cutoff(System.currentTimeMillis(), days), days)
    }
}
