package com.faucherd.markdownnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Access to the Resilio-synced vault.
 *
 * The vault lives under the shared Documents volume, which is why the app asks
 * for All Files Access rather than a scoped-storage grant: we want real
 * java.io.File paths, so writes can use write-temp-then-rename instead of
 * truncating a file in place through a ContentResolver.
 */
object VaultAccess {

    /** All Files Access is a special grant, not a runtime permission. */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun sharedDocuments(): File? =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    fun externalRoot(): File? = Environment.getExternalStorageDirectory()
}
