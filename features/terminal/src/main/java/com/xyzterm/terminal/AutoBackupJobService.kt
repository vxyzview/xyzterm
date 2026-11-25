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
            runCatching { TerminalBackup.autoBackup() }
            if (params != null) jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 20261125

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job =
                JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setOverrideDeadline(60_000L)
                    .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}
