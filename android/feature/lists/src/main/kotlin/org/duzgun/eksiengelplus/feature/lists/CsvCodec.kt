package org.duzgun.eksiengelplus.feature.lists

import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.model.TurkishDateParser
import java.io.OutputStream
import java.time.LocalDate

/**
 * The CSV interchange the extension already defines.
 *
 * Export reproduces notificationHandler.js:186-214 and import reproduces
 * authorListPage.js:112-210. The format is how users move a list between the
 * extension and this app and between machines, so the requirement is
 * byte-compatibility with files already in circulation, not a better CSV.
 *
 * Deliberately free of Android types: the SAF plumbing lives in the caller, and
 * parser parity is then a table of JVM unit tests rather than an emulator run.
 */
object CsvCodec {

    const val HEADER = "Username,RegistrationDate"

    /** One imported row. [epochDay] is null when the file carried no usable date. */
    data class Row(val nick: String, val epochDay: Long?)

    /** What an import produced, so the screen can report it rather than guess. */
    data class ImportResult(
        val rows: List<Row>,
        val skippedLines: Int,
        val hadHeader: Boolean,
        /**
         * Repeat nicks folded into an earlier row.
         *
         * Counted so the reported numbers account for every line the user
         * pasted. They were simply dropped before, which left a paste of six
         * lines reporting two authors and one skipped line and no explanation
         * for the other three -- the kind of arithmetic that reads as the list
         * having eaten a name.
         *
         * Blank lines are deliberately still uncounted: a trailing newline is
         * not something the user did wrong, and reporting it would put "1 satır
         * atlandı" on almost every paste.
         */
        val duplicates: Int = 0,
    ) {
        val datesRecognised: Int get() = rows.count { it.epochDay != null }
    }

    /**
     * Splits one CSV line, honouring `"`-quoted fields that contain commas.
     *
     * Ported from authorListPage.js:112-132, including its handling of a quote as
     * a bare toggle rather than a paired delimiter -- `a"b` yields `ab`. Matching
     * the extension matters more here than being right.
     */
    fun parseLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    values += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
        }
        values += current.toString().trim()
        return values
    }

    /**
     * Parses a pasted or picked list.
     *
     * The first line is a header only when its first field lowercases to
     * `username`, matching authorListPage.js:178 -- a headerless file whose first
     * row is a real user must not lose that user.
     *
     * An unparseable date never rejects its row. The nick is the payload; the date
     * is a hint that saves a profile fetch later, and dropping a user because a
     * spreadsheet reformatted a column would be the worse failure.
     */
    fun parseImport(text: String): ImportResult {
        val lines = text.split(Regex("\\r?\\n"))
        val firstFields = lines.firstOrNull()?.let(::parseLine).orEmpty()
        val hasHeader = firstFields.firstOrNull()?.lowercase() == "username"
        val dataLines = if (hasHeader) lines.drop(1) else lines

        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<String>()
        var skipped = 0
        var duplicates = 0

        for (line in dataLines) {
            if (line.isBlank()) continue
            val fields = parseLine(line)
            val nick = fields.getOrNull(0)?.trim().orEmpty()
            if (nick.isEmpty()) {
                skipped++
                continue
            }
            // author_list.nick is uniquely indexed; collapsing here keeps the
            // reported count honest instead of promising rows the upsert merges.
            // Counted, so the line is accounted for rather than vanishing.
            if (!seen.add(nick)) {
                duplicates++
                continue
            }
            rows += Row(nick, TurkishDateParser.parse(fields.getOrNull(1))?.toEpochDay())
        }

        return ImportResult(rows, skipped, hasHeader, duplicates)
    }

    /**
     * Streams the export.
     *
     * Written a row at a time rather than assembled into one string the way
     * notificationHandler.js:186-194 does: a 20 000-nick blocked list is no reason
     * to hold 600 KB.
     *
     * Fields are NOT quoted, reproducing the extension's asymmetry -- its writer
     * does not escape even though its reader unescapes. Quoting here would produce
     * files the extension misreads, which is the opposite of the goal.
     */
    fun writeExport(rows: List<Row>, out: OutputStream) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(HEADER)
            for (row in rows) {
                w.write("\n")
                w.write(row.nick)
                w.write(",")
                w.write(row.epochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty())
            }
        }
    }

    /**
     * `eksiengel_blocked_users_2026-08-06.csv`, matching
     * notificationHandler.js:200-206.
     *
     * [today] is passed in rather than read from the clock so the caller decides
     * the zone -- and so this stays testable.
     */
    fun suggestedFilename(listType: ListType, today: LocalDate): String {
        val slug = when (listType) {
            ListType.BLOCKED -> "blocked"
            ListType.MUTED -> "muted"
            ListType.FOLLOWED -> "followed"
            // No extension counterpart: it exports three lists, not four.
            ListType.TITLE_BANNED -> "title_banned"
        }
        return "eksiengel_${slug}_users_$today.csv"
    }
}
