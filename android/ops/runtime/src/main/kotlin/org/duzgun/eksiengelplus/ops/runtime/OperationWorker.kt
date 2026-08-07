package org.duzgun.eksiengelplus.ops.runtime

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationOutcome
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.ReadPacer

private val Json = Json { ignoreUnknownKeys = true }

/**
 * Runs one operation, promoted to a foreground service by WorkManager.
 *
 * A CoroutineWorker rather than a hand-rolled Service because WorkManager
 * persists the request in its own database: process death, OEM task killers and
 * reboot all re-run the work instead of losing it. That is precisely the problem
 * resumableOperation.js solves by hand with resumableOp_<id> keys and a startup
 * sweep — here the platform does the bookkeeping.
 */
@HiltWorker
class OperationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: EksiDatabase,
    private val commands: OperationCommandBus,
    private val notifier: OpsNotifier,
    private val taskFactory: OperationTaskFactory,
    private val pacerState: PacerStateStore,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK = "eksiengel-operation"
        const val KEY_OPERATION_ID = "operationId"
        const val KEY_REQUEST_JSON = "requestJson"

        /**
         * Records the request, then schedules the work.
         *
         * The request goes to the database rather than into WorkManager's input
         * data, which is capped at 10 KB: a LIST run carries every nick it
         * targets, and a list imported from a CSV blows that cap, throwing on the
         * caller before anything is scheduled.
         *
         * Suspending because the write has to land first -- the worker reads the
         * request back from the checkpoint, so enqueueing ahead of the row would
         * race a worker that starts immediately.
         */
        suspend fun enqueue(
            wm: WorkManager,
            db: EksiDatabase,
            operationId: String,
            request: OperationRequest,
        ) {
            db.checkpoints().upsert(
                OperationCheckpointEntity(
                    operationId = operationId,
                    type = request.source.name,
                    state = OperationState.IDLE.name,
                    cursorJson = Json.encodeToString(OperationCursor.serializer(), OperationCursor()),
                    processed = 0,
                    total = 0,
                    successful = 0,
                    failed = 0,
                    startedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    workRequestId = null,
                    requestJson = Json.encodeToString(OperationRequest.serializer(), request),
                ),
            )

            val data = Data.Builder()
                .putString(KEY_OPERATION_ID, operationId)
                .build()

            // KEEP, not REPLACE: a second request must never cancel a run that is
            // hours deep. Serial execution is a hard requirement, since the pacer
            // budget is shared and two concurrent runs would double the rate.
            wm.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OperationWorker>()
                    .setInputData(data)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }

        /**
         * Reschedules a run whose checkpoint already exists.
         *
         * The row holds the request, so nothing needs writing and this stays
         * non-suspending for callers like the reconciler's resume offer.
         */
        fun enqueueExisting(wm: WorkManager, operationId: String) {
            wm.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OperationWorker>()
                    .setInputData(Data.Builder().putString(KEY_OPERATION_ID, operationId).build())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }

        /** Schedules the next slice of a run parked on the foreground budget. */
        fun enqueueContinuation(
            wm: WorkManager,
            operationId: String,
            request: OperationRequest,
            delayMs: Long,
        ) {
            // No request in the data here either: the checkpoint this continuation
            // resumes from already carries it, and a long list would breach the
            // 10 KB cap on the way to its second slice.
            val data = Data.Builder()
                .putString(KEY_OPERATION_ID, operationId)
                .build()

            wm.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OperationWorker>()
                    .setInputData(data)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }
    }

    /** Kept so the budget warning can state how much work is left. */
    private var lastRemaining: Int = 0

    private val operationId: String
        get() = inputData.getString(KEY_OPERATION_ID) ?: "unknown"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        notifier.ensureChannels()
        return ForegroundInfo(
            OpsNotifier.NOTIFICATION_ID_PROGRESS,
            notifier.progress(operationId, "EksiEngelPlus", 0, 0, ActionPacer.DEFAULT_PERMITS_PER_MINUTE),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result {
        /*
         * The request comes from the checkpoint, not from input data.
         *
         * WorkManager caps Data at 10 KB and throws when a request exceeds it. A
         * LIST run carries every nick it targets, so three typed names fit and a
         * few hundred imported from a CSV do not -- the throw landed on the caller
         * and took the app down before the run ever started.
         *
         * Input data is still read first, so work enqueued by an older build
         * finishes rather than failing on upgrade.
         */
        val requestJson = inputData.getString(KEY_REQUEST_JSON)
            ?: db.checkpoints().get(operationId)?.requestJson
            ?: return Result.failure()
        val request = runCatching {
            Json.decodeFromString(OperationRequest.serializer(), requestJson)
        }.getOrNull() ?: return Result.failure()

        notifier.ensureChannels()
        setForeground(getForegroundInfo())

        val existing = db.checkpoints().get(operationId)
        val startCursor = existing?.cursorJson
            ?.let { runCatching { Json.decodeFromString(OperationCursor.serializer(), it) }.getOrNull() }
            ?: OperationCursor()

        val budget = ForegroundBudget().apply { resume(existing?.fgsMillisUsed ?: 0) }
        val actionPacer = ActionPacer(sleep = { kotlinx.coroutines.delay(it) }, state = pacerState)
        val readPacer = ReadPacer(sleep = { kotlinx.coroutines.delay(it) })

        val ctx = RoomOperationContext(
            operationId = operationId,
            request = request,
            startCursor = startCursor,
            db = db,
            commands = commands,
            budget = budget,
            actionPacer = actionPacer,
            readPacer = readPacer,
            onBudgetWarning = { _ ->
                // Opens the app: while it is visible the run costs no budget.
                val launch = applicationContext.packageManager
                    .getLaunchIntentForPackage(applicationContext.packageName)
                    ?.let {
                        android.app.PendingIntent.getActivity(
                            applicationContext,
                            0,
                            it,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_IMMUTABLE,
                        )
                    }
                notifier.budgetWarning(remainingItems = lastRemaining, launchIntent = launch)
            },
            onProgress = { p ->
                lastRemaining = (p.total - p.processed).coerceAtLeast(0)
                setForeground(
                    ForegroundInfo(
                        OpsNotifier.NOTIFICATION_ID_PROGRESS,
                        notifier.progress(
                            operationId,
                            request.source.name,
                            p.processed,
                            p.total,
                            ActionPacer.DEFAULT_PERMITS_PER_MINUTE,
                        ),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    ),
                )
            },
        )

        val task = taskFactory.create(request)
            ?: return Result.failure()

        val outcome = runCatching { task.run(ctx) }.getOrElse {
            notifier.alert("İşlem başarısız", it.message ?: it.javaClass.simpleName)
            recordState(OperationState.INTERRUPTED)
            return Result.failure()
        }

        commands.clear(operationId)

        return when (outcome) {
            OperationOutcome.COMPLETED -> {
                recordState(OperationState.COMPLETED)
                notifier.clearProgress()
                notifier.alert("İşlem tamamlandı", "Tüm hedefler işlendi.")
                Result.success()
            }
            OperationOutcome.PAUSED -> {
                recordState(OperationState.PAUSED)
                // The foreground service ends here and takes its notification with
                // it, so without this the run vanishes with no way back to it.
                val cp = db.checkpoints().get(operationId)
                notifier.showPaused(
                    operationId,
                    processed = cp?.processed ?: 0,
                    total = cp?.total ?: 0,
                )
                Result.success()
            }
            OperationOutcome.STOPPED -> {
                recordState(OperationState.STOPPED)
                // Stopped is terminal, so nothing should be left offering a resume.
                notifier.clearProgress()
                Result.success()
            }
            OperationOutcome.PAUSED_AUTH -> {
                // No retry: /giris is behind Turnstile, so only a human can fix
                // this. Retrying would spend the rest of the budget failing.
                recordState(OperationState.PAUSED_AUTH)
                notifier.alert(
                    "Oturum sona erdi",
                    "İşlem duraklatıldı. Devam etmek için tekrar giriş yapın.",
                )
                Result.success()
            }
            OperationOutcome.PAUSED_BUDGET -> {
                recordState(OperationState.PAUSED_BUDGET)
                notifier.alert(
                    "İşlem yarın devam edecek",
                    "Günlük arka plan süresi doldu. Kalan işlemler otomatik olarak sürdürülecek.",
                )
                enqueueContinuation(
                    WorkManager.getInstance(applicationContext),
                    operationId,
                    request,
                    delayMs = budgetResetDelayMs(),
                )
                Result.success()
            }
        }
    }

    /**
     * The platform's foreground-service allowance is a rolling 24-hour window, so
     * waiting out the remainder of a day is the simplest correct thing. Erring
     * long is cheap; erring short means the continuation is killed on arrival.
     */
    private fun budgetResetDelayMs(): Long = TimeUnit.HOURS.toMillis(20)

    private suspend fun recordState(state: OperationState) {
        db.checkpoints().get(operationId)?.let {
            db.checkpoints().upsert(it.copy(state = state.name, updatedAt = System.currentTimeMillis()))
        }
    }
}

/** Maps a request to the task that serves it. */
interface OperationTaskFactory {
    fun create(request: OperationRequest): org.duzgun.eksiengelplus.ops.engine.OperationTask?
}

/** Room-backed pacer state, so the bucket survives process death. */
interface PacerStateStore : org.duzgun.eksiengelplus.ops.engine.PacerState
