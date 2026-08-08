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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.ops.engine.PauseSignal
import org.duzgun.eksiengelplus.ops.engine.StopSignal
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
    private val configRepository: org.duzgun.eksiengelplus.datastore.ConfigRepository,
) : CoroutineWorker(appContext, params) {

    /** Supplied by the build; blank in a developer build, which then never posts. */
    private val telemetryKey: String
        get() = inputData.getString(KEY_TELEMETRY_KEY).orEmpty()
    private val telemetryUrl: String
        get() = inputData.getString(KEY_TELEMETRY_URL)
            ?: "https://eksiengelplus.duzgun.org/api/action/"

    companion object {
        /**
         * How often a pacing wait looks at the command bus.
         *
         * Short enough that Durdur feels immediate, long enough that a 30s
         * penalty costs 120 cheap reads of an in-memory bus rather than a poll
         * loop worth worrying about.
         */
        private const val COMMAND_POLL_MS = 250L

        /** Below this there is no countdown worth rendering. */
        private const val COUNTDOWN_MIN_MS = 1_000L

        const val UNIQUE_WORK = "eksiengel-operation"
        const val KEY_OPERATION_ID = "operationId"
        const val KEY_REQUEST_JSON = "requestJson"
        const val KEY_TELEMETRY_KEY = "telemetryKey"
        const val KEY_TELEMETRY_URL = "telemetryUrl"

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
            /*
             * A run is already going: queue this one instead of losing it.
             *
             * The unique work uses KEEP so a second request cannot cancel a run
             * hours deep -- but KEEP also silently discards it, which meant
             * tapping çalıştır during a run reported "sıraya alındı" and then
             * did nothing at all. The queue is what makes that message true.
             */
            if (db.checkpoints().liveCount() > 0) {
                // Queued, not lost. Drained when the live run reaches a terminal
                // state; see startNextQueued.
                db.queuedTasks().enqueue(
                    org.duzgun.eksiengelplus.database.QueuedTaskEntity(
                        seq = db.queuedTasks().maxSeq() + 1,
                        banSourcePk = request.source.pk,
                        banModePk = request.mode.pk,
                        targetTypePk = request.targetType.pk,
                        clickSourcePk = null,
                        payloadJson = Json.encodeToString(OperationRequest.serializer(), request),
                        status = "QUEUED",
                        enqueuedAt = System.currentTimeMillis(),
                    ),
                )
                return
            }

            startNow(wm, db, operationId, request)
        }

        /** Writes the run's row and schedules it, with no queue check. */
        suspend fun startNow(
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

            /*
             * REPLACE, now that the queue above is what protects a running run.
             *
             * KEEP was doing two jobs: keeping a live run safe, and deciding what
             * happens to a second request. The check above already handles the
             * first, so all KEEP could still do was silently discard the request
             * whenever a stale entry lingered under this name -- an enqueued or
             * cancelled one from a run that never started. The checkpoint was
             * written, the work was dropped, and it sat as "başlamadı" forever.
             *
             * Reaching here means our own records say nothing is live, so
             * replacing whatever WorkManager still holds is correct.
             */
            wm.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
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
            // Resuming a parked run: nothing is executing, so a stale entry must
            // not be allowed to swallow it either.
            wm.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
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

    /** Last published counts, so a cooldown notification keeps showing them. */
    private var lastProcessed: Int = 0
    private var lastTotal: Int = 0

    private val operationId: String
        get() = inputData.getString(KEY_OPERATION_ID) ?: "unknown"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        notifier.ensureChannels()
        return ForegroundInfo(
            OpsNotifier.NOTIFICATION_ID_PROGRESS,
            notifier.progress(operationId, "EksiEngelPlus", 0, 0),
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
        /*
         * Pacing waits sleep in slices, watching for Durdur and Duraklat.
         *
         * The pacer slept a whole wait in one delay(), and ensureActive() -- the
         * only thing that reads the command bus -- runs between actions. A 429
         * penalty holds the bucket for up to 30s and a server cooldown longer,
         * so pressing Durdur during one did nothing until the wait expired: the
         * button looked stuck, then the run reacted all at once when the
         * cooldown ended.
         *
         * The signals are the same ones ensureActive() raises and the task loop
         * already handles both. Safe to abandon a wait: acquire() takes its
         * token after sleeping, never before, so nothing is consumed here.
         */
        suspend fun responsiveSleep(totalMs: Long) {
            var remaining = totalMs
            // The bus is polled four times a second; the notification is not.
            // Posting every slice would be ~120 updates on a 30s cooldown, which
            // the system rate-limits anyway, and the text only changes on the
            // second.
            var shownSecond = -1L
            while (remaining > 0) {
                when (commands.peek(operationId)) {
                    OperationCommand.PAUSE -> throw PauseSignal()
                    OperationCommand.STOP -> throw StopSignal()
                    null -> Unit
                }
                // Every rate-limit wait is counted down, whether it is the
                // bucket's ordinary turn (~5s at 12/min) or a 429 penalty. They
                // are the same thing to the user -- the API not letting the next
                // action through yet -- and the number ticking is the point.
                // Sub-second waits are skipped: nothing to watch.
                val second = (remaining + 999) / 1000
                if (totalMs >= COUNTDOWN_MIN_MS && second != shownSecond) {
                    shownSecond = second
                    setForeground(
                        ForegroundInfo(
                            OpsNotifier.NOTIFICATION_ID_PROGRESS,
                            notifier.progress(
                                operationId,
                                request.source.name,
                                lastProcessed,
                                lastTotal,
                                waitMs = remaining,
                            ),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        ),
                    )
                }
                val slice = minOf(remaining, COMMAND_POLL_MS)
                kotlinx.coroutines.delay(slice)
                remaining -= slice
            }
        }

        val actionPacer = ActionPacer(sleep = ::responsiveSleep, state = pacerState)
        val readPacer = ReadPacer(sleep = ::responsiveSleep)

        /*
         * The date filter, resolved once for the run.
         *
         * Read once rather than per target: a rule changed mid-run would apply to
         * part of a list and not the rest, which is not a filter anyone asked
         * for. Dates come from the cache the CSV import and the list sync fill.
         */
        val config = configRepository.config.first()
        val activeRules = if (config.enableDateFilter) config.dateFilterRules else emptyList()
        val today = java.time.LocalDate.now(org.duzgun.eksiengelplus.model.TurkishDateParser.ZONE).toEpochDay()

        val ctx = RoomOperationContext(
            allowTarget = { nick ->
                if (activeRules.none { it.enabled }) {
                    true
                } else {
                    val cached = db.registrationDates().get(nick)?.registrationEpochDay
                    org.duzgun.eksiengelplus.datastore.DateFilter.allows(activeRules, cached, today)
                }
            },
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
                lastProcessed = p.processed
                lastTotal = p.total
                setForeground(
                    ForegroundInfo(
                        OpsNotifier.NOTIFICATION_ID_PROGRESS,
                        notifier.progress(
                            operationId,
                            request.source.name,
                            p.processed,
                            p.total,
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
                val done = db.checkpoints().get(operationId)
                archive(request)

                /*
                 * One run finishing is not everything finishing.
                 *
                 * The old wording announced completion outright while a queue was
                 * still waiting, so the app declared itself done and then carried
                 * on working. What is left is counted before the queue is drained,
                 * so it reflects what has not started rather than what just did.
                 */
                val waiting = db.queuedTasks().count().first()
                val summary = done?.let { "${it.successful}/${it.processed} işlendi" }.orEmpty()

                notifier.clearProgress()
                if (waiting > 0) {
                    notifier.alert(
                        "İşlem tamamlandı",
                        listOf(summary, "sırada $waiting işlem var, bir sonrakine geçiliyor.")
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
                } else {
                    notifier.alert(
                        "Tüm işlemler tamamlandı",
                        summary.ifBlank { "Tüm hedefler işlendi." },
                    )
                }

                startNextQueued()
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
                archive(request)
                startNextQueued()
                // recordState only writes if the row is still there, so a run
                // stopped by cancellation stays deleted rather than coming back
                // as a stopped one.
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

    /**
     * Moves a finished run into history.
     *
     * completed_operation has existed since android-foundations and nothing has
     * ever written to it, so a run that finished said so in a notification and
     * then vanished -- the history screen had nothing to show because there was
     * never anything in the table.
     *
     * The checkpoint is removed in the same step: keeping a terminal row would
     * leave the run in two places, and the live count that gates refresh and
     * queueing reads that table.
     */
    private suspend fun archive(request: OperationRequest) {
        val cp = db.checkpoints().get(operationId) ?: return
        db.completedOperations().insert(
            org.duzgun.eksiengelplus.database.CompletedOperationEntity(
                banSourcePk = request.source.pk,
                banModePk = request.mode.pk,
                processed = cp.processed,
                successful = cp.successful,
                failed = cp.failed,
                startedAt = cp.startedAt,
                finishedAt = System.currentTimeMillis(),
                summaryJson = "{}",
            ),
        )
        db.checkpoints().remove(operationId)
        db.completedOperations().trim()

        /*
         * Reported in the extension's own shape (commHandler.js:47-93), so the
         * backend cannot tell the two clients apart, and only with consent.
         *
         * Written to the outbox rather than posted here: a report must never be
         * able to fail the run that produced it.
         */
        val config = configRepository.config.first()
        TelemetryReporter(db, endpoint = telemetryUrl, apiKey = telemetryKey).record(
            sendData = config.sendData,
            bodyJson = Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("ban_source", kotlinx.serialization.json.JsonPrimitive(request.source.pk))
                    put("ban_mode", kotlinx.serialization.json.JsonPrimitive(request.mode.pk))
                    put("target_type", kotlinx.serialization.json.JsonPrimitive(request.targetType.pk))
                    put("planned_action", kotlinx.serialization.json.JsonPrimitive(cp.total))
                    put("performed_action", kotlinx.serialization.json.JsonPrimitive(cp.processed))
                    put("successful_action", kotlinx.serialization.json.JsonPrimitive(cp.successful))
                    put("author_list_size", kotlinx.serialization.json.JsonPrimitive(request.nicks.size))
                    put("is_early_stopped", kotlinx.serialization.json.JsonPrimitive(cp.processed < cp.total))
                },
            ),
            now = System.currentTimeMillis(),
        )
        TelemetryWorker.enqueue(WorkManager.getInstance(applicationContext), telemetryKey)
    }

    /**
     * Starts whatever was waiting behind this run.
     *
     * Called on the terminal outcomes only. A pause is not the end of a run, so
     * jumping the queue there would leave two operations sharing a pacer budget
     * sized for one.
     */
    private suspend fun startNextQueued() {
        val next = db.queuedTasks().next() ?: return
        db.queuedTasks().remove(next.id)
        val request = runCatching {
            Json.decodeFromString(OperationRequest.serializer(), next.payloadJson)
        }.getOrNull() ?: return

        // Started directly rather than through enqueue(), which would consult the
        // live check and could put this straight back on the queue it just came
        // from. Nothing is running at this point: this is the run that finished.
        startNow(
            WorkManager.getInstance(applicationContext),
            db,
            java.util.UUID.randomUUID().toString(),
            request,
        )
    }

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
