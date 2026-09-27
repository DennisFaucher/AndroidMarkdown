package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * SafeWriter is the only code in the app that can destroy a note, so it is
 * tested against real files rather than mocked. The properties that matter:
 * a write never truncates in place, a failed write leaves the original intact
 * and no debris, and "keep mine" never discards the other version.
 */
class SafeWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, content: String): File =
        File(tmp.root, name).apply { writeText(content) }

    private fun debris(dir: File): List<String> =
        dir.list()?.filter { it.contains(".tmp-") } ?: emptyList()

    // ---- hashing -------------------------------------------------------

    @Test
    fun `hash matches the known SHA-1 of the input`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SafeWriter.hashOf("abc"))
    }

    @Test
    fun `hash is stable and content-sensitive`() {
        val a = SafeWriter.hashOf("- TODO one\n")
        assertEquals(a, SafeWriter.hashOf("- TODO one\n"))
        assertFalse(a == SafeWriter.hashOf("- TODO two\n"))
    }

    // ---- snapshot ------------------------------------------------------

    @Test
    fun `snapshot reports text, hash, size and mtime`() {
        val f = file("note.md", "- hello\n")
        val s = SafeWriter.snapshot(f)
        assertNotNull(s)
        assertEquals("- hello\n", s!!.text)
        assertEquals(SafeWriter.hashOf("- hello\n"), s.hash)
        assertEquals(8L, s.size)
        assertEquals(f.lastModified(), s.lastModified)
    }

    @Test
    fun `snapshot of a missing file is null`() {
        assertNull(SafeWriter.snapshot(File(tmp.root, "nope.md")))
    }

    // ---- writing -------------------------------------------------------

    @Test
    fun `write creates the file with exact bytes`() {
        val f = File(tmp.root, "new.md")
        SafeWriter.writeAtomic(f, "- TODO one\n\t- nested\n")
        assertEquals("- TODO one\n\t- nested\n", f.readText())
    }

    @Test
    fun `write overwrites an existing file completely`() {
        val f = file("note.md", "- a very long original line that must fully disappear\n")
        SafeWriter.writeAtomic(f, "- short\n")
        assertEquals("- short\n", f.readText())
    }

    @Test
    fun `write leaves no temp file behind on success`() {
        val f = file("note.md", "- one\n")
        SafeWriter.writeAtomic(f, "- two\n")
        assertTrue("debris: ${debris(tmp.root)}", debris(tmp.root).isEmpty())
    }

    @Test
    fun `repeated writes never accumulate temp files`() {
        val f = file("note.md", "- start\n")
        repeat(25) { SafeWriter.writeAtomic(f, "- pass $it\n") }
        assertTrue("debris: ${debris(tmp.root)}", debris(tmp.root).isEmpty())
    }

    @Test
    fun `a failed write leaves the original untouched and no debris`() {
        // Induce a real failure: make the target path a non-empty directory, so
        // the rename cannot succeed.
        val target = File(tmp.root, "blocked.md")
        assertTrue(target.mkdirs())
        File(target, "child.txt").writeText("precious")

        try {
            SafeWriter.writeAtomic(target, "- replacement\n")
            fail("expected the write to fail")
        } catch (e: IOException) {
            // expected
        }

        assertTrue("target was destroyed", target.isDirectory)
        assertEquals("precious", File(target, "child.txt").readText())
        assertTrue("debris: ${debris(tmp.root)}", debris(tmp.root).isEmpty())
    }

    @Test
    fun `a write into a missing directory fails loudly`() {
        val f = File(tmp.root, "no-such-dir/note.md")
        try {
            SafeWriter.writeAtomic(f, "- x\n")
            fail("expected the write to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unchanged"))
        }
        assertFalse(f.exists())
    }

    @Test
    fun `writes are byte-exact for tabs, unicode and CRLF`() {
        val f = File(tmp.root, "exact.md")
        val body = "- TODO caf\u00e9 \u2192\n\r\n\t- indented \u00e9\u00e8\n"
        SafeWriter.writeAtomic(f, body)
        assertArrayEqualsBytes(body, f.readBytes())
    }

    // ---- conflict copies -----------------------------------------------

    @Test
    fun `conflict sibling matches the server naming shape`() {
        val f = file("2026-09-26.md", "x")
        val c = SafeWriter.conflictSibling(f, 1_700_000_000_000L)
        // <name>.conflict-<ISO with : and . replaced by ->.md
        assertTrue(c.name, c.name.matches(Regex("""2026-09-26\.conflict-[\d\-]+T[\d\-]+Z\.md""")))
        assertEquals("2026-09-26.conflict-2023-11-14T22-13-20-000Z.md", c.name)
    }

    @Test
    fun `conflict sibling of a non-markdown file keeps its extension`() {
        val c = SafeWriter.conflictSibling(File(tmp.root, "notes.txt"), 1_700_000_000_000L)
        assertTrue(c.name, c.name.startsWith("notes.conflict-"))
        assertTrue(c.name, c.name.endsWith(".txt"))
    }

    @Test
    fun `preserve moves the on-disk version aside and keeps its content`() {
        val f = file("note.md", "theirs")
        val kept = SafeWriter.preserveAsConflictCopy(f)
        assertNotNull(kept)
        assertTrue(kept!!.name.contains(".conflict-"))
        assertEquals("theirs", kept.readText())
        assertFalse("original path must be free for our version", f.exists())
    }

    @Test
    fun `preserve of a missing file is a no-op`() {
        assertNull(SafeWriter.preserveAsConflictCopy(File(tmp.root, "gone.md")))
    }

    @Test
    fun `keep-mine preserves both versions and is never destructive`() {
        val f = file("note.md", "theirs\n")
        val kept = SafeWriter.preserveAsConflictCopy(f)
        SafeWriter.writeAtomic(f, "mine\n")
        assertEquals("mine\n", f.readText())
        assertEquals("theirs\n", kept!!.readText())
    }

    @Test
    fun `preserving a file that is not there is a no-op, not a new copy`() {
        val f = file("note.md", "v1\n")
        assertNotNull(SafeWriter.preserveAsConflictCopy(f))
        // It is gone now; a second attempt must not invent an empty copy.
        assertNull(SafeWriter.preserveAsConflictCopy(f))
    }

    @Test
    fun `two conflicts a second apart get distinct names`() {
        val f = file("note.md", "v1\n")
        val first = SafeWriter.preserveAsConflictCopy(f, 1_700_000_000_000L)
        f.writeText("v2\n")
        val second = SafeWriter.preserveAsConflictCopy(f, 1_700_000_001_000L)
        assertFalse(first!!.name == second!!.name)
        assertEquals("v1\n", first.readText())
        assertEquals("v2\n", second!!.readText())
    }

    private fun assertArrayEqualsBytes(expected: String, actual: ByteArray) {
        assertEquals(expected.toByteArray(Charsets.UTF_8).toList(), actual.toList())
    }
}
