package org.duzgun.eksiengelplus.feature.lists

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.ListType

/**
 * Runs one list sync.
 *
 * Deliberately NOT an OperationTask. A sync makes no mutations: it is not paced by
 * ActionPacer (the ~12/min ceiling governs addrelation/removerelation, not GET
 * /relation-list), it does not checkpoint into operation_checkpoint, and above all
 * it must not draw down the Android 15 foreground-service budget that
 * ForegroundBudget rations for multi-day blocking runs. Spending an hour of a
 * six-hour daily allowance on a forty-second read would be a real regression.
 *
 * No foreground service and no notification for the same reason -- a sync is short
 * enough to live inside a normal background execution window.
 */
@HiltWorker
class ListSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val source: RelationSource,
    private val store: ListSyncStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val listType = inputData.getString(KEY_LIST_TYPE)
            ?.let { runCatching { ListType.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        // isStopped covers both the user's Stop button (cancelUniqueWork) and the
        // system reclaiming the worker. Either way the pages already fetched stay,
        // and the cursor points at where to pick up.
        return when (val outcome = ListSyncer(source, store).sync(listType) { isStopped }) {
            is SyncOutcome.Completed -> Result.success()
            is SyncOutcome.Stopped -> Result.success()
            // Retrying a session loss would just fail the same way until the user
            // logs in, and the Lists screen already shows the list as partial.
            SyncOutcome.SessionLost -> Result.failure()
            is SyncOutcome.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_LIST_TYPE = "listType"
        private const val MAX_ATTEMPTS = 3

        /** One unique work name per list, so the three can sync independently. */
        fun uniqueWorkName(listType: ListType) = "eksiengel-list-sync-${listType.name.lowercase()}"

        /**
         * The `ban_source` this sync reports as, once the telemetry sender exists.
         *
         * Recorded here rather than invented at the call site because these
         * integers are primary keys in the shared backend (enums.js), and the
         * backend should not be able to tell the extension and the app apart.
         */
        fun telemetrySource(listType: ListType): BanSource = when (listType) {
            ListType.BLOCKED -> BanSource.REFRESH_BLOCKED_LIST
            ListType.MUTED -> BanSource.REFRESH_MUTED_LIST
            ListType.FOLLOWED -> BanSource.REFRESH_FOLLOWED_LIST
        }

        fun enqueue(wm: WorkManager, listType: ListType) {
            wm.enqueueUniqueWork(
                uniqueWorkName(listType),
                // KEEP: tapping Refresh twice must not restart a sync that is
                // already forty pages deep.
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ListSyncWorker>()
                    .setInputData(Data.Builder().putString(KEY_LIST_TYPE, listType.name).build())
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }

        fun stop(wm: WorkManager, listType: ListType) {
            wm.cancelUniqueWork(uniqueWorkName(listType))
        }
    }
}
