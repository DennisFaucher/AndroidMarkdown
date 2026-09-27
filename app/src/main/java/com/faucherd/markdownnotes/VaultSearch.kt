package com.faucherd.markdownnotes

import java.io.File

data class VaultFile(
    val file: File,
    val kind: String,
    val title: String,
    /** Defaults to the directory, which is right for every real file. */
    val subtitle: String = kind,
    /**
     * True for a row that has no file behind it yet — today's journal before it
     * is written. Virtual rows open an empty buffer and materialise on first
     * save, mirroring how the web app synthesises empty days in its feed.
     */
    val isVirtual: Boolean = false,
) {
    val isConflict: Boolean get() = VaultLocator.isConflictFile(file.name)
}

data class SearchHit(
    val entry: VaultFile,
    val lineNumber: Int,
    val line: String,
)

/**
 * Vault-wide text search.
 *
 * The whole vault is a few megabytes, so this deliberately does *not* build an
 * inverted index: a linear scan over the markdown is fast enough that the only
 * thing worth optimising is avoiding redundant reads. Files whose
 * (size, lastModified) are unchanged since the previous scan are served from
 * memory.
 */
object VaultSearch {

    private val contentCache = HashMap<String, CachedContent>()

    private data class CachedContent(
        val size: Long,
        val modified: Long,
        val lines: List<String>,
    )

    fun clearCache() = contentCache.clear()

    private fun linesOf(file: File): List<String> {
        val size = file.length()
        val modified = file.lastModified()
        contentCache[file.absolutePath]?.let {
            if (it.size == size && it.modified == modified) return it.lines
        }
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            emptyList()
        }
        contentCache[file.absolutePath] = CachedContent(size, modified, lines)
        return lines
    }

    /**
     * Case-insensitive substring search. All query terms must appear somewhere
     * in the file, and at least one term must appear on the reported line — so
     * "tag work" narrows to files carrying both while still showing the most
     * relevant individual lines.
     */
    fun search(vault: File, query: String, limit: Int = 200): List<SearchHit> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()
        for (file in VaultLocator.markdownFiles(vault)) {
            if (VaultLocator.isConflictFile(file.name)) continue
            val entry = VaultFile(file, kindOf(file), titleOf(file))
            val lines = linesOf(file)
            val blob = lines.joinToString("\n").lowercase()
            if (!terms.all { blob.contains(it) }) continue

            lines.forEachIndexed { index, raw ->
                val lower = raw.lowercase()
                if (terms.any { lower.contains(it) }) {
                    hits.add(SearchHit(entry, index + 1, raw.trim()))
                }
            }
            if (hits.size >= limit) return hits.take(limit)
        }
        return hits
    }

    fun kindOf(file: File): String = file.parentFile?.name ?: ""

    /** Journals read better as their date; pages as their title. */
    fun titleOf(file: File): String {
        val name = file.nameWithoutExtension
        return if (kindOf(file) == "journals") name.replace('_', '-') else name
    }
}
