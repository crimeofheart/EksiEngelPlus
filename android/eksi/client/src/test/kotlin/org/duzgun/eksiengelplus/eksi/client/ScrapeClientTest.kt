package org.duzgun.eksiengelplus.eksi.client

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.duzgun.eksiengelplus.model.TargetType
import org.junit.After
import org.junit.Before
import org.junit.Test

class ScrapeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ScrapeClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = OkHttpClient.Builder()
            .addInterceptor(EksiHeadersInterceptor("test-ua"))
            .followRedirects(false)
            .build()
        client = ScrapeClient(http, baseUrlProvider = { server.url("/").toString().trimEnd('/') })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) =
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun relPage(isLast: Boolean, n: Int, from: Int = 1) = json(
        """{"Relations":{"IsLast":$isLast,"Items":[""" +
            (from until from + n).joinToString(",") { """{"Id":$it,"Nick":{"Value":"u$it"}}""" } +
            "]}}",
    )

    @Test fun `the first request uses pageIndex 1, never 0`() = runTest {
        // pageIndex=0 answers HTTP 500 with an empty body, measured on device.
        relPage(isLast = true, n = 0)
        client.allRelations(TargetType.USER)
        assertThat(server.takeRequest().path)
            .isEqualTo("/relation-list?relationType=m&pageIndex=1")
    }

    @Test fun `requesting page zero is rejected before a request is made`() = runTest {
        try {
            client.relationPage(TargetType.USER, 0)
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("1-based")
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `relation pagination terminates on IsLast`() = runTest {
        relPage(isLast = false, n = 25, from = 1)
        relPage(isLast = false, n = 25, from = 26)
        relPage(isLast = true, n = 3, from = 51)
        val all = client.allRelations(TargetType.MUTE)
        assertThat(all.nicks).hasSize(53)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(server.takeRequest().path).contains("pageIndex=1")
        assertThat(server.takeRequest().path).contains("pageIndex=2")
        assertThat(server.takeRequest().path).contains("pageIndex=3")
    }

    @Test fun `relation list carries the mute relation code`() = runTest {
        relPage(isLast = true, n = 0)
        client.allRelations(TargetType.MUTE)
        assertThat(server.takeRequest().path).contains("relationType=u")
    }

    @Test fun `follow pagination terminates on an empty array, not IsLast`() = runTest {
        // /follower and /following have no IsLast field at all.
        json("""[{"Id":1,"Nick":{"Value":"a"}}]""")
        json("""[{"Id":2,"Nick":{"Value":"0 derece"},"IsBuddy":true}]""")
        json("[]")
        val all = client.allFollow(FollowEndpoint.FOLLOWER, "coh")
        assertThat(all).hasSize(2)
        assertThat(all[1].nick.value).isEqualTo("0 derece")
        assertThat(all[1].isBuddy).isTrue()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `an empty first follow page yields nothing and stops`() = runTest {
        json("[]")
        assertThat(client.allFollow(FollowEndpoint.FOLLOWING, "coh")).isEmpty()
        assertThat(server.takeRequest().path).isEqualTo("/following?nick=coh&pageIndex=1")
    }

    @Test fun `html from a json endpoint is session expiry, not a parse error`() = runTest {
        json("<!DOCTYPE html><html><body>giriş</body></html>")
        try {
            client.relationPage(TargetType.USER, 1)
            throw AssertionError("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            assertThat(e.reason).contains("html")
        }
    }

    @Test fun `a redirect to giris surfaces as session expiry`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "https://eksisozluk.com/giris"),
        )
        try {
            client.relationPage(TargetType.USER, 1)
            throw AssertionError("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            assertThat(e.reason).contains("302")
        }
    }

    @Test fun `own nick is read from the homepage avatar`() = runTest {
        json(
            """<html><body><div class="mobile-notification-icons">
               <span class="mobile-only"><a title="coh 81"></a></span></div></body></html>""",
        )
        assertThat(client.ownNick()).isEqualTo("coh-81")
    }

    @Test fun `a full follow page is not treated as the end`() = runTest {
        // The follow endpoints cap at 100, not 25, and carry no IsLast -- so only
        // an empty array ends iteration. A client that stopped at a short page,
        // or assumed 25, would silently truncate the list.
        val full = (1..100).joinToString(",") { """{"Id":$it,"Nick":{"Value":"u$it"}}""" }
        json("[$full]")
        json("""[{"Id":101,"Nick":{"Value":"tail"}}]""")
        json("[]")
        val all = client.allFollow(FollowEndpoint.FOLLOWER, "coh")
        assertThat(all).hasSize(101)
        assertThat(server.requestCount).isEqualTo(3)
    }

    // ------------------------------------------------------- title pagination

    private fun topicPage(vararg nicks: String) = json(
        nicks.joinToString("") { nick ->
            """<li data-author="$nick" data-author-id="7"><div class="content">x</div></li>"""
        }.let { "<html><body><ul id=\"entry-item-list\">$it</ul></body></html>" },
    )

    @Test fun `a 404 past the last page ends pagination instead of the run`() = runTest {
        // Measured on device: /yeni-parti--473428?a=dailynice&p=2 answers 404 on a
        // title with one page of daily entries. This used to throw, so the whole
        // operation failed having acted on nobody -- with page one already read.
        topicPage("alice", "bob")
        server.enqueue(MockResponse().setResponseCode(404))

        val authors = client.allTopicAuthors("yeni-parti", 473428, lastDayOnly = true)

        assertThat(authors.map { it.nick }).containsExactly("alice", "bob")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a 404 on the first page is still an error`() = runTest {
        // Every real title renders page one, so this means the slug or the id is
        // wrong. Swallowing it would turn a broken request into an operation that
        // silently does nothing.
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.allTopicAuthors("nope", 1)
            throw AssertionError("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertThat(e.code).isEqualTo(404)
        }
    }

    @Test fun `a 500 mid-pagination is not mistaken for the end`() = runTest {
        // The extension calls every error the last page, so a server blip reads
        // as a complete list and it acts on a truncated set. Only 404 ends this.
        topicPage("alice")
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            client.allTopicAuthors("t", 1)
            throw AssertionError("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertThat(e.code).isEqualTo(500)
        }
    }

    @Test fun `an empty page still ends pagination`() = runTest {
        topicPage("alice")
        topicPage()

        val authors = client.allTopicAuthors("t", 1)

        assertThat(authors.map { it.nick }).containsExactly("alice")
    }

    @Test fun `observed page sizes differ by endpoint family`() {
        assertThat(ScrapeClient.RELATION_PAGE_SIZE).isEqualTo(25)
        assertThat(ScrapeClient.FOLLOW_PAGE_SIZE).isEqualTo(100)
    }
}
