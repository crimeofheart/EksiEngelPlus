package org.duzgun.eksiengelplus.eksi.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParserUnitTest {

    @Test
    fun `favouriter fragment strips the at sign and slugifies`() {
        // Shape and sample values recorded on device: 24 anchors, @-prefixed,
        // nicks containing spaces.
        val html = """
            <a href="/biri/x">@ben ne diyorum sen ne diyorsun</a>
            <a href="/biri/y">@cokhaklisininci</a>
            <a href="/biri/z">@dr jacobu bile kullanmislar</a>
        """.trimIndent()
        assertThat(EksiHtmlParser().parseFavouriters(html)).containsExactly(
            "ben-ne-diyorum-sen-ne-diyorsun",
            "cokhaklisininci",
            "dr-jacobu-bile-kullanmislar",
        ).inOrder()
    }

    @Test
    fun `anchors without an at prefix are ignored`() {
        // The trailing "çaylak" link is not a nick (scrapingHandler.js:170-172).
        val html = """<a>@real</a><a>çaylak</a>"""
        assertThat(EksiHtmlParser().parseFavouriters(html)).containsExactly("real")
    }

    @Test
    fun `author id of zero is treated as absent`() {
        val doc = EksiHtmlParser().parse("""<input id="who" value="0">""")
        assertThat(EksiHtmlParser().parseAuthorId(doc)).isNull()
    }

    @Test
    fun `registration date falls back to a bounded text scan`() {
        val doc = EksiHtmlParser().parse(
            """<html><body><div>kayıt tarihi</div><span>aralık 2018</span>
               <li>kayıt tarihi: 30.03.2011</li></body></html>""",
        )
        assertThat(EksiHtmlParser().parseRegistrationDate(doc)).isNotNull()
    }

    @Test
    fun `topic authors are de-duplicated by nick`() {
        val doc = EksiHtmlParser().parse(
            """<ul id="entry-item-list">
                 <li data-author="ali veli" data-author-id="1"><div class="content">a</div></li>
                 <li data-author="ali veli" data-author-id="1"><div class="content">b</div></li>
                 <li data-author="baska" data-author-id="2"><div class="content">c</div></li>
               </ul>""",
        )
        val authors = EksiHtmlParser().parseTopicAuthors(doc)
        assertThat(authors.map { it.nick }).containsExactly("ali-veli", "baska")
    }
}
