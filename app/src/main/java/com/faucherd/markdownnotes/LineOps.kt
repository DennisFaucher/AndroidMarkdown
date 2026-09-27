package com.faucherd.markdownnotes

/**
 * Pure, line-oriented text transforms for the markdown editor.
 *
 * These deliberately know nothing about the document's structure. They rewrite
 * a single line (or each line a selection touches) as *text*, so the bytes the
 * user sees are the bytes that get written. Nothing here re-serialises a parsed
 * tree, which is what keeps byte-exact round-tripping free — the property the
 * web app's round-trip suite exists to protect.
 *
 * Results are Spans rather than a rebuilt String so the editor can apply them
 * to the Editable in place, which keeps Android's native undo stack intact.
 * Calling setText() would silently discard it.
 */
object LineOps {

    const val INDENT = "\t"

    /** One replacement to apply to the buffer. */
    data class Span(val start: Int, val end: Int, val text: String)

    data class Result(val spans: List<Span>, val selStart: Int, val selEnd: Int)

    private val MARKERS = listOf("TODO", "DOING", "NOW", "LATER", "WAITING", "DONE", "CANCELED")

    private val BULLETS = listOf("-", "*", "+")

    // ---- line geometry -------------------------------------------------

    private fun lineStartAt(text: String, index: Int): Int {
        val i = index.coerceIn(0, text.length)
        if (i == 0) return 0
        val nl = text.lastIndexOf('\n', (i - 1).coerceAtLeast(0))
        return if (nl < 0) 0 else nl + 1
    }

    private fun lineEndAt(text: String, index: Int): Int {
        val i = index.coerceIn(0, text.length)
        val nl = text.indexOf('\n', i)
        return if (nl < 0) text.length else nl
    }

    private fun lineStartsIn(text: String, from: Int, to: Int): List<Int> {
        val out = ArrayList<Int>()
        var p = from
        while (p <= to) {
            out.add(p)
            val nl = text.indexOf('\n', p)
            if (nl < 0 || nl + 1 > to) break
            p = nl + 1
        }
        return out
    }

    /**
     * Half-open [start, end) covering the first byte of the first affected line
     * and the first byte of the last one.
     *
     * A selection ending exactly at a line start must not drag in that line, so
     * the end resolves against e-1 whenever a range is selected.
     */
    private fun affectedRange(text: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        val first = lineStartAt(text, s)
        val last = lineStartAt(text, if (e > s) e - 1 else s)
        return first to last
    }

    private fun indentWidth(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == '\t' || line[i] == ' ')) i++
        return i
    }

    /**
     * A marker must be a whole word. Without this, "- TODOlist of things" reads
     * as a TODO to-do and gets mangled into "- DONE list of things".
     */
    private fun isMarkerAt(rest: String, marker: String): Boolean {
        if (!rest.startsWith(marker, ignoreCase = true)) return false
        val after = marker.length
        if (after >= rest.length) return true
        val c = rest[after]
        return !c.isLetterOrDigit() && c != '_'
    }

    // ---- transforms ----------------------------------------------------

    /** Demote: one more level of indentation on every affected line. */
    fun indent(text: String, selStart: Int, selEnd: Int): Result {
        val (from, to) = affectedRange(text, selStart, selEnd)
        val spans = ArrayList<Span>()
        var selS = selStart
        var selE = selEnd
        for (start in lineStartsIn(text, from, to)) {
            val line = text.substring(start, lineEndAt(text, start))
            if (line.isBlank()) continue
            spans.add(Span(start, start, INDENT))
            // An insertion at `start` sits before the caret whenever start <= it,
            // which keeps the caret glued to the same character it was on.
            if (start <= selS) selS += INDENT.length
            if (start <= selE) selE += INDENT.length
        }
        return Result(spans, selS, selE)
    }

    /**
     * Promote: remove one level of indentation. A tab counts as one level, and
     * so do two or four spaces — otherwise outdent would feel broken on
     * space-indented content, which is what a phone's soft keyboard produces.
     */
    fun outdent(text: String, selStart: Int, selEnd: Int): Result {
        val (from, to) = affectedRange(text, selStart, selEnd)
        val spans = ArrayList<Span>()
        var selS = selStart
        var selE = selEnd
        for (start in lineStartsIn(text, from, to)) {
            val line = text.substring(start, lineEndAt(text, start))
            if (line.isBlank()) continue
            val cut = when {
                line.startsWith(INDENT) -> 1
                line.startsWith("  ") -> 2
                line.startsWith(' ') -> 1
                else -> 0
            }
            if (cut == 0) continue
            spans.add(Span(start, start + cut, ""))
            if (start < selS) { selS -= cut; selE -= cut }
            else if (start < selE) selE -= cut
        }
        val safeS = selS.coerceIn(0, text.length)
        return Result(spans, safeS, selE.coerceIn(safeS, text.length))
    }

    /**
     * Toggle the Logseq marker on each affected line. TODO <-> DONE, in place —
     * the block is never moved or deleted, matching how the web app behaves.
     */
    fun toggleTodo(text: String, selStart: Int, selEnd: Int): Result {
        val (from, to) = affectedRange(text, selStart, selEnd)
        val spans = ArrayList<Span>()
        var selS = selStart
        var selE = selEnd
        for (start in lineStartsIn(text, from, to)) {
            val end = lineEndAt(text, start)
            val line = text.substring(start, end)
            if (line.isBlank()) continue

            val lead = indentWidth(line)
            var p = start + lead
            for (b in BULLETS) {
                if (line.startsWith(b, lead)) { p += b.length; break }
            }
            while (p < end && text[p] == ' ') p++

            val rest = text.substring(p, end)
            val existing = MARKERS.firstOrNull { isMarkerAt(rest, it) }

            // The marker region owns the space that follows it, so a swap keeps
            // exactly one space and an unmark doesn't leave a double behind.
            val wordEnd = p + (existing?.length ?: 0)
            val hasSpaceAfter = existing != null && wordEnd < end && text[wordEnd] == ' '
            val regionEnd = if (hasSpaceAfter) wordEnd + 1 else wordEnd

            val replacement = when {
                existing == null -> "TODO "
                existing.equals("DONE", true) || existing.equals("CANCELED", true) -> ""
                else -> if (hasSpaceAfter) "DONE " else "DONE"
            }
            val stop = if (existing == null) p else regionEnd
            spans.add(Span(p, stop, replacement))

            // A marker before the caret pushes it along; one after it doesn't.
            val d = replacement.length - (stop - p)
            if (p < selS) { selS += d; selE += d }
        }
        val safeS = selS.coerceIn(0, text.length)
        return Result(spans, safeS, selE.coerceIn(safeS, text.length))
    }

    /** The text a fresh line should start with, or null to insert a bare newline. */
    fun continuationFor(line: String): String? {
        if (line.isBlank()) return null
        val lead = line.takeWhile { it == '\t' || it == ' ' }
        val rest = line.substring(lead.length)
        for (b in BULLETS) {
            if (!rest.startsWith(b)) continue
            // "- TODO x" continues as "- TODO " so the marker is kept.
            val after = rest.substring(b.length).trimStart()
            val marker = MARKERS.firstOrNull { isMarkerAt(after, it) }
            return if (marker != null) "$lead$b $marker " else "$lead$b "
        }
        return null
    }

    /**
     * Applies a [Result]'s spans through a caller-supplied replace, so the editor
     * can write into an `Editable` (preserving the buffer's undo metadata) while
     * tests run the same arithmetic over a plain StringBuilder.
     *
     * Returns the selection to restore, which is [Result.selStart] **verbatim**.
     * LineOps already reports it in post-edit coordinates; adding the total
     * length delta again shifts the caret a second time, which on a line-start
     * caret puts it outside the line and makes promote look like it jumps to
     * the next one.
     */
    fun applySpans(
        result: Result,
        replace: (start: Int, end: Int, text: CharSequence) -> Unit,
    ): Int {
        var delta = 0
        for (span in result.spans.sortedBy { it.start }) {
            val at = span.start + delta
            replace(at, span.end + delta, span.text)
            delta += span.text.length - (span.end - span.start)
        }
        return result.selStart
    }
}
