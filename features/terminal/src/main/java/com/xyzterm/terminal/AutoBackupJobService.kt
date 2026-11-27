package com.xyzterm.terminal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.xyzterm.DefaultScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Durable auto-backup. A coroutine started from Service.onDestroy() dies with the process; a scheduled job survives it
 * and is retried by the OS if the app is killed mid-run.
 */
@OptIn(DelicateCoroutinesApi::class)
class AutoBackupJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        DefaultScope.launch(Dispatchers.IO) {
            // Swapping the rootfs or tar-ing a live tree produces torn backups.
            if (SessionService.instance?.hasActiveWork() == true) {
                if (params != null) jobFinished(params, true)
                return@launch
            }
            val ok = runCatching { TerminalBackup.autoBackup() }.getOrDefault(false)
            if (params != null) jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 20261125

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // Service teardown must not restart the six-hour period on every exit.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job =
                JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .build()
            runCatching { scheduler.schedule(job) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JOB_ID) }
        }
    }
}
