package org.duzgun.eksiengelplus.eksi.client

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.TargetType
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Every RelationResult branch, including the codes android-spike observed on a
 * real device. Response shapes are transcribed from that run.
 */
class RelationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelationClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = OkHttpClient.Builder()
            .addInterceptor(EksiHeadersInterceptor("test-ua"))
            .followRedirects(false)     // a login redirect must be observable
            .build()
        client = RelationClient(http) { server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() = server.shutdown()

    private fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val r = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { (k, v) -> r.addHeader(k, v) }
        server.enqueue(r)
    }

    private suspend fun block() = client.perform(BanMode.BAN, TargetType.USER, 42L)
    private suspend fun unblock() = client.perform(BanMode.UNDOBAN, TargetType.USER, 42L)

    @Test fun `ban code 0 is success`() = runTest {
        enqueue(200, "0"); assertThat(block()).isEqualTo(RelationResult.Success)
    }

    @Test fun `ban code 2 means already in that state`() = runTest {
        enqueue(200, "2"); assertThat(block()).isEqualTo(RelationResult.AlreadyInState)
    }

    @Test fun `ban code 4 is self-target, distinct from a generic failure`() = runTest {
        // Observed when the target id equals the authenticated user's own id.
        enqueue(200, "4"); assertThat(block()).isEqualTo(RelationResult.SelfTarget)
    }

    @Test fun `an unknown numeric code fails and records itself`() = runTest {
        enqueue(200, "7")
        val r = block()
        assertThat(r).isInstanceOf(RelationResult.Failed::class.java)
        assertThat((r as RelationResult.Failed).code).isEqualTo(7)
    }

    @Test fun `undoban parses the object and ignores unknown fields`() = runTest {
        // count is undocumented and was present on device.
        enqueue(200, """{"result":true,"count":0}""")
        assertThat(unblock()).isEqualTo(RelationResult.Success)
    }

    @Test fun `undoban with result false fails`() = runTest {
        enqueue(200, """{"result":false}""")
        assertThat(unblock()).isInstanceOf(RelationResult.Failed::class.java)
    }

    @Test fun `429 with Retry-After returns that delay plus one second`() = runTest {
        enqueue(429, "", "Retry-After" to "30")
        assertThat(block()).isEqualTo(RelationResult.RateLimited(31))
    }

    @Test fun `429 without Retry-After falls back to 65 seconds`() = runTest {
        enqueue(429)
        assertThat(block()).isEqualTo(RelationResult.RateLimited(65))
    }

    @Test fun `429 with an HTTP-date Retry-After falls back rather than parsing it`() = runTest {
        // The date form is explicitly unsupported (relationHandler.js:157).
        enqueue(429, "", "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")
        assertThat(block()).isEqualTo(RelationResult.RateLimited(65))
    }

    @Test fun `redirect to giris is session expiry, not a failure`() = runTest {
        enqueue(302, "", "Location" to "https://eksisozluk.com/giris?ReturnUrl=%2f")
        assertThat(block()).isEqualTo(RelationResult.SessionExpired)
    }

    @Test fun `403 is session expiry`() = runTest {
        enqueue(403); assertThat(block()).isEqualTo(RelationResult.SessionExpired)
    }

    @Test fun `html where a code was expected is session expiry`() = runTest {
        enqueue(200, "<!DOCTYPE html><html><body>giriş</body></html>")
        assertThat(block()).isEqualTo(RelationResult.SessionExpired)
    }

    @Test fun `request shape matches the contract`() = runTest {
        enqueue(200, "0")
        client.perform(BanMode.BAN, TargetType.MUTE, 99L)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/userrelation/addrelation/99?r=u")
        assertThat(req.body.readUtf8()).isEqualTo("id=99")
        assertThat(req.getHeader("x-requested-with")).isEqualTo("XMLHttpRequest")
        assertThat(req.getHeader("Origin")).isNull()
    }

    @Test fun `undoban uses the removerelation segment`() = runTest {
        enqueue(200, """{"result":true}""")
        client.perform(BanMode.UNDOBAN, TargetType.TITLE, 5L)
        assertThat(server.takeRequest().path).isEqualTo("/userrelation/removerelation/5?r=i")
    }

    @Test fun `the client never sleeps on a 429`() = runTest {
        enqueue(429, "", "Retry-After" to "600")
        val start = System.currentTimeMillis()
        val r = block()
        // A ten-minute delay must be returned for a shared pacer to apply, not
        // absorbed here -- otherwise a global penalty is impossible.
        assertThat(r).isEqualTo(RelationResult.RateLimited(601))
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000)
    }
}
