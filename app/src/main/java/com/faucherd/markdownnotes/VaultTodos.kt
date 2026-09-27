package com.faucherd.markdownnotes

import java.io.File
import java.io.IOException

/**
 * One open to-do, located well enough to open it.
 */
data class TodoItem(
    /** Vault-relative, e.g. `journals/2026_09_26.md`. */
    val path: String,
    /** 0-based line the to-do starts on, which is what ViewerActivity scrolls to. */
    val line: Int,
    val marker: String,
    /** First line with the marker stripped. */
    val text: String,
    /**
     * The block's tag ending in "ToDo", e.g. `WorkToDo`. null when it has none.
     *
     * The tag's original casing is kept, because the server returns the stored
     * tag unchanged and the web app renders `#${category}`. Matching
     * case-insensitively but *displaying* the stored spelling is what keeps
     * `#WorkToDo` and `#worktodo` from becoming two groups on one screen — at the
     * cost of agreeing with the web app's known case-sensitivity wrinkle.
     */
    val category: String?,
    val depth: Int,
)

/**
 * A vault-wide scan for open to-dos, so the phone's dashboard agrees with the
 * web app's instead of being a second, subtly different opinion.
 *
 * Nothing here is a design decision; every rule is transcribed from the server,
 * because the only way to keep two independent implementations in agreement is
 * to write down where each one came from:
 *
 *  - `tokenize.ts` — a block starts at `BULLET_RE` and owns every following
 *    line that is not itself a bullet. Only `- ` and a bare `-` count; `*` and
 *    `+` do not, because this is a Logseq vault.
 *  - `derive.ts` `MARKER_RE` — **anchored**, so a marker is only a marker when
 *    it is the whole line. `write TODO docs` is prose, not a to-do. The server
 *    uses the same anchor for its /api/todos, so an unanchored copy here would
 *    show a different set.
 *  - `build.ts` — a block whose content is empty is never indexed, so a bare
 *    `- TODO` is not a to-do. Without this the phone would offer to-do items the
 *    web app has never heard of.
 *  - `search.ts` — `OPEN_MARKERS` are the open ones, `category` is the first tag
 *    ending in "ToDo" (case-insensitive, tag without the `#`), and the ordering
 *    is journals newest-first, then pages.
 *
 * The one deliberate difference: the server's block *properties* are read without
 * regard for code fences, which is a known bug there (a journal that documents
 * Logseq properties in a fence has its examples parsed as real ones). That bug
 * only affects `collapsed`, and a to-do inside a code fence is documentation
 * rather than a real to-do, so excluding fenced text here is what we want. Tag
 * extraction *is* fence-aware on both sides.
 */
object VaultTodos {

    /** `DONE` and `CANCELED` are absent because a finished item is not open. */
    val OPEN_MARKERS = setOf("TODO", "DOING", "NOW", "LATER", "WAITING")

    private const val MARKERS = "TODO|DONE|NOW|LATER|DOING|WAITING|CANCELED"

    private val MARKER_RE = Regex("^($MARKERS)(?:\\s+(.*))?$")
    private val PROPERTY_RE = Regex("^([A-Za-z][A-Za-z0-9_.\\-/]*):: ?(.*)$")
    private val TAG_RE = Regex("#([A-Za-z][A-Za-z0-9_\\-/]*)")

    /** Tabs give depth, matching the server's tokenizer. */
    private val BULLET_RE = Regex("^(\t*)(?:- (.*)|-())$")

    /**
     * Shown for a to-do with no *ToDo tag, which is the web app's own label for
     * that bucket. The adapter renders it without a "#" and the categories with
     * one, so it is part of the display contract rather than a private detail.
     */
    const val UNCATEGORIZED = "Uncategorized"

    /**
     * Scans one file. Returns nothing for a file that is missing, unreadable or
     * not a file, so a row can never point at a to-do that is not there.
     */
    fun scan(file: File, path: String): List<TodoItem> {
        if (!file.isFile) return emptyList()
        val lines = try {
            file.readLines()
        } catch (e: IOException) {
            return emptyList()
        }
        val out = mutableListOf<TodoItem>()
        var i = 0
        while (i < lines.size) {
            val bullet = BULLET_RE.matchEntire(lines[i])
            if (bullet == null) {
                i++
                continue
            }
            // A block owns every following non-bullet line, so the extent has to
            // be measured before the block can be judged.
            //
            // The prefix strip is not cosmetic: it is what turns "  id:: 123"
            // into "id:: 123" so the property rules below can see it. Without it
            // every property line counts as prose, and a bare "- TODO" carrying
            // nothing but properties would look like a real to-do.
            val depth = bullet.groupValues[1].length
            val prefix = "\t".repeat(depth) + "  "
            var j = i + 1
            val cont = mutableListOf<String>()
            while (j < lines.size && !BULLET_RE.matches(lines[j])) {
                val line = lines[j]
                cont.add(if (line.startsWith(prefix)) line.substring(prefix.length) else line)
                j++
            }

            val markerMatch = MARKER_RE.matchEntire(bullet.groupValues[2])
            if (markerMatch != null && markerMatch.groupValues[1] in OPEN_MARKERS) {
                val text = markerMatch.groupValues[2]
                val content = deriveContent(text, cont)
                if (content.isNotBlank()) {
                    out += TodoItem(
                        path = path,
                        line = i,
                        marker = markerMatch.groupValues[1],
                        text = text.ifBlank { firstNonBlank(content) },
                        category = categoryOf(content),
                        depth = bullet.groupValues[1].length,
                    )
                }
            }
            i = j
        }
        return out
    }

    /**
     * The web app's dashboard order: journals before pages, each newest first,
     * then position within the file. Journals are `YYYY_MM_DD.md` so a plain
     * descending string sort *is* descending by date.
     */
    fun collect(vault: File): List<TodoItem> {
        val rows = mutableListOf<TodoItem>()
        for (file in VaultLocator.markdownFiles(vault)) {
            rows += scan(file, file.relativeTo(vault).path)
        }
        val byFile = rows.groupBy { it.path }
        val keys = byFile.keys.sortedWith(
            compareBy<String>({ kindRank(it) }).thenByDescending { it },
        )
        return keys.flatMap { path -> byFile.getValue(path).sortedBy { it.line } }
    }

    /** Groups for display, matching TodosView: categories then Uncategorized last. */
    fun grouped(todos: List<TodoItem>): List<Pair<String, List<TodoItem>>> {
        val byCategory = LinkedHashMap<String, MutableList<TodoItem>>()
        for (t in todos) {
            byCategory.getOrPut(t.category ?: UNCATEGORIZED) { mutableListOf() }.add(t)
        }
        return byCategory.entries
            .sortedWith(compareBy({ if (it.key == UNCATEGORIZED) 1 else 0 }, { it.key }))
            .map { it.key to it.value }
    }

    private fun kindRank(path: String) = if (path.startsWith("journals/")) 0 else 1

    /** [text, ...contentLines].join("\n") with the server's exclusions applied. */
    private fun deriveContent(text: String, cont: List<String>): String {
        val lines = mutableListOf(text)
        var inLogbook = false
        for (line in cont) {
            when {
                line == ":LOGBOOK:" -> inLogbook = true
                line == ":END:" -> inLogbook = false
                // Logbook entries are timestamps, not prose.
                inLogbook -> {}
                PROPERTY_RE.matches(line) -> {}
                else -> lines.add(line)
            }
        }
        return lines.joinToString("\n")
    }

    private fun firstNonBlank(content: String): String =
        content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun categoryOf(content: String): String? =
        TAG_RE.findAll(stripFencedCode(content))
            .map { it.groupValues[1] }
            .firstOrNull { it.endsWith("todo", ignoreCase = true) }

    /** A #tag in a fenced block is a code sample, not a tag. */
    private fun stripFencedCode(text: String): String {
        val out = mutableListOf<String>()
        var inFence = false
        for (line in text.split("\n")) {
            if (line.trim().startsWith("```")) {
                inFence = !inFence
                continue
            }
            out.add(if (inFence) "" else line)
        }
        return out.joinToString("\n")
    }
}
