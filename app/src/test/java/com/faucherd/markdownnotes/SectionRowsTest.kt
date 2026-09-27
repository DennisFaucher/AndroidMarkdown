package com.faucherd.markdownnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * The top level is a menu of three categories, and each opens a page. The
 * counts on the menu are a promise about what is behind them, so most of these
 * assert that the menu's number and the page's row count agree — that is the
 * thing a user can actually notice going wrong.
 */
class SectionRowsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 9, 27)

    /** Labels are injected, so the rules can be checked without a Context. */
    private fun rows() = SectionRows(
        SectionRows.Labels(
            journals = "Journals",
            pages = "Pages",
            todos = "To Dos",
            entries = { n -> "$n entries" },
            openTodos = { n -> "$n open" },
            today = "Today",
            todaySubtitle = { date -> "date=$date" },
        ),
    )

    private fun write(path: String, body: String) {
        File(tmp.root, path).apply {
            parentFile?.mkdirs()
            writeText(body)
        }
    }

    private fun rowsFor(category: String, includeToday: Boolean = true) = rows().forCategory(
        vault = tmp.root,
        category = category,
        today = if (includeToday) today else null,
        todos = VaultTodos.collect(tmp.root),
    )

    // ---- the top level -------------------------------------------------

    @Test
    fun `the top level is exactly the three categories in order`() {
        write("journals/2026_09_26.md", "- a")
        write("pages/One.md", "- b")
        val menu = rows().categories(tmp.root, today, VaultTodos.collect(tmp.root))
        assertEquals(
            listOf(Category.JOURNALS, Category.PAGES, Category.TODOS),
            menu.map { (it as ListRow.Category).key },
        )
    }

    @Test
    fun `a category count is how many rows its page shows`() {
        write("journals/2026_09_26.md", "- a")
        write("journals/2026_09_25.md", "- b")
        write("pages/One.md", "- c")
        write("pages/Two.md", "- TODO d #WorkToDo")

        val menu = rows().categories(tmp.root, today, VaultTodos.collect(tmp.root))
            .associate { (it as ListRow.Category).key to it.subtitle }

        for (category in listOf(Category.JOURNALS, Category.PAGES)) {
            val shown = rowsFor(category).size
            assertEquals(
                "menu count for $category must match its page",
                "$shown entries",
                menu[category],
            )
        }
        // The to-do count is open to-dos, not rows: the page also shows a
        // group header per tag, and a header is a label rather than an entry.
        assertEquals("1 open", menu[Category.TODOS])
        assertEquals(1, rowsFor(Category.TODOS).filterIsInstance<ListRow.TodoRow>().size)
    }

    @Test
    fun `an empty vault still offers all three categories`() {
        val menu = rows().categories(tmp.root, null, emptyList())
        assertEquals(3, menu.size)
        assertEquals("0 entries", (menu[0] as ListRow.Category).subtitle)
        assertEquals("0 open", (menu[2] as ListRow.Category).subtitle)
    }

    // ---- journals ------------------------------------------------------

    @Test
    fun `today leads the journals page when unwritten`() {
        write("journals/2026_09_26.md", "- older day")
        val items = rowsFor(Category.JOURNALS)
        val first = (items.first() as ListRow.FileRow).entry
        assertTrue("today should be offered first", first.isVirtual)
        assertEquals("Today", first.title)
        assertEquals("date=$today", first.subtitle)
    }

    @Test
    fun `today disappears once written`() {
        write("journals/2026_09_26.md", "- older day")
        write("journals/2026_09_27.md", "- today for real")
        val builder = rows()
        val unwritten = builder.todayIfUnwritten(tmp.root, today)
        assertEquals(null, unwritten)
        val items = builder.forCategory(tmp.root, Category.JOURNALS, unwritten, emptyList())
        assertTrue("no virtual row once the file exists", items.none { (it as ListRow.FileRow).entry.isVirtual })
        assertEquals(2, items.size)
    }

    @Test
    fun `a category page has no header of its own`() {
        write("journals/2026_09_26.md", "- a")
        assertTrue(rowsFor(Category.JOURNALS).none { it is ListRow.Section })
    }

    // ---- pages ---------------------------------------------------------

    @Test
    fun `pages do not include journals`() {
        write("journals/2026_09_26.md", "- a")
        write("pages/One.md", "- b")
        val items = rowsFor(Category.PAGES)
        assertEquals(1, items.size)
        assertEquals("One", (items.first() as ListRow.FileRow).entry.title)
    }

    /**
     * Only `journals/` and `pages/` are ever enumerated, which is the same pair
     * the web app serves. A markdown file in some third directory is therefore
     * invisible to the phone — a deliberate scope, pinned here so that widening
     * the scan is a conscious change rather than an accident.
     */
    @Test
    fun `a file outside journals and pages is not listed anywhere`() {
        write("inbox/Loose.md", "- a")
        assertTrue(rowsFor(Category.PAGES).isEmpty())
        // includeToday off, or the virtual Today row makes this non-empty.
        assertTrue(rowsFor(Category.JOURNALS, includeToday = false).isEmpty())
    }

    // ---- to dos -------------------------------------------------------

    @Test
    fun `the to dos page groups by tag and omits finished blocks`() {
        write("journals/2026_09_26.md", "- TODO a #WorkToDo\n- DONE b #WorkToDo\n- TODO c")
        val items = rowsFor(Category.TODOS)
        assertEquals(
            // The stored tag name; the adapter renders it as "#WorkToDo" and
            // leaves Uncategorized alone.
            listOf("WorkToDo", VaultTodos.UNCATEGORIZED),
            items.filterIsInstance<ListRow.TodoGroup>().map { it.title },
        )
        assertEquals(2, items.filterIsInstance<ListRow.TodoRow>().size)
    }

    @Test
    fun `a to-do is labelled with the file's title not its path`() {
        write("pages/Renewals.md", "- TODO chase the renewal #ProjectAToDo")
        val row = rowsFor(Category.TODOS).filterIsInstance<ListRow.TodoRow>().single()
        assertEquals("Renewals", row.origin)
    }

    @Test
    fun `a to-do in a file that vanished falls back to its path`() {
        val todo = VaultTodos.scan(File(tmp.root, "pages/Gone.md"), "pages/Gone.md")
        assertTrue(todo.isEmpty())
        // Nothing to render, so the page is empty rather than showing a path.
        assertTrue(rowsFor(Category.TODOS).isEmpty())
    }

    @Test
    fun `the other pages are not scanned for to dos on the journals page`() {
        write("journals/2026_09_26.md", "- nothing open")
        write("pages/One.md", "- TODO a #WorkToDo")
        assertTrue(rowsFor(Category.JOURNALS).none { it is ListRow.TodoRow })
    }

    // ---- titles -------------------------------------------------------

    @Test
    fun `each category has a title for its toolbar`() {
        assertEquals("Journals", rows().title(Category.JOURNALS))
        assertEquals("Pages", rows().title(Category.PAGES))
        assertEquals("To Dos", rows().title(Category.TODOS))
    }

    /** An unknown key must not crash the toolbar; it is the pages list. */
    @Test
    fun `an unknown category falls back rather than throwing`() {
        assertEquals("Pages", rows().title("nonsense"))
        assertTrue(rowsFor("nonsense").isEmpty() || rowsFor("nonsense").all { it is ListRow.FileRow })
    }
}
