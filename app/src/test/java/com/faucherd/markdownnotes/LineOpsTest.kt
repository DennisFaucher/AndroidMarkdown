package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LineOps rewrites the outline, so these tests are the guard against silently
 * reshaping someone's vault. They assert on the resulting *text* after applying
 * the spans, using the same ascending-order application the editor performs on
 * the Editable.
 */
class LineOpsTest {

    private fun apply(text: String, spans: List<LineOps.Span>): String {
        val sb = StringBuilder()
        var pos = 0
        for (s in spans.sortedBy { it.start }) {
            sb.append(text, pos, s.start)
            sb.append(s.text)
            pos = s.end
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    private fun caretAt(line: Int, text: String): Int {
        var p = 0
        repeat(line) { p = text.indexOf('\n', p) + 1 }
        return p
    }

    // ---- indent / demote ----------------------------------------------

    @Test
    fun `demote adds one tab to the caret line`() {
        val t = "- alpha\n- beta"
        val r = LineOps.indent(t, 0, 0)
        assertEquals("\t- alpha\n- beta", apply(t, r.spans))
    }

    @Test
    fun `demote adds a tab to every line a selection touches`() {
        val t = "- alpha\n- beta\n- gamma"
        val r = LineOps.indent(t, 0, t.length)
        assertEquals("\t- alpha\n\t- beta\n\t- gamma", apply(t, r.spans))
    }

    @Test
    fun `demote leaves blank lines alone`() {
        val t = "- alpha\n\n- beta"
        val r = LineOps.indent(t, 0, t.length)
        assertEquals("\t- alpha\n\n\t- beta", apply(t, r.spans))
    }

    @Test
    fun `demote stacks on an already indented line`() {
        val t = "\t- alpha"
        val r = LineOps.indent(t, 0, 0)
        assertEquals("\t\t- alpha", apply(t, r.spans))
    }

    // ---- outdent / promote ---------------------------------------------

    @Test
    fun `promote removes a tab`() {
        val t = "\t- alpha\n- beta"
        val r = LineOps.outdent(t, 2, 2)
        assertEquals("- alpha\n- beta", apply(t, r.spans))
    }

    @Test
    fun `promote removes four spaces as one level`() {
        val t = "    - alpha"
        val r = LineOps.outdent(t, 0, 0)
        assertEquals("  - alpha", apply(t, r.spans))
    }

    @Test
    fun `promote removes two spaces as one level`() {
        val t = "  - alpha"
        val r = LineOps.outdent(t, 0, 0)
        assertEquals("- alpha", apply(t, r.spans))
    }

    @Test
    fun `promote at top level does nothing rather than eating content`() {
        val t = "- alpha"
        val r = LineOps.outdent(t, 0, 0)
        assertTrue(r.spans.isEmpty())
        assertEquals("- alpha", apply(t, r.spans))
    }

    @Test
    fun `promote never removes more than the leading whitespace`() {
        val t = "  \t-alpha"
        val r = LineOps.outdent(t, 0, 0)
        // "  " comes first, so only the two spaces go.
        assertEquals("\t-alpha", apply(t, r.spans))
    }

    @Test
    fun `promote across a selection shifts the caret back correctly`() {
        val t = "\t- alpha\n\t- beta"
        val start = t.indexOf("beta") + 1
        val r = LineOps.outdent(t, start, start)
        assertEquals("\t- alpha\n- beta", apply(t, r.spans))
        assertEquals(start - 1, r.selStart)
    }

    @Test
    fun `promote never yields an inverted selection`() {
        val t = "\t\t- deep"
        val r = LineOps.outdent(t, 3, 3)
        assertTrue("selStart=${r.selStart} selEnd=${r.selEnd}", r.selStart <= r.selEnd)
    }

    // ---- todo toggle ---------------------------------------------------

    @Test
    fun `toggle adds TODO when there is no marker`() {
        val t = "- alpha"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("- TODO alpha", apply(t, r.spans))
    }

    @Test
    fun `toggle turns TODO into DONE in place`() {
        val t = "- TODO alpha"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("- DONE alpha", apply(t, r.spans))
    }

    @Test
    fun `toggle turns DONE back into unmarked`() {
        val t = "- DONE alpha"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("- alpha", apply(t, r.spans))
    }

    @Test
    fun `toggle collapses a finer marker to DONE`() {
        val t = "- DOING alpha"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("- DONE alpha", apply(t, r.spans))
    }

    @Test
    fun `toggle preserves indentation and the bullet`() {
        val t = "\t- alpha"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("\t- TODO alpha", apply(t, r.spans))
    }

    @Test
    fun `toggle works on a non-bullet line`() {
        val t = "plain text"
        val r = LineOps.toggleTodo(t, 0, 0)
        assertEquals("TODO plain text", apply(t, r.spans))
    }

    @Test
    fun `toggle does not treat a word prefix as a marker`() {
        val t = "- TODOlist of things"
        val r = LineOps.toggleTodo(t, 0, 0)
        // "TODOlist" merely starts with TODO; it is not a marker. The line is
        // therefore unmarked and gains a real one in front.
        assertEquals("- TODO TODOlist of things", apply(t, r.spans))
    }

    @Test
    fun `toggle applies across a multi-line selection`() {
        val t = "- a\n- TODO b\n- DONE c"
        val r = LineOps.toggleTodo(t, 0, t.length)
        assertEquals("- TODO a\n- DONE b\n- c", apply(t, r.spans))
    }

    @Test
    fun `toggle leaves a blank line untouched`() {
        val t = "- a\n\n- b"
        val r = LineOps.toggleTodo(t, 0, t.length)
        assertEquals("- TODO a\n\n- TODO b", apply(t, r.spans))
    }

    @Test
    fun `toggle keeps the caret in range`() {
        val t = "- TODO alpha"
        val r = LineOps.toggleTodo(t, 10, 10)
        assertTrue(r.selStart in 0..t.length)
        assertTrue(r.selEnd >= r.selStart)
    }

    // ---- continuation --------------------------------------------------

    @Test
    fun `continues a plain bullet`() {
        assertEquals("- ", LineOps.continuationFor("- alpha"))
    }

    @Test
    fun `continues a bullet and keeps the marker`() {
        assertEquals("- TODO ", LineOps.continuationFor("- TODO alpha"))
    }

    @Test
    fun `continues keeping indentation`() {
        assertEquals("\t- ", LineOps.continuationFor("\t- alpha"))
    }

    @Test
    fun `does not continue a non-bullet line`() {
        assertEquals(null, LineOps.continuationFor("plain text"))
        assertEquals(null, LineOps.continuationFor("   "))
    }

    // ---- general -------------------------------------------------------

    @Test
    fun `transforms on an empty buffer are no-ops`() {
        assertTrue(LineOps.indent("", 0, 0).spans.isEmpty())
        assertTrue(LineOps.outdent("", 0, 0).spans.isEmpty())
        assertTrue(LineOps.toggleTodo("", 0, 0).spans.isEmpty())
    }

    @Test
    fun `a transform is a pure function of the buffer`() {
        val t = "- a\n\t- b"
        val before = t
        LineOps.indent(t, 0, t.length)
        LineOps.outdent(t, 0, t.length)
        LineOps.toggleTodo(t, 0, t.length)
        assertEquals(before, t)
    }

    // ---- caret placement ------------------------------------------------
    //
    // Every transform must leave the caret on the same logical character it was
    // on, on the same line. Reporting a selection in pre-edit coordinates is
    // easy to do and reads as a correct-looking number, so the invariant is
    // asserted against the text rather than the offset.

    /** Applies the result the way the editor does, and returns text plus caret. */
    private fun applyWithCaret(
        text: String,
        r: LineOps.Result,
    ): Pair<String, Int> {
        val sb = StringBuilder(text)
        val sel = LineOps.applySpans(r) { s, e, repl ->
            sb.replace(s, e, repl.toString())
        }
        return sb.toString() to sel
    }

    @Test
    fun `demote leaves the caret on the same character`() {
        val t = "- alpha\n- beta"
        val p = t.indexOf("beta") + 2
        val (out, sel) = applyWithCaret(t, LineOps.indent(t, p, p))
        assertEquals("- alpha\n\t- beta", out)
        assertEquals(t[p], out[sel])
    }

    @Test
    fun `promote leaves the caret on the same character`() {
        val t = "- alpha\n\t- beta"
        val p = t.indexOf("beta") + 2
        val (out, sel) = applyWithCaret(t, LineOps.outdent(t, p, p))
        assertEquals(t[p], out[sel])
    }

    @Test
    fun `promote at the start of a nested line keeps the caret on that line`() {
        val t = "- alpha\n\t- beta\n- gamma"
        val p = caretAt(1, t)
        val (out, sel) = applyWithCaret(t, LineOps.outdent(t, p, p))
        // The caret was on the tab. Once the tab is gone it must sit at the
        // start of the line's text -- still the beta line, not before its
        // newline and not over on gamma.
        assertEquals("- alpha\n- beta\n- gamma", out)
        assertEquals('\t', t[p])
        assertEquals(caretAt(1, out), sel)
        assertEquals('-', out[sel])
    }

    @Test
    fun `promote at the end of a line keeps the caret on that line`() {
        val t = "- alpha\n\t- beta\n- gamma"
        val p = t.indexOf("beta") + "beta".length
        val (out, sel) = applyWithCaret(t, LineOps.outdent(t, p, p))
        // The caret was parked on the newline after "beta"; it must stay there
        // rather than slide back onto the beta line or forward onto gamma.
        assertEquals('\n', out[sel])
        assertEquals('\n', t[p])
    }

    @Test
    fun `todo toggle leaves the caret on the same character`() {
        val t = "- TODO alpha"
        val p = t.indexOf("alpha") + 1
        val (out, sel) = applyWithCaret(t, LineOps.toggleTodo(t, p, p))
        assertEquals('l', out[sel])
    }

    @Test
    fun `applySpans reports the selection without shifting it again`() {
        // The exact regression: adding the total length delta to an
        // already-post-edit selection walks the caret off the end of its line.
        val t = "- alpha\n\t- beta"
        val p = t.indexOf("beta")
        val r = LineOps.outdent(t, p, p)
        val sb = StringBuilder(t)
        val sel = LineOps.applySpans(r) { s, e, repl -> sb.replace(s, e, repl.toString()) }
        assertEquals(r.selStart, sel)
        assertEquals(t[p], sb[sel])
    }

    @Test
    fun `a multi-line transform keeps the caret in range`() {
        val t = "- a\n- b\n- c"
        val ops = listOf<Pair<String, (String, Int, Int) -> LineOps.Result>>(
            "indent" to LineOps::indent,
            "outdent" to LineOps::outdent,
            "toggleTodo" to LineOps::toggleTodo,
        )
        for ((name, op) in ops) {
            val sb = StringBuilder(t)
            val sel = LineOps.applySpans(op(t, 0, t.length)) { s, e, repl -> sb.replace(s, e, repl.toString()) }
            assertTrue("$name produced sel=$sel for length=${sb.length}", sel in 0..sb.length)
        }
    }
}
