package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The dashboard is a checklist, so the only acceptable failure here is a silent
 * disagreement with the web app. Each test names the server rule it pins down;
 * the marker and empty-block cases are the ones that decide whether an item
 * exists in the list at all.
 */
class VaultTodosTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: String, body: String): File =
        File(tmp.root, path).apply {
            parentFile?.mkdirs()
            writeText(body)
        }

    private fun scan(body: String, path: String = "journals/2026_09_26.md") =
        VaultTodos.scan(write(path, body), path)

    // ---- markers -------------------------------------------------------

    @Test
    fun `an open marker on its own line is a todo`() {
        val todos = scan("- TODO write the docs")
        assertEquals(1, todos.size)
        assertEquals("TODO", todos[0].marker)
        assertEquals("write the docs", todos[0].text)
        assertEquals(0, todos[0].line)
    }

    @Test
    fun `every open marker is included`() {
        for (m in listOf("TODO", "DOING", "NOW", "LATER", "WAITING")) {
            assertEquals("expected $m to be open", 1, scan("- $m x").size)
        }
    }

    @Test
    fun `done and canceled are not open`() {
        assertTrue(scan("- DONE x").isEmpty())
        assertTrue(scan("- CANCELED x").isEmpty())
    }

    /** MARKER_RE is anchored, so the same word mid-sentence is prose. */
    @Test
    fun `a marker in the middle of a line is not a marker`() {
        assertTrue(scan("- write TODO docs later").isEmpty())
    }

    @Test
    fun `a marker must be the whole line`() {
        // "TODOx" is not "TODO" — the regex needs whitespace or end of line
        // after the marker, so these are ordinary prose lines.
        assertTrue(scan("- TODOx nope").isEmpty())
        assertTrue(scan("- TODONT").isEmpty())
        assertTrue(scan("- TODO/done").isEmpty())
    }

    /** build.ts drops a block with no content, so it has nothing to show. */
    @Test
    fun `a bare marker is not a todo`() {
        assertTrue(scan("- TODO").isEmpty())
        assertTrue(scan("- TODO   ").isEmpty())
    }

    @Test
    fun `a marker with only properties is not a todo`() {
        assertTrue(scan("- TODO\n  id:: 123\n  collapsed:: true").isEmpty())
    }

    @Test
    fun `a bare marker with real text below it is a todo`() {
        val todos = scan("- TODO\n  id:: 123\n  actually some prose here")
        assertEquals(1, todos.size)
        assertEquals("actually some prose here", todos[0].text)
    }

    // ---- blocks --------------------------------------------------------

    @Test
    fun `indent gives depth`() {
        val todos = scan("- TODO top\n\t- TODO nested")
        assertEquals(listOf(0, 1), todos.map { it.depth })
        assertEquals(listOf(0, 1), todos.map { it.line })
    }

    /** Only `- ` is a bullet in a Logseq vault; `*` and `+` are markdown lists. */
    @Test
    fun `star and plus bullets are not blocks`() {
        assertTrue(scan("* TODO not ours").isEmpty())
        assertTrue(scan("+ TODO not ours").isEmpty())
    }

    @Test
    fun `a bare dash with no text is a block but not a todo`() {
        assertTrue(scan("-").isEmpty())
    }

    @Test
    fun `line numbers are file lines not todo ordinals`() {
        val todos = scan("## Some heading\n\n- TODO one\n\n- TODO two")
        assertEquals(listOf(2, 4), todos.map { it.line })
    }

    // ---- categories ----------------------------------------------------

    @Test
    fun `the first todo-ending tag is the category`() {
        assertEquals("WorkToDo", scan("- TODO x #WorkToDo").single().category)
    }

    @Test
    fun `category matching ignores case but the stored spelling is kept`() {
        // Matches the server, which returns the stored tag unchanged. Grouping
        // case-insensitively while showing the stored spelling keeps #WorkToDo and
        // #worktodo from splitting into two groups.
        assertEquals("wtwtodo", scan("- TODO x #wtwtodo").single().category)
        assertEquals("Worktodo", scan("- TODO x #Worktodo").single().category)
    }

    @Test
    fun `only the first todo tag counts`() {
        assertEquals("WorkToDo", scan("- TODO x #WorkToDo #ProjectAToDo").single().category)
    }

    @Test
    fun `a non-todo tag is not the category`() {
        assertNull(scan("- TODO x #MCPNotes").single().category)
    }

    @Test
    fun `a tag may be found on a continuation line`() {
        assertEquals("WorkToDo", scan("- TODO x\n  #WorkToDo").single().category)
    }

    @Test
    fun `a tag inside a fence is a code sample not a tag`() {
        val body = """
            - TODO real work #WorkToDo
            - TODO documented here
            ```
            #ProjectAToDo
            ```
        """.trimIndent()
        val todos = scan(body)
        assertEquals(2, todos.size)
        assertEquals("WorkToDo", todos[0].category)
        assertNull("a tag in a fence must not categorise", todos[1].category)
    }

    @Test
    fun `logbook lines are not prose`() {
        val body = "- TODO x\n  :LOGBOOK:\n  CLOCK: [2026-09-26]\n  :END:"
        assertEquals(1, scan(body).size)
    }

    // ---- grouping ------------------------------------------------------

    @Test
    fun `categories come first and uncategorized last`() {
        val items = listOf(
            todo("Uncategorized", 0),
            todo("WorkToDo", 0),
            todo("ProjectAToDo", 0),
        )
        assertEquals(
            listOf("ProjectAToDo", "WorkToDo", VaultTodos.UNCATEGORIZED),
            VaultTodos.grouped(items).map { it.first },
        )
    }

    @Test
    fun `grouping preserves order within a category`() {
        val items = listOf(todo("WorkToDo", 5), todo("WorkToDo", 1), todo("WorkToDo", 3))
        assertEquals(listOf(5, 1, 3), VaultTodos.grouped(items).single().second.map { it.line })
    }

    @Test
    fun `an empty vault groups to nothing`() {
        assertTrue(VaultTodos.grouped(emptyList()).isEmpty())
    }

    @Test
    fun `a missing file yields no todos`() {
        assertTrue(VaultTodos.scan(File(tmp.root, "pages/Nope.md"), "pages/Nope.md").isEmpty())
    }

    @Test
    fun `a directory is not scanned as a file`() {
        assertTrue(VaultTodos.scan(tmp.root, "journals").isEmpty())
    }

    // ---- ordering across the vault -------------------------------------

    @Test
    fun `journals come before pages newest first`() {
        write("pages/Zebra.md", "- TODO page item")
        write("pages/Apple.md", "- TODO page item")
        write("journals/2026_09_25.md", "- TODO older day")
        write("journals/2026_09_26.md", "- TODO newer day")

        val order = VaultTodos.collect(tmp.root).map { it.path }
        assertEquals(
            listOf(
                "journals/2026_09_26.md",
                "journals/2026_09_25.md",
                "pages/Zebra.md",
                "pages/Apple.md",
            ),
            order,
        )
    }

    @Test
    fun `collect skips conflict copies`() {
        write("journals/2026_09_26.md", "- TODO real")
        write("journals/2026_09_26.conflict-2026-09-27T01-00-00-000Z.md", "- TODO ghost")
        assertEquals(listOf("real"), VaultTodos.collect(tmp.root).map { it.text })
    }

    private fun todo(category: String, line: Int) = TodoItem(
        path = "journals/2026_09_26.md",
        line = line,
        marker = "TODO",
        text = "x",
        category = category,
        depth = 0,
    )
}
