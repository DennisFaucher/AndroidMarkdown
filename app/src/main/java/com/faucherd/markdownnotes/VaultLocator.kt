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
 *
 * This finds *every* candidate rather than the first. It used to return the
 * first match, which meant a second synced vault was resolved by alphabetical
 * luck and silently — the user got whichever sorted earlier and had no way to
 * find out that a choice had been made for them. More than one is a question
 * for the user, not something to guess at; see [resolve].
 */
object VaultLocator {

    private const val MAX_DEPTH = 8
    private const val MAX_DIRS_PER_LEVEL = 400

    /** A vault is exactly this: a directory holding both of these. */
    fun isVault(dir: File): Boolean =
        File(dir, "journals").isDirectory && File(dir, "pages").isDirectory

    /**
     * Every vault at or below [from], in a stable order.
     *
     * [from] defaults to the shared Documents volume, which is where the sync
     * client puts it. The walk does not descend into a directory it has already
     * accepted: a vault nested inside a vault is a folder, not an alternative,
     * and offering it would be a choice between two things that are not peers.
     */
    fun candidates(from: File? = VaultAccess.sharedDocuments()): List<File> {
        val root = from ?: return emptyList()
        val found = ArrayList<File>()
        collect(root, 0, found)
        return found.sortedBy { it.absolutePath }
    }

    private fun collect(dir: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        if (children.size > MAX_DIRS_PER_LEVEL) return

        if (isVault(dir)) {
            into.add(dir)
            return
        }

        // Depth-first over directories only, skipping sync bookkeeping.
        for (child in children.sortedBy { it.name }) {
            if (!child.isDirectory) continue
            if (child.name.startsWith(".")) continue
            collect(child, depth + 1, into)
        }
    }

    /** What [resolve] concluded. Exhaustive so callers cannot forget a case. */
    sealed interface Pick {
        /** Exactly one vault, or the one the user chose. Ready to use. */
        data class Single(val vault: File) : Pick

        /** More than one vault and no remembered choice: the user must pick. */
        data class Choice(val candidates: List<File>) : Pick

        /** Nothing looks like a vault. */
        object NotFound : Pick
    }

    /**
     * Decides which vault to use, preferring an explicit prior choice.
     *
     * A remembered vault is only honoured while it still looks like a vault:
     * Resilio can rename or remove it, and a stale preference would otherwise
     * pin the app to a directory that no longer holds notes. Note that a
     * *single* auto-discovered vault is deliberately not remembered — if a
     * second vault syncs in later, the user should be asked rather than have
     * the app quietly carry on with the one it happened to find first.
     */
    fun resolve(candidates: List<File>, remembered: File?): Pick {
        if (remembered != null && isVault(remembered)) return Pick.Single(remembered)
        return when (candidates.size) {
            0 -> Pick.NotFound
            1 -> Pick.Single(candidates[0])
            else -> Pick.Choice(candidates)
        }
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
