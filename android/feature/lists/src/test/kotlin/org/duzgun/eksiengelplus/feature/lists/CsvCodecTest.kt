package org.duzgun.eksiengelplus.feature.lists

import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.model.ListType
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class CsvCodecTest {

    private fun export(rows: List<CsvCodec.Row>): String =
        ByteArrayOutputStream().also { CsvCodec.writeExport(rows, it) }.toString(Charsets.UTF_8.name())

    private fun day(iso: String) = LocalDate.parse(iso).toEpochDay()

    @Test
    fun `headerless file keeps its first row`() {
        val result = CsvCodec.parseImport("birisi,2010-04-01\nbaskasi,")

        assertThat(result.hadHeader).isFalse()
        assertThat(result.rows.map { it.nick }).containsExactly("birisi", "baskasi").inOrder()
    }

    @Test
    fun `username header is dropped`() {
        val result = CsvCodec.parseImport("Username,RegistrationDate\nbirisi,2010-04-01")

        assertThat(result.hadHeader).isTrue()
        assertThat(result.rows.map { it.nick }).containsExactly("birisi")
    }

    @Test
    fun `quoted comma stays inside the field`() {
        val result = CsvCodec.parseImport("\"nick, with comma\",2011-02-03")

        assertThat(result.rows).containsExactly(CsvCodec.Row("nick, with comma", day("2011-02-03")))
    }

    @Test
    fun `turkish long form parses`() {
        val result = CsvCodec.parseImport("birisi,ağustos 2026")

        assertThat(result.rows.single().epochDay).isEqualTo(day("2026-08-01"))
    }

    @Test
    fun `unparseable date keeps the nick`() {
        val result = CsvCodec.parseImport("birisi,dün")

        assertThat(result.rows).containsExactly(CsvCodec.Row("birisi", null))
    }

    @Test
    fun `blank date column yields no date`() {
        val result = CsvCodec.parseImport("Username,RegistrationDate\nbirisi,\nbaskasi,")

        assertThat(result.rows.map { it.epochDay }).containsExactly(null, null)
    }

    @Test
    fun `crlf input parses as cleanly as lf`() {
        val result = CsvCodec.parseImport("Username,RegistrationDate\r\nbirisi,2010-04-01\r\n")

        assertThat(result.rows).containsExactly(CsvCodec.Row("birisi", day("2010-04-01")))
    }

    @Test
    fun `blank first field is skipped and counted`() {
        val result = CsvCodec.parseImport("birisi,2010-04-01\n,2011-01-01\nbaskasi,")

        assertThat(result.rows.map { it.nick }).containsExactly("birisi", "baskasi").inOrder()
        assertThat(result.skippedLines).isEqualTo(1)
    }

    @Test
    fun `duplicate nicks collapse to one row`() {
        val result = CsvCodec.parseImport("birisi,2010-04-01\nbirisi,2012-05-06")

        assertThat(result.rows).containsExactly(CsvCodec.Row("birisi", day("2010-04-01")))
        assertThat(result.duplicates).isEqualTo(1)
    }

    @Test
    fun `every pasted line is accounted for`() {
        // Four names with two repeats, one line with no nick, one blank. The
        // numbers have to explain where each went, or a collapsed list reads as
        // one that lost a name.
        val result = CsvCodec.parseImport("birisi\nbaskasi\nbirisi\nbaskasi\n\n,2010-04-01")

        assertThat(result.rows.map { it.nick }).containsExactly("birisi", "baskasi").inOrder()
        assertThat(result.duplicates).isEqualTo(2)
        assertThat(result.skippedLines).isEqualTo(1)
    }

    @Test
    fun `a list with no repeats reports none`() {
        // The clause is appended only when this is non-zero, so an ordinary
        // paste has to leave it at zero.
        val result = CsvCodec.parseImport("birisi\nbaskasi")

        assertThat(result.duplicates).isEqualTo(0)
    }

    @Test
    fun `export shape matches the extension`() {
        val csv = export(
            listOf(
                CsvCodec.Row("birisi", day("2010-04-01")),
                CsvCodec.Row("baskasi", null),
            ),
        )

        assertThat(csv).isEqualTo("Username,RegistrationDate\nbirisi,2010-04-01\nbaskasi,")
    }

    @Test
    fun `every exported row has two fields even without a date`() {
        val csv = export(List(3) { CsvCodec.Row("nick$it", null) })

        csv.lineSequence().drop(1).forEach { assertThat(CsvCodec.parseLine(it)).hasSize(2) }
    }

    @Test
    fun `export of an empty list is a bare header`() {
        assertThat(export(emptyList())).isEqualTo(CsvCodec.HEADER)
    }

    @Test
    fun `export round-trips through import`() {
        val rows = listOf(
            CsvCodec.Row("birisi", day("2010-04-01")),
            CsvCodec.Row("baskasi", null),
            CsvCodec.Row("ucuncu", day("2026-08-06")),
        )

        assertThat(CsvCodec.parseImport(export(rows)).rows).isEqualTo(rows)
    }

    @Test
    fun `filenames match the extension's prefixes`() {
        val today = LocalDate.parse("2026-08-06")

        assertThat(CsvCodec.suggestedFilename(ListType.BLOCKED, today))
            .isEqualTo("eksiengel_blocked_users_2026-08-06.csv")
        assertThat(CsvCodec.suggestedFilename(ListType.MUTED, today))
            .isEqualTo("eksiengel_muted_users_2026-08-06.csv")
        assertThat(CsvCodec.suggestedFilename(ListType.FOLLOWED, today))
            .isEqualTo("eksiengel_followed_users_2026-08-06.csv")
    }
}
