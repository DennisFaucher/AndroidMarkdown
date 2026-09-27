package com.faucherd.markdownnotes

import java.io.File

/**
 * Locates the vault inside the Resilio sync tree.
 *
 * The path is discovered rather than hardcoded. Resilio mirrors the Mac folder
 * layout, but the observed casing has already differed once (the Mac repo uses
 * "Docker", the phone shows "docker"), and Android's emulated storage is
 * case-sensitive. Walking down until a directory has both journals/ and pages/
 * is robust to that, to a renamed folder, and to a shallower sync root.
 */
object VaultLocator {

    private const val MAX_DEPTH = 8
    private const val MAX_DIRS_PER_LEVEL = 400

    private var cached: File? = null

    fun locate(force: Boolean = false): File? {
        if (!force) cached?.let { if (it.isDirectory) return it }
        val found = search(VaultAccess.sharedDocuments() ?: return null, 0)
        if (found != null) cached = found
        return found
    }

    fun forget() {
        cached = null
    }

    private fun search(dir: File, depth: Int): File? {
        if (depth > MAX_DEPTH) return null
        val children = dir.listFiles() ?: return null
        if (children.size > MAX_DIRS_PER_LEVEL) return null

        // A vault is exactly this: a directory holding both of these.
        if (File(dir, "journals").isDirectory && File(dir, "pages").isDirectory) return dir

        // Depth-first over directories only, skipping sync bookkeeping.
        for (child in children.sortedBy { it.name }) {
            if (!child.isDirectory) continue
            if (child.name.startsWith(".")) continue
            search(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** All markdown files, journals first. */
    fun markdownFiles(vault: File): List<File> {
        val out = ArrayList<File>()
        for (sub in listOf("journals", "pages")) {
            File(vault, sub).listFiles { f ->
                f.isFile && f.name.endsWith(".md") && !isConflictFile(f.name)
            }
                ?.sortedByDescending { it.name }
                ?.forEach { out.add(it) }
        }
        return out
    }

    fun isConflictFile(name: String): Boolean = name.contains(".conflict-")
}
