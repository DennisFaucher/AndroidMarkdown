package com.faucherd.markdownnotes

import java.time.LocalDate

/**
 * Filename derivation, mirrored from `server/src/vault/files.ts`.
 *
 * This has to match the server byte for byte. The phone and the web app share
 * one vault with no database or index between them, so the only thing making a
 * note findable is both sides deriving the same filename from the same title.
 * Get it wrong and nothing fails loudly: the note saves fine, it just never
 * appears in the web app. A page written as `My/Page.md` instead of
 * `My___Page.md` is silently unreachable.
 *
 * Deliberate divergences from the server would be worse than the quirks below,
 * so the quirks are reproduced rather than fixed here. Notably a title that
 * already contains `___` collides with one containing `/`; the server has the
 * same collision and diverging would make the two disagree about which file a
 * title means.
 */
object VaultNames {

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /**
     * `2026_09_26.md` — underscores, not dashes. Matches journalFilename() on
     * the server, where the filename is also the document id.
     */
    fun journalFilename(date: LocalDate): String =
        "%04d_%02d_%02d.md".format(date.year, date.monthValue, date.dayOfMonth)

    /**
     * Null for anything that is not a journal filename, *including*
     * `2026-09-26.md`. The dashed form is not a journal here — treating it as one
     * would invent a second id for the same day.
     */
    fun journalDateFromFilename(filename: String): LocalDate? {
        val m = JOURNAL_NAME.find(filename) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toIntOrNull() ?: return null
        val day = d.toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            LocalDate.of(y.toInt(), month, day)
        } catch (_: Exception) {
            null // e.g. 2026_02_31
        }
    }

    /** `Sep 26th, 2026`, matching formatJournalTitle() on the server. */
    fun formatJournalTitle(date: LocalDate): String =
        "${MONTHS[date.monthValue - 1]} ${date.dayOfMonth}" +
            "${ordinalSuffix(date.dayOfMonth)}, ${date.year}"

    private fun ordinalSuffix(n: Int): String {
        val j = n % 10
        val k = n % 100
        if (j == 1 && k != 11) return "st"
        if (j == 2 && k != 12) return "nd"
        if (j == 3 && k != 13) return "rd"
        return "th"
    }

    /**
     * Mirrors isSafePageTitle(): the title has to survive being one filename
     * component. Note the server rejects only NUL through 0x1f, so this does
     * too — being stricter would refuse titles the web app accepts.
     */
    fun isSafePageTitle(title: String): Boolean {
        if (title.isEmpty() || title.length > 200) return false
        if (title == "." || title == "..") return false
        return title.none { it.code <= 0x1f }
    }

    /** `/` becomes `___` so a title stays a single path component. */
    fun pageTitleToFilename(title: String): String {
        require(isSafePageTitle(title)) { "unsafe page title: $title" }
        return title.replace("/", "___") + ".md"
    }

    fun filenameToPageTitle(filename: String): String =
        filename.removeSuffix(".md").replace("___", "/")

    private val JOURNAL_NAME = Regex("^(\\d{4})_(\\d{2})_(\\d{2})\\.md$")
}
