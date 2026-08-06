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

        fun enqueue(wm: WorkManager, operationId: String, request: OperationRequest) {
            val data = Data.Builder()
                .putString(KEY_OPERATION_ID, operationId)
                .putString(KEY_REQUEST_JSON, Json.encodeToString(OperationRequest.serializer(), request))
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

        /** Schedules the next slice of a run parked on the foreground budget. */
        fun enqueueContinuation(
            wm: WorkManager,
            operationId: String,
            request: OperationRequest,
            delayMs: Long,
        ) {
            val data = Data.Builder()
                .putString(KEY_OPERATION_ID, operationId)
                .putString(KEY_REQUEST_JSON, Json.encodeToString(OperationRequest.serializer(), request))
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
        val requestJson = inputData.getString(KEY_REQUEST_JSON) ?: return Result.failure()
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
            onProgress = { p ->
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
                notifier.alert("İşlem tamamlandı", "Tüm hedefler işlendi.")
                Result.success()
            }
            OperationOutcome.PAUSED -> {
                recordState(OperationState.PAUSED)
                Result.success()
            }
            OperationOutcome.STOPPED -> {
                recordState(OperationState.STOPPED)
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
