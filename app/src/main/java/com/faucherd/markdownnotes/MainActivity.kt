package com.faucherd.markdownnotes

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faucherd.markdownnotes.databinding.ActivityMainBinding
import java.io.File
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var vault: File? = null

    private val accessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onAccessPossiblyGranted()
        }

    private val filesAdapter = FileAdapter(
        onFile = { entry -> openFile(entry) },
        onTodo = { _, _ -> },
        onCategory = { key ->
            startActivity(
                Intent(this, CategoryActivity::class.java)
                    .putExtra(CategoryActivity.EXTRA_CATEGORY, key),
            )
        },
    )

    private val searchAdapter = SearchAdapter { hit ->
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_PATH, hit.entry.file.absolutePath)
                .putExtra(ViewerActivity.EXTRA_TITLE, hit.entry.title)
                .putExtra(ViewerActivity.EXTRA_FOCUS_LINE, hit.lineNumber),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        useLightStatusBarIcons()

        setSupportActionBar(binding.toolbar)

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = filesAdapter
        binding.searchList.layoutManager = LinearLayoutManager(this)
        binding.searchList.adapter = searchAdapter

        binding.searchInput.addTextChangedListener(
            SimpleTextWatcher { query -> runSearch(query) },
        )
        binding.retryButton.setOnClickListener { onAccessPossiblyGranted() }

        onAccessPossiblyGranted()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings, or after an edit elsewhere in the app.
        if (::binding.isInitialized) onAccessPossiblyGranted()
    }

    private fun onAccessPossiblyGranted() {
        if (!VaultAccess.hasAccess()) {
            showPermissionRequired()
            return
        }
        val found = VaultLocator.locate()
        if (found == null) {
            showVaultNotFound()
            return
        }
        vault = found
        showContent()
        refreshFileList()
    }

    private fun showPermissionRequired() {
        binding.statusTitle.text = getString(R.string.status_needs_access)
        binding.statusBody.text = getString(
            R.string.status_needs_access_body,
            VaultAccess.externalRoot()?.absolutePath ?: "",
        )
        binding.permissionButton.setOnClickListener {
            runCatching { accessLauncher.launch(VaultAccess.requestIntent(this)) }
                .onFailure { startActivity(VaultAccess.requestIntent(this)) }
        }
        showStatus()
    }

    private fun showVaultNotFound() {
        binding.statusTitle.text = getString(R.string.status_no_vault)
        binding.statusBody.text = getString(
            R.string.status_no_vault_body,
            VaultAccess.sharedDocuments()?.absolutePath ?: "",
        )
        binding.permissionButton.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        showStatus()
    }

    private fun showContent() {
        binding.statusGroup.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE
        binding.subtitle.text = getString(R.string.subtitle_files, vault?.absolutePath ?: "")
    }

    private fun showStatus() {
        binding.contentGroup.visibility = View.GONE
        binding.statusGroup.visibility = View.VISIBLE
    }

    private fun sectionRows() = SectionRows(
        SectionRows.Labels(
            journals = getString(R.string.section_journals),
            pages = getString(R.string.section_pages),
            todos = getString(R.string.section_todos),
            entries = { n -> resources.getQuantityString(R.plurals.row_count, n, n) },
            openTodos = { n -> getString(R.string.todo_count_open, n) },
            today = getString(R.string.entry_today),
            todaySubtitle = { date -> VaultNames.formatJournalTitle(date) },
        ),
    )

    /**
     * The top level is only the three categories. Each count is the number of
     * rows its page will show, so the menu is a preview of what is behind it
     * rather than a second, differently-scoped tally.
     */
    private fun refreshFileList() {
        val v = vault ?: return
        val builder = sectionRows()
        val today = builder.todayIfUnwritten(v, LocalDate.now())
        filesAdapter.submit(builder.categories(v, today, VaultTodos.collect(v)))
        binding.subtitle.text = getString(R.string.subtitle_files, v.absolutePath)
    }


    private fun openFile(entry: VaultFile) {
        // Checked at tap time rather than trusting the row: a virtual row may
        // have been written by another writer since the list loaded, and a real
        // one may have been deleted by Resilio.
        val target = if (entry.file.isFile) ViewerActivity::class.java else EditorActivity::class.java
        startActivity(
            Intent(this, target)
                .putExtra(EXTRA_PATH_KEY, entry.file.absolutePath)
                .putExtra(EXTRA_TITLE_KEY, entry.title),
        )
    }


    // ---- new page ------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_new_page) {
            promptNewPage()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun promptNewPage() {
        val v = vault ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.new_page_hint)
            setSingleLine()
        }
        // EditText.setError() draws its message in a PopupWindow that only shows
        // on a focus change and never reaches an accessibility dump, so a
        // rejected title could be tapped repeatedly with nothing on screen to
        // explain why. An explicit label is visible, and testable.
        val errorLabel = TextView(this).apply {
            visibility = View.GONE
            setTextColor(ContextCompat.getColor(context, R.color.error))
            textSize = 13f
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
            addView(errorLabel)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_page_title)
            .setView(panel)
            // A null listener so the dialog stays open on invalid input; the
            // default handler dismisses on any tap, which would throw away the
            // half-typed title the user needs to correct.
            .setPositiveButton(R.string.action_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun reject(message: String) {
            errorLabel.text = message
            errorLabel.visibility = View.VISIBLE
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = input.text.toString().trim()
                when {
                    title.isEmpty() -> reject(getString(R.string.new_page_error_empty))
                    !VaultNames.isSafePageTitle(title) ->
                        reject(getString(R.string.new_page_error_unsafe))
                    else -> {
                        dialog.dismiss()
                        openFile(
                            VaultFile(
                                file = File(
                                    v,
                                    "pages/${VaultNames.pageTitleToFilename(title)}",
                                ),
                                kind = "pages",
                                title = title,
                            ),
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    /** Debounced so typing doesn't re-scan the vault on every keystroke. */
    private var searchRunnable: Runnable? = null

    private fun runSearch(query: String) {
        searchRunnable?.let(binding.searchInput::removeCallbacks)
        val v = vault
        if (v == null || query.isBlank()) {
            searchAdapter.submit(emptyList())
            showFileSection()
            return
        }
        val task = Runnable {
            val hits = VaultSearch.search(v, query)
            searchAdapter.submit(hits)
            binding.searchList.visibility = View.VISIBLE
            binding.searchEmpty.visibility =
                if (hits.isEmpty()) View.VISIBLE else View.GONE
            binding.searchEmpty.text = getString(R.string.search_none, query.trim())
            // A "0 lines" header above "No matches" is just noise.
            binding.searchHeader.visibility =
                if (hits.isEmpty()) View.GONE else View.VISIBLE
            binding.searchCount.text = resources.getQuantityString(
                R.plurals.hit_count, hits.size, hits.size,
            )
            // Results replace the file list rather than sharing the screen with
            // it — two scrolling lists of the same vault reads as clutter.
            binding.fileSection.visibility = View.GONE
        }
        searchRunnable = task
        binding.searchInput.postDelayed(task, 220)
    }

    private fun showFileSection() {
        binding.searchList.visibility = View.GONE
        binding.searchEmpty.visibility = View.GONE
        binding.searchHeader.visibility = View.GONE
        binding.fileSection.visibility = View.VISIBLE
    }
}

private const val EXTRA_PATH_KEY = "path"
private const val EXTRA_TITLE_KEY = "title"

/** Minimal watcher so MainActivity doesn't need a lifecycle library for one call. */
class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) = onChanged(s?.toString() ?: "")
}

/**
 * The main list is one flat adapter over four row shapes. Sections and tag
 * groups are rows rather than nested RecyclerViews on purpose: the whole vault
 * is one scroll, and a nested list would need its own scroll position, its own
 * adapter and a header that doesn't stick — for a list this size, rows are
 * simpler and behave the way the user expects when they flick the screen.
 */
sealed interface ListRow {
    /** A top-level entry. Only ever on the menu, never on a category page. */
    data class Category(val key: String, val title: String, val subtitle: String) : ListRow
    data class Section(val title: String, val count: Int) : ListRow
    data class FileRow(val entry: VaultFile) : ListRow
    data class TodoGroup(val title: String, val count: Int) : ListRow
    data class TodoRow(val todo: TodoItem, val origin: String) : ListRow
}

class FileAdapter(
    private val onFile: (VaultFile) -> Unit,
    private val onTodo: (TodoItem, String) -> Unit,
    private val onCategory: (String) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ListRow> = emptyList()

    fun submit(next: List<ListRow>) {
        items = next
        notifyDataSetChanged()
    }

    class SectionVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionTitle)
    }

    class GroupVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.groupTitle)
    }

    class FileVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)
    }

    class TodoVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.todoText)
        val origin: TextView = view.findViewById(R.id.todoOrigin)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListRow.Category -> TYPE_CATEGORY
        is ListRow.Section -> TYPE_SECTION
        is ListRow.FileRow -> TYPE_FILE
        is ListRow.TodoGroup -> TYPE_GROUP
        is ListRow.TodoRow -> TYPE_TODO
    }

    override fun onCreateViewHolder(
        parent: android.view.ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionVH(inflater.inflate(R.layout.item_section, parent, false))
            TYPE_GROUP -> GroupVH(inflater.inflate(R.layout.item_todo_group, parent, false))
            TYPE_TODO -> TodoVH(inflater.inflate(R.layout.item_todo, parent, false))
            else -> FileVH(inflater.inflate(android.R.layout.simple_list_item_2, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is ListRow.Category -> {
                val h = holder as FileVH
                h.title.text = row.title
                h.subtitle.text = row.subtitle
                h.itemView.setOnClickListener { onCategory(row.key) }
            }
            is ListRow.Section -> (holder as SectionVH).title.text = row.title
            is ListRow.TodoGroup -> (holder as GroupVH).title.text =
                if (row.title == VaultTodos.UNCATEGORIZED) row.title else "#${row.title}"
            is ListRow.TodoRow -> {
                val h = holder as TodoVH
                h.text.text = "${row.todo.marker}  ${row.todo.text}"
                h.origin.text = row.origin
                h.itemView.setOnClickListener { onTodo(row.todo, row.origin) }
            }
            is ListRow.FileRow -> {
                val h = holder as FileVH
                val item = row.entry
                h.title.text = if (item.isConflict) "${item.title}  (conflict copy)" else item.title
                h.subtitle.text = item.subtitle
                h.itemView.setOnClickListener { onFile(item) }
            }
        }
    }

    private companion object {
        const val TYPE_FILE = 0
        const val TYPE_CATEGORY = 4
        const val TYPE_SECTION = 1
        const val TYPE_GROUP = 2
        const val TYPE_TODO = 3
    }
}

class SearchAdapter(
    private val onClick: (SearchHit) -> Unit,
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private var items: List<SearchHit> = emptyList()

    fun submit(next: List<SearchHit>) {
        items = next
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val body: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = VH(
        android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false),
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hit = items[position]
        holder.title.text = "${hit.entry.title}  ·  line ${hit.lineNumber}"
        holder.body.text = hit.line
        holder.itemView.setOnClickListener { onClick(hit) }
    }
}
