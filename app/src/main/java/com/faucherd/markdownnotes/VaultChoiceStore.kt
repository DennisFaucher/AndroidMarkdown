package com.faucherd.markdownnotes

import android.content.Context
import java.io.File

/**
 * Remembers which vault the user picked, for the case where the sync tree holds
 * more than one.
 *
 * Only an explicit choice is stored. A single auto-discovered vault is left
 * unrecorded on purpose: if a second vault syncs in later, the app should ask
 * rather than silently keep using whichever it happened to find first.
 *
 * The absolute path is stored rather than a path relative to the scan root, so
 * a remembered vault still resolves if the search ever widens past Documents.
 */
class VaultChoiceStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(): File? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let(::File)

    fun set(vault: File) {
        prefs.edit().putString(KEY, vault.absolutePath).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val NAME = "markdownnotes.vault"
        const val KEY = "chosen_path"
    }
}
