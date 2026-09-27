package com.faucherd.markdownnotes

/**
 * Substring search within one document, for the viewer's find bar.
 *
 * Deliberately substring matching, not regex: what a reader types into a find
 * box is a literal string, and treating `.` or `[` as syntax is the fastest way
 * to make find feel broken. This also matches how `VaultSearch` behaves, so the
 * two find boxes in the app agree.
 *
 * Line numbers are resolved from a single precomputed offset table rather than
 * by counting newlines per match. On a large page with many matches, navigation
 * re-resolves a line for every step, and counting from the start of the
 * document each time makes each step O(document) — the search gets slower the
 * more you use it, which is exactly backwards.
 */
class FindInPage private constructor(
    /** Match ranges, in document order, non-overlapping. */
    val ranges: List<IntRange>,
    private val lineStarts: IntArray,
    private val textLength: Int,
) {

    /**
     * The **1-based** line containing [offset], matching the convention
     * `scrollToLine` and `EXTRA_FOCUS_LINE` already use. Offsets outside the
     * document clamp rather than throw: the caller is holding a range that was
     * valid a moment ago, and a file reloaded underneath it can invalidate it.
     */
    fun lineAt(offset: Int): Int {
        val clamped = offset.coerceIn(0, textLength)
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineStarts[mid] <= clamped) low = mid else high = mid - 1
        }
        return low + 1
    }

    /**
     * The index to move to from [current], wrapping at both ends.
     *
     * Wrapping is the point: on a find bar, "next" that stops dead at the last
     * hit reads as a bug, and there is no other affordance to continue.
     */
    fun step(current: Int, forward: Boolean): Int {
        val n = ranges.size
        if (n == 0) return -1
        val next = if (forward) current + 1 else current - 1
        return ((next % n) + n) % n
    }

    companion object {
        /**
         * Matches beyond this are still counted and still navigable, but not
         * highlighted. A one-letter query on a large page can match tens of
         * thousands of times, and laying down a span for each one costs enough
         * to stutter the scroll the reader is trying to do.
         */
        const val HIGHLIGHT_LIMIT = 1000

        /** Null when there is nothing to find: a blank query, or no match. */
        fun find(text: String, query: String, ignoreCase: Boolean = true): FindInPage? {
            if (query.isEmpty()) return null
            val ranges = ArrayList<IntRange>()
            var from = 0
            while (from <= text.length - query.length) {
                val at = text.indexOf(query, from, ignoreCase)
                if (at < 0) break
                // Non-overlapping: after a hit, resume past its end. Without
                // this, "aa" in "aaaa" reports three matches at 0, 1 and 2,
                // which are really two.
                ranges.add(at until at + query.length)
                from = at + query.length
            }
            if (ranges.isEmpty()) return null

            val starts = ArrayList<Int>()
            starts.add(0)
            for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
            return FindInPage(ranges, starts.toIntArray(), text.length)
        }
    }
}
