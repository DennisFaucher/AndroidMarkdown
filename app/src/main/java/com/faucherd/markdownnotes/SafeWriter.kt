package com.faucherd.markdownnotes

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reading and, more importantly, *writing* vault files without losing anything.
 *
 * The phone is a third writer in a vault that the web app and Resilio are also
 * writing. Two rules follow, and everything here exists to enforce them:
 *
 *  1. Never truncate in place. A write goes to a sibling temp file, is fsynced,
 *     and is then renamed over the target. If any step fails the original is
 *     still there — a half-written note is the one genuinely unacceptable outcome.
 *  2. Never silently discard. If the file changed since it was opened, the user
 *     decides, and "keep mine" moves the external version to a conflict sibling
 *     rather than overwriting it.
 *
 * The temp and conflict naming intentionally matches the server's
 * `server/src/vault/write.ts`, so debris looks the same no matter which writer
 * produced it, and the web app's `.conflict-` filter ignores our copies too.
 */
object SafeWriter {

    data class Snapshot(
        val text: String,
        val hash: String,
        val lastModified: Long,
        val size: Long,
    )

    /** SHA-1 hex, matching the server's hashContent so the two are comparable. */
    fun hashOf(raw: String): String {
        val d = MessageDigest.getInstance("SHA-1")
        return d.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun snapshot(file: File): Snapshot? {
        if (!file.isFile) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            Snapshot(text, hashOf(text), file.lastModified(), file.length())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Write via temp + rename. Throws with the original file untouched on any
     * failure; never leaves a partially written note in place.
     */
    fun writeAtomic(file: File, text: String) {
        try {
            writeAtomicInner(file, text)
        } catch (e: Exception) {
            // Every failure mode reports the same guarantee, because that is the
            // only thing a caller can act on: nothing was written, and the
            // original is still exactly as it was.
            throw IOException("could not save ${file.name}; the original is unchanged", e)
        }
    }

    private fun writeAtomicInner(file: File, text: String) {
        val dir = file.parentFile
            ?: throw IOException("${file.name} has no parent directory")
        if (!dir.isDirectory) throw IOException("${dir.path} is not a directory")

        var tmp: File? = null
        try {
            tmp = File(dir, "${file.name}.tmp-${stampSuffix()}")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveOver(tmp, file)
            tmp = null // renamed away, nothing left to clean
        } finally {
            // Runs even for an Error, so a failed save never leaves debris.
            tmp?.delete()
        }
    }

    private fun moveOver(src: File, dst: File) {
        val s = src.toPath()
        val d = dst.toPath()
        try {
            Files.move(s, d, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: AtomicMoveNotSupportedException) {
            // FUSE-backed emulated storage often can't promise atomicity; a
            // replace is still far better than writing into the target.
        }
        Files.move(s, d, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * `<name>.conflict-<ISO>.md`, the same shape the server produces. The
     * original extension is preserved for any file type — stripping only `.md`
     * would turn `notes.txt` into `notes.txt.conflict-<stamp>`.
     */
    fun conflictSibling(file: File, now: Long = System.currentTimeMillis()): File {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0 && dot < name.length - 1
        val ext = if (hasExt) name.substring(dot) else ""
        val base = if (hasExt) name.substring(0, dot) else name
        return File(file.parentFile, "$base.conflict-${isoStamp(now)}$ext")
    }

    /**
     * Move whatever is on disk aside so the caller can take the canonical path.
     * Returns the preserved file, or null if there was nothing to preserve.
     */
    fun preserveAsConflictCopy(file: File, now: Long = System.currentTimeMillis()): File? {
        if (!file.isFile) return null
        val target = conflictSibling(file, now)
        return try {
            moveOver(file, target)
            target
        } catch (e: Exception) {
            null
        }
    }

    private fun stampSuffix(): String =
        // android.os.Process, NOT java.lang.ProcessHandle: the latter is a
        // Java 9 API that Android does not have, and touching it throws
        // NoClassDefFoundError on the very first save.
        "${android.os.Process.myPid()}-${System.currentTimeMillis()}"

    /** ISO-8601 with ':' and '.' replaced by '-', matching the server's stamp. */
    private fun isoStamp(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }
}
