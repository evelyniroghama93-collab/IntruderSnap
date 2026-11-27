package com.intrudersnap.prototype.health

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Periodic health check via the platform JobScheduler (no extra libraries).
 * Android and HyperOS decide when it actually runs; it may be delayed or skipped under
 * battery restrictions. The Health screen shows when it last ran.
 */
class HealthCheckJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        scope.launch {
            try {
                MonitoringChecks.runAll(appContext, "periodic job")
            } catch (e: Exception) {
                Log.e(TAG, "Health check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "IntruderSnap/HealthJob"
        private const val JOB_ID = 4201
        private const val INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val FLEX_MS = 60 * 60 * 1000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Idempotent: keeps an existing schedule. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, HealthCheckJobService::class.java))
                .setPeriodic(INTERVAL_MS, FLEX_MS)
                .setPersisted(true) // survives reboot (RECEIVE_BOOT_COMPLETED is declared)
                .build()
            val result = scheduler.schedule(job)
            Log.i(TAG, "Health check scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "failed"}")
        }
    }
}
