package org.duzgun.eksiengelplus.ops.runtime

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.duzgun.eksiengelplus.database.CompletedOperationEntity
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.HistoryOutboxEntity
import org.duzgun.eksiengelplus.database.HistorySyncStateEntity
import org.duzgun.eksiengelplus.database.HistoryTaskEntity
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.database.QueuedTaskEntity
import org.duzgun.eksiengelplus.database.RemoteTaskEntity
import org.duzgun.eksiengelplus.datastore.ConfigRepository
import org.duzgun.eksiengelplus.datastore.IdentityRepository
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.RelationOutcome

/*
 * History sync: the outbox of per-target outcomes, the state of this device's
 * tasks as the other devices should see it, and the client for /api/sync/.
 *
 * Mirrored by frontend/app/assets/js/historySync.js. Wire names are the
 * backend's history/constants.py.
 */

private val HistoryJson = Json { ignoreUnknownKeys = true }

/** The names the server speaks, and its status rules. */
object HistoryWire {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val STOPPED = "STOPPED"

    /** Local only: another device took the task over, so this one says nothing more. */
    const val LOST = "LOST"

    val TERMINAL = setOf(DONE, FAILED, CANCELLED, STOPPED)
    private val RANK = mapOf(QUEUED to 0, RUNNING to 1, DONE to 2, FAILED to 2, CANCELLED to 2, STOPPED to 2)

    fun kind(targetType: TargetType): String = when (targetType) {
        TargetType.USER -> "BLOCK"
        TargetType.TITLE -> "TITLE_BLOCK"
        TargetType.MUTE -> "MUTE"
        TargetType.FOLLOW -> "FOLLOW"
    }

    fun direction(mode: BanMode): String = if (mode == BanMode.UNDOBAN) "REMOVE" else "ADD"

    /** Forward only, and a terminal status never changes -- the server's rule too. */
    fun merge(current: String?, incoming: String): String = when {
        current == null -> incoming
        current in TERMINAL || current == LOST -> current
        (RANK[incoming] ?: 0) > (RANK[current] ?: 0) -> incoming
        else -> current
    }

    /** What a checkpoint's state means to the other devices. A pause is still a running task. */
    fun statusOf(state: String): String = when (state) {
        OperationState.IDLE.name -> QUEUED
        OperationState.STOPPED.name, OperationState.STOPPING.name -> STOPPED
        OperationState.INTERRUPTED.name -> FAILED
        OperationState.COMPLETED.name -> DONE
        else -> RUNNING
    }
}

/** Who a run acts as, and which task its outcomes belong to. */
class HistoryRecording(
    val taskId: String,
    val actorId: Long,
    val actorNick: String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    fun row(mode: BanMode, targetType: TargetType, id: Long, outcome: RelationOutcome) = HistoryOutboxEntity(
        eventUuid = uuid(),
        taskId = taskId,
        kind = HistoryWire.kind(targetType),
        direction = HistoryWire.direction(mode),
        result = outcome.name,
        actorId = actorId,
        actorNick = actorNick,
        targetId = id,
        targetNick = null,
        occurredAt = clock(),
    )
}

/** A task as the local tables describe it right now. */
data class LocalTask(
    val taskId: String,
    val status: String,
    val requestJson: String?,
    val planned: Int,
    val performed: Int,
    val successful: Int,
    val createdAt: Long,
)

/** The rows reconciliation writes and the ones it can forget. */
data class Reconciled(val upserts: List<HistoryTaskEntity>, val deletes: List<String>)

/**
 * Task states, derived from the tables the app already keeps.
 *
 * Derived rather than reported at each change, because a task can leave the
 * queue in more ways than any one hook would cover -- removed from İşlem
 * durumu, cancelled by the reconciler, archived, stopped -- and a path that
 * forgot to report would leave the task waiting on every other device forever.
 * Whatever the path, the tables end up saying what happened.
 */
object TaskStates {

    /** How far back a finished run still counts as one to tell the other devices about. */
    const val RECENT_MS = 7L * 24 * 60 * 60 * 1000

    fun local(
        queued: List<QueuedTaskEntity>,
        checkpoints: List<OperationCheckpointEntity>,
        completed: List<CompletedOperationEntity>,
    ): Map<String, LocalTask> {
        val out = LinkedHashMap<String, LocalTask>()
        for (row in completed) {
            val id = row.taskId ?: continue
            out[id] = LocalTask(
                taskId = id,
                status = if (row.stopped) HistoryWire.STOPPED else HistoryWire.DONE,
                requestJson = row.requestJson,
                planned = maxOf(row.processed, row.successful + row.failed),
                performed = row.processed,
                successful = row.successful,
                createdAt = row.startedAt,
            )
        }
        for (cp in checkpoints) {
            out[cp.operationId] = LocalTask(
                taskId = cp.operationId,
                status = HistoryWire.statusOf(cp.state),
                requestJson = cp.requestJson,
                planned = maxOf(cp.total, cp.processed),
                performed = cp.processed,
                successful = cp.successful,
                createdAt = cp.startedAt,
            )
        }
        for (row in queued) {
            val id = row.taskId ?: continue
            out[id] = LocalTask(id, HistoryWire.QUEUED, row.payloadJson, 0, 0, 0, row.enqueuedAt)
        }
        return out
    }

    fun reconcile(
        known: List<HistoryTaskEntity>,
        local: Map<String, LocalTask>,
        actorId: Long?,
        describe: (OperationRequest?) -> String,
    ): Reconciled {
        val upserts = ArrayList<HistoryTaskEntity>()
        val deletes = ArrayList<String>()
        val byId = known.associateBy { it.taskId }

        for (task in local.values) {
            val previous = byId[task.taskId]
            if (previous?.status == HistoryWire.LOST) continue
            // Stopped because another device asked: that is a cancel, said once.
            val incoming = if (previous?.cancelRequested == true && task.status == HistoryWire.STOPPED) {
                HistoryWire.CANCELLED
            } else {
                task.status
            }
            if (previous == null) {
                val request = task.requestJson?.let {
                    runCatching { HistoryJson.decodeFromString(OperationRequest.serializer(), it) }.getOrNull()
                } ?: continue
                upserts += HistoryTaskEntity(
                    taskId = task.taskId,
                    epoch = 0,
                    status = incoming,
                    banSourcePk = request.source.pk,
                    banModePk = request.mode.pk,
                    description = describe(request).take(200),
                    requestJson = task.requestJson,
                    actorId = actorId,
                    planned = task.planned,
                    performed = task.performed,
                    successful = task.successful,
                    createdAt = task.createdAt,
                )
                continue
            }
            val next = previous.copy(
                status = HistoryWire.merge(previous.status, incoming),
                planned = maxOf(previous.planned, task.planned),
                performed = maxOf(previous.performed, task.performed),
                successful = maxOf(previous.successful, task.successful),
                actorId = previous.actorId ?: actorId,
            )
            if (next.copy(pushed = previous.pushed) != previous) upserts += next.copy(pushed = false)
        }

        for (previous in known) {
            if (previous.taskId in local) continue
            when {
                // Gone without finishing: removed from the queue, or a run that
                // never started was cleared away. Either way it will not run.
                previous.status != HistoryWire.LOST && previous.status !in HistoryWire.TERMINAL ->
                    upserts += previous.copy(status = HistoryWire.CANCELLED, pushed = false)
                // Settled, told, and aged out of the archive: nothing left to say.
                previous.pushed || previous.status == HistoryWire.LOST -> deletes += previous.taskId
            }
        }
        return Reconciled(upserts, deletes)
    }
}

/** /api/sync/ over HTTP. Throws [HistoryClient.HttpError] or IOException; callers decide. */
class HistoryClient(
    private val http: OkHttpClient,
    /** The run report endpoint; the sync routes sit beside it. */
    reportUrl: String,
    private val apiKey: String,
) {
    class HttpError(val code: Int, val body: JsonObject?) : IOException("history sync HTTP $code")

    private val base: String = reportUrl.substringBefore("/api/") + "/api/sync/"

    suspend fun enroll(label: String, version: String): String {
        val body = buildJsonObject {
            put("client", JsonPrimitive(ActionReport.CLIENT))
            put("label", JsonPrimitive(label.take(64)))
            put("version", JsonPrimitive(version.take(16)))
        }
        val response = call("POST", "enroll/", body, token = null)
            ?: throw HttpError(0, null)
        return response["device_token"]?.jsonPrimitive?.contentOrNull ?: throw HttpError(0, response)
    }

    suspend fun call(method: String, path: String, body: JsonObject? = null, token: String?): JsonObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(base + path).addHeader("Accept", "application/json")
            if (token == null) builder.addHeader("X-API-Key", apiKey)
            else builder.addHeader("Authorization", "Device $token")
            val payload = body?.toString()?.toRequestBody(JSON)
            when (method) {
                "POST" -> builder.post(payload ?: "{}".toRequestBody(JSON))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = text.takeIf { it.isNotBlank() }
                    ?.let { runCatching { HistoryJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                if (!response.isSuccessful) throw HttpError(response.code, json)
                json
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * One device's side of history sync: push what is waiting, pull what changed,
 * and act on what the other devices asked for.
 *
 * Never lets a failure reach a run. Everything it sends is already persisted, so
 * a sync that fails leaves the outbox as it was and backs off.
 */
class HistorySync(
    private val db: EksiDatabase,
    private val config: ConfigRepository,
    private val identity: IdentityRepository,
    private val client: HistoryClient,
    private val commands: OperationCommandBus,
    private val describe: (OperationRequest?) -> String,
    private val deviceLabel: String,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PUSH_EVENTS = 500
        const val PUSH_TASKS = 100
        const val MAX_BACKOFF_MS = 30L * 60 * 1000

        /** A request this large is a pasted list; the other devices get the description only. */
        const val MAX_SHARED_REQUEST = 16 * 1024
    }

    suspend fun enabled(): Boolean = config.config.first().syncHistory

    private suspend fun state(): HistorySyncStateEntity =
        db.historySyncState().get() ?: HistorySyncStateEntity()

    /** Returns true when the device is in step with the server. */
    suspend fun syncOnce(force: Boolean = false): Boolean {
        if (!enabled()) {
            dropPending()
            return false
        }
        val before = state()
        if (!force && clock() < before.backoffUntil) return false
        return try {
            val token = ensureToken()
            // Derived again before each round: a push can stop a task (a cancel
            // asked elsewhere), and its new state belongs in this pass, not the next.
            var rounds = 0
            while (rounds++ < 10) {
                reconcileTasks()
                if (!pushOnce(token)) break
            }
            pull(token)
            db.historySyncState().upsert(state().copy(failures = 0, backoffUntil = 0, lastSyncAt = clock()))
            true
        } catch (e: HistoryClient.HttpError) {
            // Revoked or erased elsewhere: enrol afresh next time.
            if (e.code == 401) identity.rememberHistoryToken("")
            backOff()
            false
        } catch (e: IOException) {
            backOff()
            false
        }
    }

    private suspend fun backOff() {
        val s = state()
        val failures = s.failures + 1
        val delay = minOf(MAX_BACKOFF_MS, 30_000L shl minOf(failures - 1, 10))
        db.historySyncState().upsert(s.copy(failures = failures, backoffUntil = clock() + delay))
    }

    private suspend fun ensureToken(): String {
        identity.identity.first().historyDeviceToken.takeIf { it.isNotBlank() }?.let { return it }
        val token = client.enroll(deviceLabel, appVersion)
        identity.rememberHistoryToken(token)
        db.historySyncState().upsert(state().copy(cursor = 0))
        return token
    }

    private suspend fun reconcileTasks() {
        val actor = identity.identity.first().eksiUserId.takeIf { it > 0 }
        db.withTransaction {
            val known = db.historyTasks().all()
            val completed = db.completedOperations().recentWithTaskIds(clock() - TaskStates.RECENT_MS)
            val local = TaskStates.local(db.queuedTasks().all(), db.checkpoints().all(), completed)
            val result = TaskStates.reconcile(known, local, actor, describe)
            result.upserts.forEach { db.historyTasks().upsert(it) }
            result.deletes.forEach { db.historyTasks().remove(it) }
        }
    }

    /** One push. True when something was sent, so the caller pushes again. */
    private suspend fun pushOnce(token: String): Boolean {
        val events = db.historyOutbox().oldest(PUSH_EVENTS)
        val tasks = db.historyTasks().unpushed(PUSH_TASKS)
        if (events.isEmpty() && tasks.isEmpty()) return false
        val body = buildJsonObject {
            put("tasks", JsonArray(tasks.map(::taskJson)))
            put("events", JsonArray(events.map(::eventJson)))
        }
        val response = client.call("POST", "push/", body, token) ?: JsonObject(emptyMap())

        val accepted = response["events_accepted"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (accepted.isNotEmpty()) db.historyOutbox().removeAll(accepted)

        val sent = tasks.associateBy { it.taskId }
        for (result in response["tasks"]?.jsonArray.orEmpty()) {
            val obj = result.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val current = db.historyTasks().get(id) ?: continue
            when (obj["result"]?.jsonPrimitive?.contentOrNull) {
                "conflict", "not_found" -> lose(id)
                else -> {
                    // A newer state recorded meanwhile goes on the next push.
                    if (current == sent[id]) db.historyTasks().upsert(current.copy(pushed = true))
                    if (obj["cancel_requested"]?.jsonPrimitive?.booleanOrNull == true) cancelRequested(id)
                }
            }
        }
        // Nothing accepted and nothing settled means the server refused the batch
        // in a way retrying cannot fix this pass; leave it for the next one.
        return accepted.isNotEmpty() || tasks.isNotEmpty()
    }

    private fun taskJson(t: HistoryTaskEntity): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(t.taskId))
        put("epoch", JsonPrimitive(t.epoch))
        t.actorId?.let { put("actor_eksi_id", JsonPrimitive(it)) }
        put("ban_source", JsonPrimitive(t.banSourcePk))
        put("ban_mode", JsonPrimitive(t.banModePk))
        put("description", JsonPrimitive(t.description))
        put(
            "params",
            buildJsonObject {
                // Lets another Android device run it. The extension cannot: its
                // retry params are a different shape, and the gap is stated in
                // the proposal rather than papered over.
                t.requestJson?.takeIf { it.length <= MAX_SHARED_REQUEST }
                    ?.let { put("android_request", JsonPrimitive(it)) }
            },
        )
        put("status", JsonPrimitive(t.status))
        put("planned", JsonPrimitive(t.planned))
        put("performed", JsonPrimitive(t.performed))
        put("successful", JsonPrimitive(t.successful))
        put("created_at", JsonPrimitive(t.createdAt))
    }

    private fun eventJson(e: HistoryOutboxEntity): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(e.eventUuid))
        put("task_id", e.taskId?.let(::JsonPrimitive) ?: JsonNull)
        put("kind", JsonPrimitive(e.kind))
        put("direction", JsonPrimitive(e.direction))
        put("result", JsonPrimitive(e.result))
        put("actor_eksi_id", JsonPrimitive(e.actorId))
        put("actor_nick", e.actorNick?.let(::JsonPrimitive) ?: JsonNull)
        put("target_eksi_id", JsonPrimitive(e.targetId))
        put("target_nick", e.targetNick?.let(::JsonPrimitive) ?: JsonNull)
        put("occurred_at", JsonPrimitive(e.occurredAt))
    }

    private suspend fun pull(token: String) {
        repeat(20) {
            val cursor = state().cursor
            val body = client.call("GET", "pull/?cursor=$cursor", token = token) ?: return
            for (task in body["tasks"]?.jsonArray.orEmpty()) absorb(task.jsonObject)
            val next = body["next_cursor"]?.jsonPrimitive?.longOrNull ?: cursor
            db.historySyncState().upsert(state().copy(cursor = next))
            if (body["has_more"]?.jsonPrimitive?.booleanOrNull != true) return
        }
    }

    private suspend fun absorb(task: JsonObject) {
        val id = task["id"]?.jsonPrimitive?.contentOrNull ?: return
        val status = task["status"]?.jsonPrimitive?.contentOrNull ?: return
        val mine = task["is_mine"]?.jsonPrimitive?.booleanOrNull == true
        val own = db.historyTasks().get(id)
        if (mine) {
            db.remoteTasks().remove(id)
            if (own != null && status !in HistoryWire.TERMINAL &&
                task["cancel_requested"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                cancelRequested(id)
            }
            return
        }
        // Another device runs it now; this one must not carry on with it.
        if (own != null && own.status != HistoryWire.LOST) lose(id)
        if (status in HistoryWire.TERMINAL) {
            db.remoteTasks().remove(id)
            return
        }
        val owner = task["owner"] as? JsonObject
        db.remoteTasks().upsert(
            RemoteTaskEntity(
                taskId = id,
                description = task["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                status = status,
                ownerLabel = owner?.get("label")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: if (owner?.get("client")?.jsonPrimitive?.contentOrNull == "EXTENSION") "eklenti" else "başka cihaz",
                ownerClient = owner?.get("client")?.jsonPrimitive?.contentOrNull,
                performed = task["performed"]?.jsonPrimitive?.intOrNull ?: 0,
                planned = task["planned"]?.jsonPrimitive?.intOrNull ?: 0,
                cancelRequested = task["cancel_requested"]?.jsonPrimitive?.booleanOrNull == true,
                requestJson = (task["params"] as? JsonObject)?.get("android_request")
                    ?.jsonPrimitive?.contentOrNull,
                updatedAt = clock(),
            ),
        )
    }

    /** Another device asked for a cancel: remember why, then stop. */
    private suspend fun cancelRequested(taskId: String) {
        db.historyTasks().get(taskId)?.let {
            if (!it.cancelRequested) db.historyTasks().upsert(it.copy(cancelRequested = true, pushed = false))
        }
        stopLocal(taskId)
    }

    /** Taken over elsewhere, or refused as stale. Stop it, and say nothing more about it. */
    private suspend fun lose(taskId: String) {
        db.historyTasks().get(taskId)?.let { db.historyTasks().upsert(it.copy(status = HistoryWire.LOST)) }
        stopLocal(taskId)
    }

    private suspend fun stopLocal(taskId: String) {
        if (db.queuedTasks().removeByTaskId(taskId) > 0) return
        val cp = db.checkpoints().get(taskId) ?: return
        if (cp.state == OperationState.RUNNING.name) {
            // The live run reads this between actions and parks; the archive then
            // records it stopped, which reconciliation reports as cancelled.
            commands.post(taskId, OperationCommand.STOP)
        } else {
            db.checkpoints().remove(taskId)
        }
    }

    /** The setting was turned off: nothing pending may leave the device later. */
    suspend fun dropPending() {
        db.withTransaction {
            db.historyOutbox().clear()
            db.historyTasks().clear()
            db.remoteTasks().clear()
        }
    }

    // --- what the screens ask for ---------------------------------------------------

    private suspend fun token(): String = ensureToken()

    suspend fun cancelRemote(taskId: String) {
        val task = client.call("POST", "tasks/$taskId/cancel/", token = token()) ?: return
        absorb(task)
    }

    /**
     * Takes a task over and returns the request to run under the same id, or null
     * when it carries none this device can run. The new epoch is written first, so
     * this device's next push carries it.
     */
    suspend fun takeOver(taskId: String): OperationRequest? {
        val remote = db.remoteTasks().observeAll().first().firstOrNull { it.taskId == taskId }
        val request = remote?.requestJson?.let {
            runCatching { HistoryJson.decodeFromString(OperationRequest.serializer(), it) }.getOrNull()
        } ?: return null
        val task = client.call("POST", "tasks/$taskId/takeover/", token = token()) ?: return null
        db.remoteTasks().remove(taskId)
        db.historyTasks().upsert(
            HistoryTaskEntity(
                taskId = taskId,
                epoch = task["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
                status = HistoryWire.QUEUED,
                banSourcePk = request.source.pk,
                banModePk = request.mode.pk,
                description = remote.description,
                requestJson = remote.requestJson,
                actorId = identity.identity.first().eksiUserId.takeIf { it > 0 },
                planned = 0,
                performed = 0,
                successful = 0,
                createdAt = clock(),
            ),
        )
        return request
    }

    suspend fun pairStart(): String =
        client.call("POST", "pair/start/", token = token())?.get("code")?.jsonPrimitive?.contentOrNull
            ?: throw HistoryClient.HttpError(0, null)

    suspend fun pairClaim(code: String) {
        client.call(
            "POST",
            "pair/claim/",
            buildJsonObject { put("code", JsonPrimitive(code)) },
            token = token(),
        )
        // A new account: everything it holds is news to this device.
        db.historySyncState().upsert(state().copy(cursor = 0))
        db.remoteTasks().clear()
        syncOnce(force = true)
    }

    /** Erases this account on the server, then forgets it here and turns sync off. */
    suspend fun deleteAccount() {
        val stored = identity.identity.first().historyDeviceToken
        if (stored.isNotBlank()) client.call("DELETE", "account/", token = stored)
        identity.rememberHistoryToken("")
        dropPending()
        db.historySyncState().upsert(HistorySyncStateEntity())
        config.update { it.copy(syncHistory = false) }
    }

    data class Status(val enabled: Boolean, val enrolled: Boolean, val pendingEvents: Int, val lastSyncAt: Long)

    suspend fun status() = Status(
        enabled = enabled(),
        enrolled = identity.identity.first().historyDeviceToken.isNotBlank(),
        pendingEvents = db.historyOutbox().count(),
        lastSyncAt = state().lastSyncAt,
    )
}

@HiltWorker
class HistorySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sync: HistorySync,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Success either way: HistorySync backs off on its own, and a WorkManager
        // retry on top would double the attempts against a server that is down.
        sync.syncOnce()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "eksiengel-history-sync"
        private const val NOW = "eksiengel-history-sync-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 15 minutes, the shortest period WorkManager allows. */
        fun schedulePeriodic(wm: WorkManager) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistorySyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(network)
                    .build(),
            )
        }

        /** As soon as the network allows. KEEP: one already waiting covers this request too. */
        fun syncNow(wm: WorkManager) {
            wm.enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HistorySyncWorker>().setConstraints(network).build(),
            )
        }
    }
}
