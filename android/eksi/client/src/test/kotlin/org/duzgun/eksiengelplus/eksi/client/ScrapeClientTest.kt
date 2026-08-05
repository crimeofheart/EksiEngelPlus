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
}
