package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The vault search used to return the first directory it happened to find,
 * which meant two synced vaults were resolved by alphabetical luck and the user
 * was never told. These pin the replacement: find them all, in a stable order,
 * and let the caller decide.
 */
class VaultLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var counter = 0

    /** A fresh scan root per call; TemporaryFolder rejects a reused name. */
    private fun root(): File = tmp.newFolder("root${counter++}")

    /** Builds a vault, returning the directory holding journals/ and pages/. */
    private fun vault(parent: File, vararg parts: String): File {
        var dir = parent
        for (part in parts) dir = File(dir, part)
        File(dir, "journals").mkdirs()
        File(dir, "pages").mkdirs()
        return dir
    }

    /** The name of the directory *containing* the vault, for readable asserts. */
    private fun ownerOf(vault: File) = vault.parentFile.name

    private fun rel(file: File) =
        file.absolutePath.removePrefix(tmp.root.absolutePath).trimStart('/')

    // ---- finding every candidate ------------------------------------------

    @Test
    fun `finds two vaults rather than stopping at the first`() {
        val base = root()
        val work = vault(base, "work", "vault")
        val personal = vault(base, "personal", "vault")

        val found = VaultLocator.candidates(base)

        assertEquals(setOf(work.absolutePath, personal.absolutePath), found.map { it.absolutePath }.toSet())
    }

    @Test
    fun `order is stable so the chooser does not reshuffle between launches`() {
        val base = root()
        vault(base, "zebra", "notes")
        vault(base, "apple", "notes")
        vault(base, "mango", "notes")

        // The scan must not inherit the order the filesystem happened to hand
        // back directory entries, or the chooser reshuffles on every launch.
        val runs = List(5) { VaultLocator.candidates(base).map { it.absolutePath } }
        val expected = listOf("apple", "mango", "zebra").sorted()
        repeat(5) { assertEquals(expected, runs[it].map { ownerOf(File(it)) }) }
        assertEquals("all runs identical", 1, runs.distinct().size)
    }

    @Test
    fun `a directory with only one of journals and pages is not a vault`() {
        val base = root()
        File(base, "halfbaked/journals").mkdirs()
        val real = vault(base, "real", "vault")

        assertEquals(listOf(rel(real)), VaultLocator.candidates(base).map { rel(it) })
    }

    @Test
    fun `does not descend into a vault it already found`() {
        // A nested vault is not a second vault; treating it as one would offer
        // the user a choice between a vault and a folder inside it.
        val base = root()
        val outer = vault(base, "outer")
        val inner = vault(outer, "inner")

        val found = VaultLocator.candidates(base)

        assertEquals(listOf(outer.absolutePath), found.map { it.absolutePath })
        assertTrue("inner vault should not be offered", found.none { it.absolutePath == inner.absolutePath })
    }

    @Test
    fun `hidden directories are skipped`() {
        // Sync bookkeeping and dotfile churn are not vaults, and walking them
        // is both slow and a source of false positives.
        val base = root()
        vault(base, ".hidden", "vault")
        val real = vault(base, "visible", "vault")

        assertEquals(listOf(real.absolutePath), VaultLocator.candidates(base).map { it.absolutePath })
    }

    @Test
    fun `stops descending past the depth cap instead of walking the whole volume`() {
        val base = root()
        val deep = vault(base, "a", "b", "c", "d", "e", "f", "g", "h", "i", "vault")
        val shallow = vault(base, "shallow", "vault")

        val found = VaultLocator.candidates(base)

        assertTrue("beyond the cap should not be found", found.none { it.absolutePath == deep.absolutePath })
        assertEquals(listOf(shallow.absolutePath), found.map { it.absolutePath })
    }

    @Test
    fun `a directory with nothing in it contributes no vault`() {
        // The guard against a vanished or unreadable folder is `listFiles() ?: return`,
        // which cannot be provoked on the JVM as the file owner; an empty
        // directory exercises the same "no children, no crash" path.
        val base = root()
        val real = vault(base, "real", "vault")
        File(base, "empty").mkdirs()

        assertEquals(listOf(real.absolutePath), VaultLocator.candidates(base).map { it.absolutePath })
    }

    @Test
    fun `isVault recognises a valid vault and nothing else`() {
        val good = vault(root(), "good")
        val bare = root()
        File(bare, "journals").mkdirs()

        assertTrue(VaultLocator.isVault(good))
        assertTrue("a missing directory is not a vault", !VaultLocator.isVault(File(bare, "nope")))
        assertTrue("journals alone is not a vault", !VaultLocator.isVault(bare))
    }

    // ---- deciding which one to use ---------------------------------------

    private fun vaultNamed(name: String): File = vault(tmp.root, name)

    @Test
    fun `no candidates means the vault is not found`() {
        assertTrue(VaultLocator.resolve(emptyList(), remembered = null) is VaultLocator.Pick.NotFound)
    }

    @Test
    fun `one candidate is used without asking`() {
        val only = vaultNamed("only")
        assertEquals(VaultLocator.Pick.Single(only), VaultLocator.resolve(listOf(only), null))
    }

    @Test
    fun `two candidates require a choice`() {
        val a = vaultNamed("a")
        val b = vaultNamed("b")
        assertEquals(
            VaultLocator.Pick.Choice(listOf(a, b)),
            VaultLocator.resolve(listOf(a, b), remembered = null),
        )
    }

    @Test
    fun `three candidates still ask rather than taking the first`() {
        // This is the regression that motivated the whole change: the old code
        // returned the first match, so a third vault arriving silently changed
        // which notes the app opened.
        val all = listOf(vaultNamed("a"), vaultNamed("b"), vaultNamed("c"))
        val pick = VaultLocator.resolve(all, remembered = null)
        assertEquals(VaultLocator.Pick.Choice(all), pick)
    }

    @Test
    fun `a remembered choice wins and is not asked again`() {
        val a = vaultNamed("a")
        val b = vaultNamed("b")
        assertEquals(
            VaultLocator.Pick.Single(b),
            VaultLocator.resolve(listOf(a, b), remembered = b),
        )
    }

    @Test
    fun `a remembered choice that no longer looks like a vault is ignored`() {
        // The vault can be renamed or deleted by Resilio. Honouring a stale
        // preference would leave the app pointed at nothing.
        val a = vaultNamed("a")
        val stale = File(tmp.root, "gone")
        assertEquals(
            VaultLocator.Pick.Single(a),
            VaultLocator.resolve(listOf(a), remembered = stale),
        )
    }

    @Test
    fun `a remembered choice is preferred over a stale one-of-many`() {
        // Picking a vault must not be silently undone by a re-scan that now
        // sees something new, or the user can never settle on one.
        val a = vaultNamed("a")
        val b = vaultNamed("b")
        val c = vaultNamed("c")
        assertEquals(VaultLocator.Pick.Single(b), VaultLocator.resolve(listOf(a, b, c), b))
    }

    @Test
    fun `a remembered vault outside the scan root is still honoured`() {
        // The choice is stored as an absolute path, so it keeps working if the
        // search is ever widened past Documents.
        val outside = vault(tmp.root, "outside", "notes")
        assertEquals(
            VaultLocator.Pick.Single(outside),
            VaultLocator.resolve(emptyList(), remembered = outside),
        )
    }
}
