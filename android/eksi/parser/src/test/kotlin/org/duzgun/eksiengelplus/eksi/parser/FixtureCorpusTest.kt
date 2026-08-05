package org.duzgun.eksiengelplus.eksi.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs every selector against the HTML captured from the live site during
 * android-spike (docs/fixtures/eksisozluk/), under all three user agents.
 *
 * Asserting against captured bytes rather than against the extension source is
 * the whole point: it proves the parser matches what the SITE does, not what the
 * extension assumes. The spike already found one place those differ.
 *
 * The corpus is logged-out, so auth-gated targets are covered by
 * AuthGatedShapeTest instead.
 */
class FixtureCorpusTest {

    private val root = File(System.getProperty("eksi.fixtures") ?: "").resolve("logged-out")
    private val uas = listOf("desktop", "android_chrome", "webview")

    private fun doc(page: String, ua: String) =
        root.resolve("$page.$ua.html").let {
            assumeTrue("fixture missing: ${it.path}", it.isFile)
            EksiHtmlParser().parse(it.readText())
        }

    private fun each(page: String, block: (String, org.jsoup.nodes.Document) -> Unit) =
        uas.forEach { ua -> block(ua, doc(page, ua)) }

    @Test
    fun `webview and android chrome are byte identical`() {
        listOf("home", "profile", "entry", "title").forEach { page ->
            val a = root.resolve("$page.android_chrome.html")
            val w = root.resolve("$page.webview.html")
            assumeTrue(a.isFile && w.isFile)
            assertWithMessage(page).that(w.readBytes()).isEqualTo(a.readBytes())
        }
    }

    @Test
    fun `profile registration date parses under every user agent`() {
        each("profile") { ua, d ->
            val date = EksiHtmlParser().parseRegistrationDate(d)
            assertWithMessage(ua).that(date).isNotNull()
        }
    }

    @Test
    fun `entry metadata resolves under every user agent`() {
        each("entry") { ua, d ->
            val meta = EksiHtmlParser().parseEntry(29256704L, d)
            assertWithMessage("$ua authorId").that(meta.authorId).isNotNull()
            assertWithMessage("$ua authorNick").that(meta.authorNick).isNotEmpty()
            assertWithMessage("$ua titleId").that(meta.titleId).isNotNull()
        }
    }

    @Test
    fun `title page yields the same authors under every user agent`() {
        val counts = uas.map { ua -> EksiHtmlParser().parseTopicAuthors(doc("title", ua)).size }
        assertWithMessage("author counts per UA: $counts").that(counts.distinct()).hasSize(1)
        assertThat(counts.first()).isGreaterThan(0)
    }

    @Test
    fun `ul toggles-menu is dead on every page and user agent`() {
        // Recorded so nobody reintroduces a dependency on it. Zero everywhere,
        // logged in and out -- see eksisozluk-client-contract.
        listOf("title", "entry").forEach { page ->
            each(page) { ua, d ->
                assertWithMessage("$page/$ua")
                    .that(d.select(Selectors.TOGGLES_MENU_DEAD)).isEmpty()
            }
        }
    }

    @Test
    fun `own nick is absent when logged out and health records the miss`() {
        val p = EksiHtmlParser()
        assertThat(p.parseOwnNick(doc("home", "webview"))).isNull()
        assertThat(p.health().snapshot()).containsKey(Selectors.OWN_NICK)
    }

    @Test
    fun `author id is absent when logged out`() {
        // #who does not render at all for anonymous visitors -- zero occurrences of
        // the string, confirmed during the spike. Auth-gating, not UA divergence.
        each("profile") { ua, d ->
            assertWithMessage(ua).that(EksiHtmlParser().parseAuthorId(d)).isNull()
        }
    }
}
