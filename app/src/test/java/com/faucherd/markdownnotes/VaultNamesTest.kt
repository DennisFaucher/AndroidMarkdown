package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Parity tests against `server/src/vault/files.ts`.
 *
 * These are the highest-consequence tests in the app. Nothing here fails
 * visibly if it drifts: a note still saves, it just becomes invisible to the
 * web app, or gets two ids for one day. The expected strings are written out
 * literally rather than recomputed, so a change on either side has to be
 * acknowledged here deliberately.
 */
class VaultNamesTest {

    @Test
    fun `journal filename uses underscores`() {
        assertEquals("2026_09_26.md", VaultNames.journalFilename(LocalDate.of(2026, 9, 26)))
        assertEquals("2025_10_06.md", VaultNames.journalFilename(LocalDate.of(2025, 10, 6)))
        assertEquals("2026_01_01.md", VaultNames.journalFilename(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `journal filename round-trips through the date`() {
        for (d in listOf(
            LocalDate.of(2026, 9, 26),
            LocalDate.of(2025, 10, 6),
            LocalDate.of(2024, 2, 29),
            LocalDate.of(2026, 12, 31),
        )) {
            assertEquals(d, VaultNames.journalDateFromFilename(VaultNames.journalFilename(d)))
        }
    }

    @Test
    fun `a dashed date is not a journal filename`() {
        // The web app never produces this form. Reading it as a journal would
        // invent a second document id for a day that already has one.
        assertNull(VaultNames.journalDateFromFilename("2026-09-26.md"))
        assertEquals("2026-09-26", VaultNames.filenameToPageTitle("2026-09-26.md"))
    }

    @Test
    fun `malformed journal filenames are rejected not guessed at`() {
        assertNull(VaultNames.journalDateFromFilename("2026_9_26.md"))
        assertNull(VaultNames.journalDateFromFilename("2026_09_26"))
        assertNull(VaultNames.journalDateFromFilename("2026_13_01.md"))
        assertNull(VaultNames.journalDateFromFilename("2026_02_31.md"))
        assertNull(VaultNames.journalDateFromFilename("notes.md"))
        assertNull(VaultNames.journalDateFromFilename("2026_09_26.conflict-x.md"))
    }

    @Test
    fun `journal titles match the server format`() {
        assertEquals("Sep 26th, 2026", VaultNames.formatJournalTitle(LocalDate.of(2026, 9, 26)))
        assertEquals("Oct 6th, 2025", VaultNames.formatJournalTitle(LocalDate.of(2025, 10, 6)))
        assertEquals("Jan 1st, 2026", VaultNames.formatJournalTitle(LocalDate.of(2026, 1, 1)))
        assertEquals("Dec 31st, 2026", VaultNames.formatJournalTitle(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `ordinal suffixes follow the english rules not just last digit`() {
        val nth = mapOf(
            1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 10 to "10th",
            11 to "11th", 12 to "12th", 13 to "13th", 14 to "14th",
            20 to "20th", 21 to "21st", 22 to "22nd", 23 to "23rd",
            24 to "24th", 30 to "30th", 31 to "31st",
        )
        for ((day, expected) in nth) {
            // December, so day 31 is a real date.
            assertEquals(
                "Dec $expected, 2026",
                VaultNames.formatJournalTitle(LocalDate.of(2026, 12, day)),
            )
        }
    }

    @Test
    fun `a slash in a page title becomes three underscores`() {
        assertEquals("My___Page.md", VaultNames.pageTitleToFilename("My/Page"))
        assertEquals("a___b___c.md", VaultNames.pageTitleToFilename("a/b/c"))
    }

    @Test
    fun `page filenames round-trip back to the title`() {
        for (title in listOf("Simple", "My/Page", "a/b/c", "Trailing space ")) {
            assertEquals(
                title,
                VaultNames.filenameToPageTitle(VaultNames.pageTitleToFilename(title)),
            )
        }
    }

    @Test
    fun `unsafe page titles are rejected`() {
        assertFalse(VaultNames.isSafePageTitle(""))
        assertFalse(VaultNames.isSafePageTitle("."))
        assertFalse(VaultNames.isSafePageTitle(".."))
        assertFalse(VaultNames.isSafePageTitle("x".repeat(201)))
        assertFalse(VaultNames.isSafePageTitle("bad\u0000name"))
        assertFalse(VaultNames.isSafePageTitle("bad\nname"))
        assertFalse(VaultNames.isSafePageTitle("bad\u001fname"))
    }

    @Test
    fun `a slash is allowed in a page title`() {
        // Rejecting this would be a divergence: the web app accepts it and
        // escapes it, so the phone has to as well.
        assertTrue(VaultNames.isSafePageTitle("My/Page"))
        assertTrue(VaultNames.isSafePageTitle("x".repeat(200)))
    }

    @Test
    fun `pageTitleToFilename refuses what isSafePageTitle rejects`() {
        var threw = false
        try {
            VaultNames.pageTitleToFilename("..")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected a thrown IllegalArgumentException", threw)
    }
}
