package palacios.top.calendartv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import palacios.top.calendartv.CalendarTvApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CalendarTvApp).container
        return when (container.syncer.sync()) {
            SyncOutcome.OK, SyncOutcome.NEEDS_AUTH -> Result.success()
            SyncOutcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

class SyncScheduler(context: Context) {

    private val work = WorkManager.getInstance(context)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** WorkManager's minimum period is 15 minutes. */
    fun ensurePeriodic() {
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build(),
        )
    }

    fun syncNow() {
        work.enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build(),
        )
    }

    fun cancelAll() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(NOW)
    }

    private companion object {
        const val PERIODIC = "sync-periodic"
        const val NOW = "sync-now"
    }
}
