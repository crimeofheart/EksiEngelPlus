package org.duzgun.eksiengelplus.ops.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.duzgun.eksiengelplus.eksi.client.EksiHeadersInterceptor
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The loop every source shares: happy path, a 429 mid-run, and a session expiry
 * mid-run. Those three cover almost every way a real run ends.
 */
class TargetRunnerTest {

    private lateinit var server: MockWebServer
    private lateinit var runner: TargetRunner

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = OkHttpClient.Builder()
            .addInterceptor(EksiHeadersInterceptor("test-ua"))
            .followRedirects(false)
            .build()
        val base = { server.url("/").toString().trimEnd('/') }
        runner = TargetRunner(RelationClient(http, base), ScrapeClient(http, baseUrlProvider = base))
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String = "0") =
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun ctx(signalAt: Pair<Int, RuntimeException>? = null) = FakeContext(
        OperationRequest(BanSource.LIST, BanMode.BAN),
        signalAt = signalAt,
    )

    private fun targets(n: Int) = (1L..n).map { Target("u$it", it) }

    @Test fun `every target is acted on and counted`() = runTest {
        repeat(3) { ok() }
        val c = ctx()
        assertThat(runner.applyToAll(c, targets(3))).isEqualTo(OperationOutcome.COMPLETED)
        assertThat(c.lastCheckpoint!!.processed).isEqualTo(3)
        assertThat(c.lastCheckpoint!!.successful).isEqualTo(3)
        assertThat(c.actionPermits).isEqualTo(3)   // every mutation paced
    }

    @Test fun `an already-blocked target counts as success, not failure`() = runTest {
        ok("0"); ok("2")
        val c = ctx()
        runner.applyToAll(c, targets(2))
        assertThat(c.lastCheckpoint!!.successful).isEqualTo(2)
        assertThat(c.lastCheckpoint!!.failed).isEqualTo(0)
    }

    @Test fun `a 429 mid-run penalises the pacer and retries the same target`() = runTest {
        ok()
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "30"))
        ok()
        ok()
        val c = ctx()
        assertThat(runner.applyToAll(c, targets(3))).isEqualTo(OperationOutcome.COMPLETED)
        // The delay reached the pacer rather than being slept on inside the client.
        assertThat(c.penalties).containsExactly(31)
        assertThat(c.lastCheckpoint!!.successful).isEqualTo(3)
        // Four calls for three targets: the rate-limited one was attempted twice.
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `session expiry mid-run parks the operation at that target`() = runTest {
        ok()
        server.enqueue(MockResponse().setResponseCode(403))
        ok()
        val c = ctx()
        assertThat(runner.applyToAll(c, targets(3))).isEqualTo(OperationOutcome.PAUSED_AUTH)
        // Stops at the failing index so a resumed run retries it rather than skipping.
        assertThat(c.lastCheckpoint!!.index).isEqualTo(1)
        // Third target never attempted: no point burning budget with no session.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `an unknown code fails that target without stopping the run`() = runTest {
        ok(); ok("7"); ok()
        val c = ctx()
        assertThat(runner.applyToAll(c, targets(3))).isEqualTo(OperationOutcome.COMPLETED)
        assertThat(c.lastCheckpoint!!.successful).isEqualTo(2)
        assertThat(c.lastCheckpoint!!.failed).isEqualTo(1)
    }

    @Test fun `self-target fails without retrying`() = runTest {
        ok("4"); ok()
        val c = ctx()
        runner.applyToAll(c, targets(2))
        assertThat(c.lastCheckpoint!!.failed).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(2)   // no retry of the self-target
    }

    @Test fun `pause stops cleanly and records where to resume`() = runTest {
        repeat(5) { ok() }
        val c = ctx(signalAt = 3 to PauseSignal())
        assertThat(runner.applyToAll(c, targets(5))).isEqualTo(OperationOutcome.PAUSED)
        // Two units done before the pause landed; resume continues from index 2.
        assertThat(c.lastCheckpoint!!.index).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `stop is distinguished from pause`() = runTest {
        repeat(5) { ok() }
        val c = ctx(signalAt = 2 to StopSignal())
        assertThat(runner.applyToAll(c, targets(5))).isEqualTo(OperationOutcome.STOPPED)
    }

    @Test fun `budget exhaustion parks for a later continuation`() = runTest {
        repeat(5) { ok() }
        val c = ctx(signalAt = 4 to BudgetExhaustedSignal())
        assertThat(runner.applyToAll(c, targets(5))).isEqualTo(OperationOutcome.PAUSED_BUDGET)
        assertThat(c.lastCheckpoint!!.index).isEqualTo(3)
    }

    @Test fun `a resumed run does not redo completed work`() = runTest {
        ok(); ok()
        val c = FakeContext(
            OperationRequest(BanSource.LIST, BanMode.BAN),
            startCursor = OperationCursor(index = 3, processed = 3, successful = 3),
        )
        runner.applyToAll(c, targets(5))
        assertThat(server.requestCount).isEqualTo(2)   // only targets 4 and 5
        assertThat(c.lastCheckpoint!!.processed).isEqualTo(5)
    }

    @Test fun `a target with no id is resolved before acting`() = runTest {
        // /biri/<nick> then the mutation.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""<input id="who" value="4242">"""))
        ok()
        val c = ctx()
        runner.applyToAll(c, listOf(Target("someone", null)))
        assertThat(c.readPermits).isAtLeast(1)
        server.takeRequest()
        assertThat(server.takeRequest().path).contains("/userrelation/addrelation/4242")
    }

    @Test fun `an unresolvable nick is counted failed and the run continues`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>no who element</html>"))
        ok()
        val c = ctx()
        assertThat(runner.applyToAll(c, listOf(Target("ghost", null), Target("real", 9L))))
            .isEqualTo(OperationOutcome.COMPLETED)
        assertThat(c.lastCheckpoint!!.failed).isEqualTo(1)
        assertThat(c.lastCheckpoint!!.successful).isEqualTo(1)
    }
}
