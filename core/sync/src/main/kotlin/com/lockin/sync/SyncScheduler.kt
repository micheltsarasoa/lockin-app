package com.lockin.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the [BlocklistSyncWorker] to run weekly on Wi-Fi.
 *
 * Uses [ExistingPeriodicWorkPolicy.KEEP] — if work is already enqueued, it
 * is not replaced. This means calling [scheduleWeeklySync] multiple times
 * (e.g., on every boot) is safe and idempotent.
 *
 * Also exposes [triggerImmediateSync] for manual sync from the parent dashboard.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Enqueues a weekly sync job if one is not already scheduled.
     * Should be called once during app setup and on every boot.
     */
    fun scheduleWeeklySync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)  // Wi-Fi only
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            BlocklistSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Triggers an immediate one-time sync regardless of constraints.
     * Used by the parent dashboard "Sync Now" button.
     */
    fun triggerImmediateSync() {
        val request = androidx.work.OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            "${BlocklistSyncWorker.WORK_NAME}_immediate",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
