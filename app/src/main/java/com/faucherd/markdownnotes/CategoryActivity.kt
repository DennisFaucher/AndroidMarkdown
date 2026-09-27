package com.faucherd.markdownnotes

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.faucherd.markdownnotes.databinding.ActivityCategoryBinding
import java.io.File
import java.time.LocalDate

/**
 * One of the three category pages opened from the top level.
 *
 * The rows come from [SectionRows], the same builder the top level uses, so
 * what a category contains and what its count on the menu said are by
 * construction the same thing.
 */
class CategoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryBinding
    private var vault: File? = null

    private val adapter = FileAdapter(
        onFile = { entry -> openFile(entry) },
        onTodo = { todo, origin -> openTodo(todo, origin) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        useLightStatusBarIcons()

        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: Category.JOURNALS
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = rows().title(category)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Coming back from the editor or the viewer, the file or to-do the user
        // just changed may have moved, been created or been deleted by Resilio.
        refresh()
    }

    private fun rows(): SectionRows = SectionRows(
        SectionRows.Labels(
            journals = getString(R.string.section_journals),
            pages = getString(R.string.section_pages),
            todos = getString(R.string.section_todos),
            entries = { resources.getQuantityString(R.plurals.row_count, it, it) },
            openTodos = { getString(R.string.todo_count_open, it) },
            today = getString(R.string.entry_today),
            todaySubtitle = { date -> VaultNames.formatJournalTitle(date) },
        ),
    )

    private fun refresh() {
        val found = VaultLocator.locate()
        if (found == null) {
            // The vault cannot disappear from under a page the user is looking
            // at, but if it somehow does, leaving them on an empty screen with no
            // way back would be worse than a message.
            finish()
            return
        }
        vault = found
        val builder = rows()
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: Category.JOURNALS
        val today = builder.todayIfUnwritten(found, LocalDate.now())
        val todos = if (category == Category.TODOS) VaultTodos.collect(found) else emptyList()
        val items = builder.forCategory(found, category, today, todos)
        adapter.submit(items)

        val empty = items.isEmpty()
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.empty.setText(
                if (category == Category.TODOS) {
                    R.string.category_empty_todos
                } else {
                    R.string.category_empty_files
                },
            )
        }
    }

    private fun openFile(entry: VaultFile) {
        val target = if (entry.file.isFile) ViewerActivity::class.java else EditorActivity::class.java
        startActivity(
            Intent(this, target)
                .putExtra(EXTRA_PATH, entry.file.absolutePath)
                .putExtra(EXTRA_TITLE, entry.title),
        )
    }

    private fun openTodo(todo: TodoItem, origin: String) {
        val v = vault ?: return
        val file = File(v, todo.path)
        val target = if (file.isFile) ViewerActivity::class.java else EditorActivity::class.java
        startActivity(
            Intent(this, target)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_TITLE, origin)
                .putExtra(ViewerActivity.EXTRA_FOCUS_LINE, todo.line + 1),
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_CATEGORY = "category"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
    }
}
