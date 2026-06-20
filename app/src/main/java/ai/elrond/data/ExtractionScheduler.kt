package ai.elrond.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Enqueues background auto-extraction for a saved note page (FA-2). */
object ExtractionScheduler {

    /** Short delay after a save so a burst of edits coalesces into one run (with REPLACE). */
    private const val INITIAL_DELAY_SECONDS = 5L

    fun enqueue(context: Context, pageId: String) {
        // NETWORK_TYPE_NOT_REQUIRED so the job isn't gated on network (the AI call retries if
        // offline); battery-not-low + battery-saver handling keep it from draining the device.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setInputData(workDataOf(ExtractionWorker.KEY_PAGE_ID to pageId))
            .setConstraints(constraints)
            .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ExtractionWorker.uniqueName(pageId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
