package com.faucherd.markdownnotes

import java.io.File
import java.time.LocalDate

/**
 * The three things this app is for. The top level is only these; each opens a
 * page listing its own rows.
 */
object Category {
    const val JOURNALS = "journals"
    const val PAGES = "pages"
    const val TODOS = "todos"
}

/**
 * Builds the rows for the top-level menu and for each category page.
 *
 * This lives outside the activities so the menu and the three pages cannot
 * drift apart, and so the grouping rules are unit testable without a Context —
 * which is why the labels are injected here rather than read from resources.
 * The activities pass real strings; tests pass dummies.
 */
class SectionRows(private val labels: Labels) {

    data class Labels(
        val journals: String,
        val pages: String,
        val todos: String,
        /** e.g. "3 entries" — how many rows the category page will show. */
        val entries: (Int) -> String,
        /** e.g. "14 open" */
        val openTodos: (Int) -> String,
        val today: String,
        val todaySubtitle: (LocalDate) -> String,
    )

    /**
     * Every file, with today's journal first when it has not been written yet.
     *
     * The virtual row leads so it is the obvious thing to tap on opening the
     * app, which is the whole point of offering it.
     */
    fun files(vault: File, today: LocalDate?): List<VaultFile> {
        val all = ArrayList(
            VaultLocator.markdownFiles(vault).map {
                VaultFile(it, VaultSearch.kindOf(it), VaultSearch.titleOf(it))
            },
        )
        if (today != null) {
            all.add(
                0,
                VaultFile(
                    file = File(vault, "journals/${VaultNames.journalFilename(today)}"),
                    kind = "journals",
                    title = labels.today,
                    subtitle = labels.todaySubtitle(today),
                    isVirtual = true,
                ),
            )
        }
        return all
    }

    /** Today's date, or null once the file exists — see [files]. */
    fun todayIfUnwritten(vault: File, date: LocalDate): LocalDate? =
        if (File(vault, "journals/${VaultNames.journalFilename(date)}").isFile) null else date

    /** The top level: three rows, nothing else. */
    fun categories(vault: File, today: LocalDate?, todos: List<TodoItem>): List<ListRow> {
        val all = files(vault, today)
        val journalRows = journalFiles(all)
        val pageRows = pageFiles(all)
        return listOf(
            ListRow.Category(Category.JOURNALS, labels.journals, labels.entries(journalRows.size)),
            ListRow.Category(Category.PAGES, labels.pages, labels.entries(pageRows.size)),
            ListRow.Category(Category.TODOS, labels.todos, labels.openTodos(todos.size)),
        )
    }

    /** One category's rows, with no redundant header: the toolbar names it. */
    fun forCategory(
        vault: File,
        category: String,
        today: LocalDate?,
        todos: List<TodoItem>,
    ): List<ListRow> {
        val all = files(vault, today)
        return when (category) {
            Category.JOURNALS -> journalFiles(all).map { ListRow.FileRow(it) }
            Category.PAGES -> pageFiles(all).map { ListRow.FileRow(it) }
            Category.TODOS -> todoRows(vault, all, todos)
            // Not reachable from the menu, which only offers the three. Falling
            // through to the page list means an unexpected directory still shows
            // up somewhere instead of silently vanishing from the app.
            else -> pageFiles(all).map { ListRow.FileRow(it) }
        }
    }

    fun title(category: String): String = when (category) {
        Category.JOURNALS -> labels.journals
        Category.PAGES -> labels.pages
        Category.TODOS -> labels.todos
        else -> labels.pages
    }

    private fun journalFiles(all: List<VaultFile>) = all.filter { it.kind == "journals" }

    /**
     * Just the pages. A file in some third directory is not listed anywhere,
     * because `VaultLocator.markdownFiles` only ever enumerates `journals/` and
     * `pages/` — the two directories the web app serves. An earlier version
     * claimed to sweep "anything not a journal" into the page list, which was
     * dead logic: such a file never reaches the app in the first place.
     */
    private fun pageFiles(all: List<VaultFile>) = all.filter { it.kind == "pages" }

    private fun todoRows(
        vault: File,
        all: List<VaultFile>,
        todos: List<TodoItem>,
    ): List<ListRow> {
        // Resolved back to the file row so a to-do is labelled with the same
        // text the file list uses, rather than showing a raw vault-relative path.
        val titles = all.filter { !it.isVirtual }
            .associateBy { it.file.relativeTo(vault).path }
        val rows = mutableListOf<ListRow>()
        for ((category, items) in VaultTodos.grouped(todos)) {
            rows += ListRow.TodoGroup(category, items.size)
            for (todo in items) {
                rows += ListRow.TodoRow(todo, titles[todo.path]?.title ?: todo.path)
            }
        }
        return rows
    }
}
