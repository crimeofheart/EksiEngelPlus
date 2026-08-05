package org.duzgun.eksiengelplus.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import org.junit.Test

/**
 * Characterisation suite for the behaviour of utils.parseTurkishDate
 * (frontend/app/assets/js/utils.js:97-139).
 *
 * Written before the implementation deliberately. Date-filter bugs silently
 * block or unblock the wrong people and stay invisible until a user complains,
 * so the table is the specification and the Kotlin follows it.
 *
 * The two month-name values seen live during android-spike were "ağustos 2026"
 * and "temmuz 2026", so that form is the primary case, not a fallback.
 */
class TurkishDateParserTest {

    private fun parsed(s: String) = TurkishDateParser.parse(s)

    // ---------------------------------------------------------- month names

    @Test
    fun `all twelve month names resolve to the first of that month`() {
        val cases = listOf(
            "ocak 2020" to LocalDate.of(2020, 1, 1),
            "şubat 2020" to LocalDate.of(2020, 2, 1),
            "mart 2020" to LocalDate.of(2020, 3, 1),
            "nisan 2020" to LocalDate.of(2020, 4, 1),
            "mayıs 2020" to LocalDate.of(2020, 5, 1),
            "haziran 2020" to LocalDate.of(2020, 6, 1),
            "temmuz 2020" to LocalDate.of(2020, 7, 1),
            "ağustos 2020" to LocalDate.of(2020, 8, 1),
            "eylül 2020" to LocalDate.of(2020, 9, 1),
            "ekim 2020" to LocalDate.of(2020, 10, 1),
            "kasım 2020" to LocalDate.of(2020, 11, 1),
            "aralık 2020" to LocalDate.of(2020, 12, 1),
        )
        cases.forEach { (input, expected) ->
            assertWithMessage(input).that(parsed(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `live values observed during the spike parse`() {
        assertThat(parsed("ağustos 2026")).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(parsed("temmuz 2026")).isEqualTo(LocalDate.of(2026, 7, 1))
    }

    @Test
    fun `month names are case insensitive and tolerate surrounding whitespace`() {
        assertThat(parsed("  ARALIK 2018 ")).isEqualTo(LocalDate.of(2018, 12, 1))
        assertThat(parsed("Aralık 2018")).isEqualTo(LocalDate.of(2018, 12, 1))
    }

    @Test
    fun `dotless i and other Turkish characters are handled`() {
        // "mayıs" and "kasım" carry the dotless i; a naive lowercase() with a
        // Turkish locale can turn I into ı and break naive matching.
        assertThat(parsed("mayıs 1999")).isEqualTo(LocalDate.of(1999, 5, 1))
        assertThat(parsed("kasım 1999")).isEqualTo(LocalDate.of(1999, 11, 1))
        assertThat(parsed("şubat 1999")).isEqualTo(LocalDate.of(1999, 2, 1))
        assertThat(parsed("eylül 1999")).isEqualTo(LocalDate.of(1999, 9, 1))
    }

    // ------------------------------------------------------------- numeric

    @Test
    fun `dotted day-month-year form parses`() {
        assertThat(parsed("30.03.2011")).isEqualTo(LocalDate.of(2011, 3, 30))
        assertThat(parsed("01.01.2000")).isEqualTo(LocalDate.of(2000, 1, 1))
        assertThat(parsed("31.12.1999")).isEqualTo(LocalDate.of(1999, 12, 31))
    }

    @Test
    fun `dotted form with a trailing time parses to the date`() {
        // Entry timestamps on the site carry a time: "30.03.2011 13:52".
        assertThat(parsed("30.03.2011 13:52")).isEqualTo(LocalDate.of(2011, 3, 30))
    }

    @Test
    fun `iso form parses`() {
        assertThat(parsed("2018-12-01")).isEqualTo(LocalDate.of(2018, 12, 1))
        assertThat(parsed("2026-08-05")).isEqualTo(LocalDate.of(2026, 8, 5))
    }

    // ---------------------------------------------------------- rejections

    @Test
    fun `unparseable input returns null rather than guessing`() {
        val bad = listOf(
            "", "   ", "aralık", "2018", "not a date", "32.13.2020",
            "2018-13-01", "aralık 20x8", "13/05/2020", "-", "null",
        )
        bad.forEach { assertWithMessage("'$it'").that(parsed(it)).isNull() }
    }

    @Test
    fun `an unknown month name is rejected`() {
        assertThat(parsed("smarch 2020")).isNull()
        assertThat(parsed("december 2020")).isNull()
    }

    // ------------------------------------------------------- day arithmetic

    @Test
    fun `day difference counts whole days`() {
        val a = LocalDate.of(2020, 1, 1)
        assertThat(TurkishDateParser.daysBetween(a, LocalDate.of(2020, 1, 1))).isEqualTo(0)
        assertThat(TurkishDateParser.daysBetween(a, LocalDate.of(2020, 1, 31))).isEqualTo(30)
        assertThat(TurkishDateParser.daysBetween(a, LocalDate.of(2021, 1, 1))).isEqualTo(366) // leap
    }

    @Test
    fun `day difference is signed the same way regardless of order`() {
        val a = LocalDate.of(2020, 1, 1)
        val b = LocalDate.of(2020, 2, 1)
        assertThat(TurkishDateParser.daysBetween(a, b)).isEqualTo(31)
        assertThat(TurkishDateParser.daysBetween(b, a)).isEqualTo(-31)
    }
}
