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
import org.duzgun.eksiengelplus.model.TargetType
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The half of a run that happens before there is a target list.
 *
 * Collecting followers is many requests and no progress, and it used to be
 * uninterruptible: one read permit for the whole walk, no ensureActive()
 * anywhere in it, so Duraklat and Durdur had nobody reading the command bus
 * until the last page was in. A run started by mistake against an account with
 * thousands of followers could not be stopped at all, showed 0 / 0 while it
 * could not, and -- because a checkpoint that is neither terminal nor cancelled
 * counts as live -- queued every later operation behind itself.
 *
 * These are the three properties that make that impossible, checked at the level
 * the user feels them: pages stop being fetched, pages are paced, and the count
 * reaches a surface.
 */
class CollectionPhaseTest {

    private lateinit var server: MockWebServer
    private lateinit var scrape: ScrapeClient
    private lateinit var runner: TargetRunner

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = OkHttpClient.Builder()
            .addInterceptor(EksiHeadersInterceptor("test-ua"))
            .followRedirects(false)
            .build()
        val base = { server.url("/").toString().trimEnd('/') }
        scrape = ScrapeClient(http, baseUrlProvider = base)
        runner = TargetRunner(RelationClient(http, base), scrape)
    }

    @After fun tearDown() = server.shutdown()

    /** One page of followers, ids and nicks distinct across pages. */
    private fun followPage(n: Int, from: Int = 1) = server.enqueue(
        MockResponse().setResponseCode(200).setBody(
            "[" + (from until from + n).joinToString(",") {
                """{"Id":$it,"Nick":{"Value":"u$it"},"IsBuddy":false,"IsBlocked":false}"""
            } + "]",
        ),
    )

    private fun followRequest() = OperationRequest(
        source = BanSource.FOLLOW,
        mode = BanMode.BAN,
        targetType = TargetType.USER,
        authorNick = "coh",
    )

    @Test fun `durdur during collection stops the walk on the page it is pressed`() = runTest {
        followPage(n = 2)
        followPage(n = 2, from = 3)
        followPage(n = 0)

        // Before the second page: the first is already paid for, the rest are not.
        val ctx = FakeContext(followRequest(), signalAt = 2 to StopSignal())

        try {
            FollowActionTask(runner, scrape).run(ctx)
            throw AssertionError("expected the stop to end the run")
        } catch (expected: StopSignal) {
            // The run ends here rather than after every remaining page, which is
            // the whole point: the user waited for one request, not for all of
            // them.
        }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `every page takes its own read permit`() = runTest {
        // One permit for an entire walk paced nothing: 120 requests left in a
        // burst and the rate limit was hit by the collection phase alone.
        followPage(n = 2)
        followPage(n = 0)

        // Stopped once collection is done, so the assertion is about the walk
        // and not about what the action loop would have gone on to do.
        val ctx = FakeContext(followRequest(), signalAt = 3 to StopSignal())
        assertThat(FolloweesActionTask(runner, scrape).run(ctx))
            .isEqualTo(OperationOutcome.STOPPED)

        // Two fetched pages, the second of which terminates the walk.
        assertThat(ctx.readPermits).isEqualTo(2)
    }

    @Test fun `collection reports how many it has found so far`() = runTest {
        followPage(n = 3)
        followPage(n = 2, from = 4)
        followPage(n = 0)

        val ctx = FakeContext(followRequest(), signalAt = 4 to StopSignal())
        assertThat(FollowActionTask(runner, scrape).run(ctx))
            .isEqualTo(OperationOutcome.STOPPED)

        // Published before each request, so the screen can say "toplanıyor · N"
        // instead of the 0 / 0 that a wedged run also shows.
        assertThat(ctx.collected).containsExactly(0, 3, 5).inOrder()
    }
}
