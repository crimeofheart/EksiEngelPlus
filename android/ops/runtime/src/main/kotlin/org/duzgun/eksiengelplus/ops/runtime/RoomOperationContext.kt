package org.duzgun.eksiengelplus.ops.runtime

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.ops.engine.BudgetExhaustedSignal
import org.duzgun.eksiengelplus.ops.engine.OperationContext
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationProgress
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.PauseSignal
import org.duzgun.eksiengelplus.ops.engine.RateLimitAware
import org.duzgun.eksiengelplus.ops.engine.StopSignal
import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.ReadPacer

private val Json = Json { ignoreUnknownKeys = true }

/**
 * Tracks foreground-service time against a soft budget.
 *
 * Android 15 caps a dataSync foreground service at roughly six hours per rolling
 * twenty-four and then calls onTimeout(). At twelve actions a minute a
 * ten-thousand-user run needs about fourteen hours, so a run has to span
 * sessions. The soft budget sits under the platform cap so the checkpoint is
 * ours to schedule rather than the system's to force.
 *
 * Time counts only while the foreground service holds the process. With a
 * visible activity the operation runs unconstrained, so leaving the app open
 * genuinely finishes sooner -- which is worth telling the user. The worker
 * drives that from [AppVisibility], on a thread of its own, hence the locking.
 */
class ForegroundBudget(
    private val softBudgetMs: Long = DEFAULT_SOFT_BUDGET_MS,
    private val warnFraction: Double = DEFAULT_WARN_FRACTION,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Five hours, comfortably under the ~6h platform cap. */
        const val DEFAULT_SOFT_BUDGET_MS = 5L * 60 * 60 * 1000

        /**
         * Warn at 80% -- four hours in, with an hour of runway. Early enough to
         * act on, late enough not to be noise.
         */
        const val DEFAULT_WARN_FRACTION = 0.8
    }

    private var startedAt: Long? = null
    private var consumedMs: Long = 0
    private var warned = false

    @Synchronized
    fun resume(alreadyConsumedMs: Long) {
        consumedMs = alreadyConsumedMs
        startedAt = clock()
    }

    /**
     * Called when a visible activity takes over: stops billing, and starts over.
     *
     * Starting over, not just pausing, because that is what the platform does:
     * bringing the app to the foreground resets its timer, and the service has
     * the full six hours again once the app goes back to the background. Keeping
     * the count would park a run hours before anything required it. The warning
     * is re-armed with it, since the next one would be about a new allowance.
     */
    @Synchronized
    fun releaseForeground() {
        startedAt = null
        consumedMs = 0
        warned = false
    }

    /** Called when the app leaves the screen; billing starts again from here. */
    @Synchronized
    fun holdForeground() {
        if (startedAt == null) startedAt = clock()
    }

    @Synchronized
    fun consumedMs(): Long = consumedMs + (startedAt?.let { clock() - it } ?: 0)

    fun isExhausted(): Boolean = consumedMs() >= softBudgetMs

    fun remainingMs(): Long = (softBudgetMs - consumedMs()).coerceAtLeast(0)

    /**
     * True exactly once, on the first crossing of the warning threshold.
     *
     * There is no way to get more background time honestly, so the warning's job
     * is to surface the option that exists the whole time: work done with the app
     * visible costs no budget at all. Hence "open the app to finish now" rather
     * than any suggestion that the limit can be extended.
     *
     * Once, not repeatedly. A notification that fires every few minutes trains
     * the user to dismiss it, including the time it mattered.
     */
    @Synchronized
    fun shouldWarn(): Boolean {
        if (warned) return false
        if (consumedMs() < (softBudgetMs * warnFraction).toLong()) return false
        warned = true
        return true
    }
}

/**
 * The OperationContext a real run uses.
 *
 * The important part is checkpoint(): the cursor and the rows the unit produced
 * are written in ONE transaction. Writing them separately is the single place a
 * crash corrupts user-visible state -- a cursor ahead of its rows silently skips
 * users, one behind re-processes them, and neither is visible until someone
 * notices the wrong people are blocked.
 */
class RoomOperationContext(
    private val operationId: String,
    override val request: OperationRequest,
    override val startCursor: OperationCursor,
    private val db: EksiDatabase,
    private val commands: OperationCommandBus,
    private val budget: ForegroundBudget,
    private val actionPacer: ActionPacer,
    private val readPacer: ReadPacer,
    private val onProgress: suspend (OperationProgress) -> Unit = {},
    /**
     * Targets gathered so far, while the run is still collecting them.
     *
     * Not written to the checkpoint: the row's processed/total describe work
     * done against a known list, and borrowing them for "found 1,300 so far"
     * would make İşlem durumu claim progress that has not happened.
     */
    private val onCollecting: suspend (Int) -> Unit = {},
    /**
     * The user's date filter, already resolved to a predicate.
     *
     * A lambda rather than the rules themselves, so ops:runtime does not have to
     * decide what a rule means and the engine never learns that rules exist.
     */
    private val allowTarget: suspend (String) -> Boolean = { true },
    /**
     * Invoked once when the budget is nearly spent. A callback rather than the
     * notifier itself, so ops:engine never learns about Android.
     */
    private val onBudgetWarning: suspend (remainingMs: Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Who the run acts as, for the history ledger; null when history sync is off
     * or the user is not known, and then no outcome is written anywhere.
     */
    private val history: HistoryRecording? = null,
    /** Asked for when enough has been written that the other devices should hear it. */
    private val onHistoryDue: () -> Unit = {},
) : OperationContext, RateLimitAware {

    /** Outcomes since the last checkpoint. They commit with it, or not at all. */
    private val pendingHistory = ArrayList<org.duzgun.eksiengelplus.database.HistoryOutboxEntity>()
    private var historySinceDue = 0
    private var historyDueAt = clock()

    override suspend fun recordOutcome(
        mode: org.duzgun.eksiengelplus.model.BanMode,
        targetType: org.duzgun.eksiengelplus.model.TargetType,
        id: Long,
        outcome: org.duzgun.eksiengelplus.ops.engine.RelationOutcome,
        nick: String?,
    ) {
        val recording = history ?: return
        pendingHistory += recording.row(mode, targetType, id, outcome, nick)
    }

    /** After a commit: the rows are on disk, and a push may be due. */
    private fun historyCommitted() {
        if (pendingHistory.isEmpty()) return
        historySinceDue += pendingHistory.size
        pendingHistory.clear()
        if (historySinceDue >= HISTORY_DUE_EVERY || clock() - historyDueAt >= HISTORY_DUE_MS) {
            historySinceDue = 0
            historyDueAt = clock()
            onHistoryDue()
        }
    }

    /**
     * Writes outcomes left over after the last checkpoint, for a task that ended
     * without one. Every TargetRunner path checkpoints on the way out, so this is
     * a backstop, not the route.
     */
    suspend fun flushHistory() {
        if (pendingHistory.isEmpty()) return
        db.historyOutbox().addAll(pendingHistory.toList())
        historyCommitted()
    }

    private val logBuffer = ArrayDeque<String>()

    override suspend fun ensureActive() {
        when (commands.peek(operationId)) {
            OperationCommand.PAUSE -> throw PauseSignal()
            OperationCommand.STOP -> throw StopSignal()
            null -> Unit
        }
        // Checked here rather than on a timer so the operation always stops
        // between units, never mid-mutation.
        if (budget.isExhausted()) throw BudgetExhaustedSignal()
        if (budget.shouldWarn()) onBudgetWarning(budget.remainingMs())
    }

    /**
     * The run's size and start, which the cursor does not carry.
     *
     * checkpoint() was writing total = 0 and startedAt = 0 on every pass, so the
     * screen read "15 / 0" while the notification read "3 / 28", and every run
     * claimed to have begun in 1970. The notification had them because
     * publishProgress is given the total; the row simply never kept it.
     */
    @Volatile private var lastTotal: Int = 0

    override suspend fun checkpoint(cursor: OperationCursor, effects: suspend () -> Unit) {
        val alive = db.withTransaction {
            /*
             * The outcomes first, and whatever happens to the row. A run cancelled
             * mid-way still changed what the user holds for the targets it
             * reached, and losing those would make the ledger disagree with the
             * site. The cursor and its rows are a separate matter, handled below.
             */
            if (pendingHistory.isNotEmpty()) db.historyOutbox().addAll(pendingHistory.toList())
            /*
             * A missing row means the run was cancelled while it was working.
             *
             * The upsert below would otherwise recreate it as RUNNING, which is
             * exactly what happened: cancelling deleted the checkpoint, the live
             * worker rebuilt it on its next pass, and the app was left reporting
             * an operation in progress that nothing could pause, stop or resume,
             * because RUNNING is neither terminal nor resumable. The StopSignal is
             * raised once the outcomes above have committed.
             */
            val existing = db.checkpoints().get(operationId) ?: return@withTransaction false
            effects()
            db.checkpoints().upsert(
                OperationCheckpointEntity(
                    operationId = operationId,
                    type = request.source.name,
                    state = OperationState.RUNNING.name,
                    cursorJson = Json.encodeToString(OperationCursor.serializer(), cursor),
                    processed = cursor.processed,
                    total = maxOf(lastTotal, cursor.processed),
                    successful = cursor.successful,
                    failed = cursor.failed,
                    // Preserved: enqueue set it, and overwriting it each pass is
                    // what dated every run to the epoch.
                    startedAt = existing.startedAt,
                    updatedAt = clock(),
                    // Preserved too: it is how the reconciler knows a run taken
                    // off the queue is still being carried by a live worker.
                    workRequestId = existing.workRequestId,
                    fgsMillisUsed = budget.consumedMs(),
                    // Carried on the row so resumption does not depend on the
                    // caller still holding the request.
                    requestJson = Json.encodeToString(OperationRequest.serializer(), request),
                ),
            )
            true
        }
        historyCommitted()
        if (!alive) throw StopSignal()
    }

    override suspend fun allows(nick: String): Boolean = allowTarget(nick)

    override suspend fun publishProgress(progress: OperationProgress) {
        // The only place the run's size is known, so the checkpoint borrows it.
        lastTotal = progress.total
        /*
         * Written through, not just remembered.
         *
         * The notification reads these from memory; İşlem durumu reads them from
         * the row, and nothing wrote the row between checkpoints. The two
         * surfaces disagreed about the same run -- first 0 / 1 against 0 / 0
         * before the size was known, then 8 / 13 against 5 / 13 as the counts
         * moved in steps of five.
         *
         * Cheap enough to do per action: the rate limit caps this at twelve
         * writes a minute, and it is one UPDATE against one row by primary key.
         * The cursor is deliberately untouched, so what a resume restores is
         * still decided by checkpoint() alone.
         */
        db.checkpoints().setLiveProgress(
            operationId,
            processed = progress.processed,
            total = progress.total,
            successful = progress.successful,
            failed = progress.failed,
            at = clock(),
        )
        onProgress(progress)
    }

    override suspend fun publishCollecting(found: Int) = onCollecting(found)

    override suspend fun awaitActionPermit() = actionPacer.acquire()

    override suspend fun awaitReadPermit() = readPacer.acquire()

    override suspend fun onRateLimited(retryAfterSeconds: Int) =
        actionPacer.penalize(retryAfterSeconds)

    override suspend fun log(message: String) {
        // Ring buffer: the telemetry log field is capped at 10,000 characters
        // server-side (api/serializers.py:191), so unbounded retention would only
        // be discarded later.
        logBuffer.addLast(message)
        while (logBuffer.size > 200) logBuffer.removeFirst()
    }

    fun logs(): List<String> = logBuffer.toList()

    /**
     * Who the run acted on, for the action report.
     *
     * Held in memory for the length of the run, as the extension does: it is what
     * the backend's most_banned rankings are built from, and the alternative --
     * reporting an empty list -- would make every Android run invisible to them
     * while still counting as telemetry.
     *
     * Capped at the serializer's own limit (api/serializers.py:184). Past that
     * the whole report is rejected, so keeping more would cost the report rather
     * than enlarge it.
     */
    private val targetBuffer = ArrayList<Pair<String, Long>>()

    override suspend fun recordTarget(nick: String, id: Long) {
        if (targetBuffer.size >= MAX_REPORTED_TARGETS) return
        targetBuffer.add(nick to id)
    }

    fun targets(): List<Pair<String, Long>> = targetBuffer.toList()

    private companion object {
        const val MAX_REPORTED_TARGETS = 10_000

        /** While a run goes: push every 25 outcomes or every minute, whichever first. */
        const val HISTORY_DUE_EVERY = 25
        const val HISTORY_DUE_MS = 60_000L
    }
}
