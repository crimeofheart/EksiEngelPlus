package org.duzgun.eksiengelplus.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ports utils.parseTurkishDate (frontend/app/assets/js/utils.js:97-139).
 *
 * Ekşi Sözlük renders profile registration dates as a Turkish month name and a
 * year — the only two values observed live during android-spike were
 * "ağustos 2026" and "temmuz 2026" — so that is the primary form. ISO and
 * dotted DD.MM.YYYY are also accepted because entry timestamps use them.
 *
 * Returns null rather than throwing or guessing: a wrong date silently blocks or
 * unblocks the wrong accounts, so an unparseable value must be visible to the
 * caller as "unknown".
 */
object TurkishDateParser {

    /** Ekşi's timezone. All day arithmetic is anchored here. */
    val ZONE: ZoneId = ZoneId.of("Europe/Istanbul")

    private val MONTHS = mapOf(
        "ocak" to 1,
        "şubat" to 2,
        "mart" to 3,
        "nisan" to 4,
        "mayıs" to 5,
        "haziran" to 6,
        "temmuz" to 7,
        "ağustos" to 8,
        "eylül" to 9,
        "ekim" to 10,
        "kasım" to 11,
        "aralık" to 12,
    )

    private val MONTH_YEAR = Regex("""^(\p{L}+)\s+(\d{4})$""")
    private val DOTTED = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})\b""")

    fun parse(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        // Turkish locale lowercasing, so "ARALIK" folds to "aralık" and not "aralik".
        val lower = s.lowercase(java.util.Locale.forLanguageTag("tr"))

        MONTH_YEAR.find(lower)?.let { m ->
            val month = MONTHS[m.groupValues[1]] ?: return null
            val year = m.groupValues[2].toIntOrNull() ?: return null
            // A month-name value carries no day; the extension resolves it to the first.
            return runCatching { LocalDate.of(year, month, 1) }.getOrNull()
        }

        DOTTED.find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }

        ISO.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }

        return null
    }

    /** Whole days from [from] to [to]; negative when [to] precedes [from]. */
    fun daysBetween(from: LocalDate, to: LocalDate): Long =
        ChronoUnit.DAYS.between(from, to)

    fun today(): LocalDate = LocalDate.now(ZONE)
}
