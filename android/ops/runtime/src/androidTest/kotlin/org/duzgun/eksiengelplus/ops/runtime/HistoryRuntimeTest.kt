package org.duzgun.eksiengelplus.ops.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.HistoryOutboxEntity
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.database.QueuedTaskEntity
import org.duzgun.eksiengelplus.datastore.Stores
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.ReadPacer
import org.duzgun.eksiengelplus.ops.engine.RelationOutcome
import org.duzgun.eksiengelplus.ops.engine.StopSignal
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History on a device: outcomes commit with their checkpoint, and the sync pass
 * speaks the server's protocol against a scripted server.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRuntimeTest {

    private lateinit var db: EksiDatabase
    private lateinit var commands: OperationCommandBus
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val request = OperationRequest(BanSource.FAV, BanMode.BAN, TargetType.USER, authorNick = "coh", entryId = 1)
    private val requestJson = Json.encodeToString(OperationRequest.serializer(), request)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EksiDatabase::class.java).build()
        commands = InMemoryCommandBus()
        server = MockWebServer().also { it.start() }
        dir = File(context.cacheDir, "history-test-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::server.isInitialized) server.shutdown()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private suspend fun seedCheckpoint(id: String, state: OperationState = OperationState.RUNNING) {
        db.checkpoints().upsert(
            OperationCheckpointEntity(
                operationId = id, type = request.source.name, state = state.name, cursorJson = "{}",
                processed = 2, total = 5, successful = 2, failed = 0, startedAt = 10, updatedAt = 10,
                workRequestId = null, requestJson = requestJson,
            ),
        )
    }

    private fun context(history: HistoryRecording?) = RoomOperationContext(
        operationId = "op1",
        request = request,
        startCursor = OperationCursor(),
        db = db,
        commands = commands,
        budget = ForegroundBudget().apply { resume(0) },
        actionPacer = ActionPacer(sleep = {}),
        readPacer = ReadPacer(sleep = {}),
        history = history,
    )

    // --- outcomes and the checkpoint --------------------------------------------------

    @Test fun outcomesCommitWithTheirCheckpoint() = runTest {
        seedCheckpoint("op1")
        val ctx = context(HistoryRecording("op1", 7, "me"))
        ctx.recordOutcome(BanMode.BAN, TargetType.USER, 11, RelationOutcome.OK, "onbir")
        ctx.recordOutcome(BanMode.BAN, TargetType.MUTE, 11, RelationOutcome.FAIL, "onbir")
        assertThat(db.historyOutbox().count()).isEqualTo(0)
        ctx.checkpoint(OperationCursor(index = 1, processed = 1))
        val rows = db.historyOutbox().oldest(10)
        assertThat(rows.map { it.kind to it.result }).containsExactly("BLOCK" to "OK", "MUTE" to "FAIL").inOrder()
        assertThat(rows.all { it.taskId == "op1" && it.actorId == 7L && it.targetNick == "onbir" }).isTrue()
    }

    @Test fun processDeathBeforeTheCheckpointLeavesNothing() = runTest {
        seedCheckpoint("op1")
        // The request landed, the checkpoint never did: the target is re-run on
        // resume, and only that attempt is recorded -- never both.
        context(HistoryRecording("op1", 7, "me")).recordOutcome(BanMode.BAN, TargetType.USER, 11, RelationOutcome.OK)
        assertThat(db.historyOutbox().count()).isEqualTo(0)
        val resumed = context(HistoryRecording("op1", 7, "me"))
        resumed.recordOutcome(BanMode.BAN, TargetType.USER, 11, RelationOutcome.SKIPPED)
        resumed.checkpoint(OperationCursor(index = 1, processed = 1))
        assertThat(db.historyOutbox().oldest(10).map { it.result }).containsExactly("SKIPPED")
    }

    @Test fun aCancelledRunStillRecordsWhatItDid() = runTest {
        val ctx = context(HistoryRecording("op1", 7, "me"))
        ctx.recordOutcome(BanMode.BAN, TargetType.USER, 11, RelationOutcome.OK)
        var stopped = false
        try {
            ctx.checkpoint(OperationCursor(index = 1))
        } catch (e: StopSignal) {
            stopped = true
        }
        assertThat(stopped).isTrue()
        assertThat(db.historyOutbox().count()).isEqualTo(1)
        assertThat(db.checkpoints().get("op1")).isNull()
    }

    @Test fun historyOffWritesNothing() = runTest {
        seedCheckpoint("op1")
        val ctx = context(history = null)
        ctx.recordOutcome(BanMode.BAN, TargetType.USER, 11, RelationOutcome.OK)
        ctx.checkpoint(OperationCursor(index = 1))
        assertThat(db.historyOutbox().count()).isEqualTo(0)
    }

    // --- the sync pass ---------------------------------------------------------------

    private suspend fun sync(syncHistory: Boolean = true): HistorySync {
        val config = Stores.configRepositoryAt(File(dir, "config.json"))
        config.update { it.copy(syncHistory = syncHistory) }
        val identity = Stores.identityRepositoryAt(File(dir, "identity.json"))
        identity.rememberEksiUser("me", 7)
        return HistorySync(
            db = db,
            config = config,
            identity = identity,
            client = HistoryClient(OkHttpClient(), server.url("/api/action/").toString(), "test-key"),
            commands = commands,
            describe = { "favlayanlar (coh)" },
            deviceLabel = "test device",
            appVersion = "0.6.0",
        )
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun outboxRow(uuid: String) = HistoryOutboxEntity(
        eventUuid = uuid, taskId = "t1", kind = "BLOCK", direction = "ADD", result = "OK",
        actorId = 7, actorNick = "me", targetId = 11, targetNick = null, occurredAt = 1_700_000_000_000,
    )

    @Test fun aSyncPassEnrolsPushesAndPulls() = runTest {
        seedCheckpoint("t1")
        db.historyOutbox().addAll(listOf(outboxRow("e1"), outboxRow("e2")))
        server.enqueue(json(201, """{"device_token":"tok","join_key":"j"}"""))
        server.enqueue(
            json(
                200,
                """{"tasks":[{"id":"t1","result":"ok","epoch":0,"status":"RUNNING","cancel_requested":false}],
                   "events_accepted":["e1","e2"],"cursor":1}""",
            ),
        )
        server.enqueue(
            json(
                200,
                """{"tasks":[{"id":"r1","is_mine":false,"status":"RUNNING","description":"Listeden Engelle",
                   "owner":{"label":"Chrome · Linux","client":"EXTENSION"},"performed":3,"planned":9,
                   "cancel_requested":false,"params":{}}],"next_cursor":4,"has_more":false}""",
            ),
        )
        val s = sync()
        assertThat(s.syncOnce(force = true)).isTrue()

        val enroll = server.takeRequest()
        assertThat(enroll.path).isEqualTo("/api/sync/enroll/")
        assertThat(enroll.getHeader("X-API-Key")).isEqualTo("test-key")
        val push = server.takeRequest()
        assertThat(push.getHeader("Authorization")).isEqualTo("Device tok")
        val body = Json.parseToJsonElement(push.body.readUtf8()).jsonObject
        assertThat(body["events"]!!.jsonArray).hasSize(2)
        val task = body["tasks"]!!.jsonArray.single().jsonObject
        assertThat(task["status"]!!.jsonPrimitive.content).isEqualTo("RUNNING")
        assertThat(task["actor_eksi_id"]!!.jsonPrimitive.content).isEqualTo("7")
        assertThat(task["params"]!!.jsonObject["android_request"]).isNotNull()
        assertThat(server.takeRequest().path).isEqualTo("/api/sync/pull/?cursor=0")

        assertThat(db.historyOutbox().count()).isEqualTo(0)
        assertThat(db.historyTasks().get("t1")!!.pushed).isTrue()
        val remote = db.remoteTasks().observeAll().first().single()
        assertThat(remote.ownerLabel).isEqualTo("Chrome · Linux")
        // The extension's tasks carry no request this client can run.
        assertThat(remote.requestJson).isNull()
        assertThat(db.historySyncState().get()!!.cursor).isEqualTo(4)
    }

    @Test fun aStaleEpochStopsTheRunAndSilencesTheTask() = runTest {
        seedCheckpoint("t1")
        server.enqueue(json(201, """{"device_token":"tok"}"""))
        server.enqueue(
            json(200, """{"tasks":[{"id":"t1","result":"conflict","epoch":1,"status":"QUEUED"}],"events_accepted":[]}"""),
        )
        server.enqueue(json(200, """{"tasks":[],"next_cursor":1,"has_more":false}"""))
        sync().syncOnce(force = true)
        assertThat(db.historyTasks().get("t1")!!.status).isEqualTo(HistoryWire.LOST)
        assertThat(commands.peek("t1")).isEqualTo(OperationCommand.STOP)
    }

    @Test fun aCancelFromAnotherDeviceDropsTheQueuedTaskAndReportsIt() = runTest {
        db.queuedTasks().enqueue(
            QueuedTaskEntity(
                seq = 1, banSourcePk = 2, banModePk = 1, targetTypePk = 1, clickSourcePk = null,
                payloadJson = requestJson, status = "QUEUED", enqueuedAt = 5, taskId = "q1",
            ),
        )
        server.enqueue(json(201, """{"device_token":"tok"}"""))
        server.enqueue(
            json(200, """{"tasks":[{"id":"q1","result":"ok","epoch":0,"status":"QUEUED","cancel_requested":true}],"events_accepted":[]}"""),
        )
        server.enqueue(
            json(200, """{"tasks":[{"id":"q1","result":"ok","epoch":0,"status":"CANCELLED","cancel_requested":true}],"events_accepted":[]}"""),
        )
        server.enqueue(json(200, """{"tasks":[],"next_cursor":2,"has_more":false}"""))
        sync().syncOnce(force = true)

        assertThat(db.queuedTasks().all()).isEmpty()
        server.takeRequest()
        server.takeRequest()
        // The same pass reports the cancel; no second sync is needed for it.
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(second["tasks"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
            .isEqualTo("CANCELLED")
        assertThat(server.takeRequest().path).startsWith("/api/sync/pull/")
    }

    @Test fun offMeansNothingLeavesAndNothingWaits() = runTest {
        db.historyOutbox().addAll(listOf(outboxRow("e1")))
        val s = sync(syncHistory = false)
        assertThat(s.syncOnce(force = true)).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(db.historyOutbox().count()).isEqualTo(0)
    }

    @Test fun serverDownKeepsTheOutboxAndBacksOff() = runTest {
        db.historyOutbox().addAll(listOf(outboxRow("e1")))
        server.enqueue(json(201, """{"device_token":"tok"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val s = sync()
        assertThat(s.syncOnce(force = true)).isFalse()
        assertThat(db.historyOutbox().count()).isEqualTo(1)
        assertThat(db.historySyncState().get()!!.backoffUntil).isGreaterThan(System.currentTimeMillis())
        // Backed off: an ordinary pass does not even try.
        val before = server.requestCount
        s.syncOnce()
        assertThat(server.requestCount).isEqualTo(before)
    }
}
