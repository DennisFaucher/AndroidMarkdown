package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The find bar's whole correctness argument lives in this file: which ranges
 * match, what line each one is on, and where "next" goes. The view is a thin
 * layer over it, so these assert the things a reader would notice going wrong.
 */
class FindInPageTest {

    private fun starts(f: FindInPage?) = f?.ranges?.map { it.first }

    @Test
    fun `finds every occurrence in document order`() {
        val f = FindInPage.find("alpha beta alpha gamma alpha", "alpha")
        assertEquals(listOf(0, 11, 23), starts(f))
    }

    @Test
    fun `matches are case-insensitive by default, like the vault search`() {
        assertEquals(listOf(0, 6), starts(FindInPage.find("Alpha alpha", "ALPHA")))
        // Case-sensitive is a different question, and here the answer is none.
        assertNull(FindInPage.find("Alpha alpha", "ALPHA", ignoreCase = false))
    }

    @Test
    fun `matches do not overlap`() {
        // "aa" in "aaaa" is two matches, not three. Reporting the shifted ones
        // inflates the count and makes next appear to stall on the same text.
        val f = FindInPage.find("aaaa", "aa")
        assertEquals(listOf(0, 2), starts(f))
    }

    @Test
    fun `a blank query finds nothing rather than matching everywhere`() {
        assertNull(FindInPage.find("some text", ""))
    }

    @Test
    fun `a query longer than the text is no match, not a crash`() {
        assertNull(FindInPage.find("short", "much longer than this"))
    }

    @Test
    fun `no match at all is null so the caller can say so`() {
        assertNull(FindInPage.find("alpha beta", "zebra"))
    }

    @Test
    fun `regex metacharacters are matched literally`() {
        // Someone searching for a filename, a glob or a tag must get those
        // characters back, not whatever the pattern happens to mean.
        assertEquals(listOf(5), starts(FindInPage.find("path a.b.c end", "a.b.c")))
        assertEquals(listOf(0), starts(FindInPage.find("[draft] note", "[draft]")))
        assertEquals(listOf(8), starts(FindInPage.find("cost is $5 (approx)", "$5")))
    }

    @Test
    fun `a match at the very start and very end is found`() {
        val text = "head\nmiddle\ntail"
        assertEquals(listOf(0), starts(FindInPage.find(text, "head")))
        assertEquals(listOf(12), starts(FindInPage.find(text, "tail")))
    }

    // ---- line resolution --------------------------------------------------

    @Test
    fun `line numbers are one-based`() {
        val f = FindInPage.find("one\ntwo\nthree", "two")!!
        assertEquals(2, f.lineAt(f.ranges[0].first))
    }

    @Test
    fun `a match on the first line is line one`() {
        val f = FindInPage.find("first\nsecond", "first")!!
        assertEquals(1, f.lineAt(f.ranges[0].first))
    }

    @Test
    fun `a match on the last line resolves to the last line`() {
        val f = FindInPage.find("a\nb\nc\nd", "d")!!
        assertEquals(4, f.lineAt(f.ranges[0].first))
    }

    @Test
    fun `several matches on one line all report that line`() {
        val f = FindInPage.find("x\nx x x\nx", "x")!!
        val lines = f.ranges.map { f.lineAt(it.first) }
        assertEquals(listOf(1, 2, 2, 2, 3), lines)
    }

    @Test
    fun `offsets past the end clamp instead of throwing`() {
        // The file can be reloaded out from under a held match; a stale offset
        // must not crash the reader mid-search.
        val f = FindInPage.find("a\nb", "a")!!
        assertEquals(1, f.lineAt(-50))
        assertEquals(2, f.lineAt(9999))
    }

    @Test
    fun `blank lines still advance the line count`() {
        // Markdown journals are full of them, and a page that ends in a newline
        // has a final empty line — offsets must not collapse onto the previous.
        val f = FindInPage.find("a\n\n\nb", "b")!!
        assertEquals(4, f.lineAt(f.ranges[0].first))
    }

    // ---- navigation -------------------------------------------------------

    @Test
    fun `next advances and wraps at the end`() {
        val f = FindInPage.find("x x x", "x")!!
        assertEquals(1, f.step(0, forward = true))
        assertEquals(2, f.step(1, forward = true))
        assertEquals(0, f.step(2, forward = true))
    }

    @Test
    fun `previous rewinds and wraps at the start`() {
        val f = FindInPage.find("x x x", "x")!!
        assertEquals(2, f.step(0, forward = false))
        assertEquals(0, f.step(1, forward = false))
    }

    @Test
    fun `stepping with no matches is a no-op rather than an index error`() {
        val f = FindInPage.find("abc", "zzz")
        assertNull(f)
    }

    @Test
    fun `a single match steps to itself in both directions`() {
        val f = FindInPage.find("x", "x")!!
        assertEquals(0, f.step(0, forward = true))
        assertEquals(0, f.step(0, forward = false))
    }

    // ---- realistic document -----------------------------------------------

    @Test
    fun `counts a tag across a large journal-style page`() {
        val block = "- TODO renew the domain #WorkToDo\n\t- notes\n"
        val page = block.repeat(500)
        val f = FindInPage.find(page, "#worktodo")!!
        assertEquals(500, f.ranges.size)
        // Spread across 500 blocks: the 499th match is deep in the document.
        assertTrue(f.lineAt(f.ranges[498].first) > 900)
    }

    @Test
    fun `a one-letter query on a big page still terminates and stays navigable`() {
        val page = "lorem ipsum dolor sit amet ".repeat(4000)
        val f = FindInPage.find(page, "o")!!
        assertTrue("expected many matches", f.ranges.size > 1000)
        assertEquals(0, f.step(f.ranges.size - 1, forward = true))
        assertNotNull(f.lineAt(f.ranges.last().first))
    }
}
