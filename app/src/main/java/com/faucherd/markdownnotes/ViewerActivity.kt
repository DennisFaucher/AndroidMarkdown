package com.faucherd.markdownnotes

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.faucherd.markdownnotes.databinding.ActivityViewerBinding
import java.io.File

/**
 * Read-only viewer for one vault file.
 *
 * The text is re-read on every resume rather than only in onCreate. The editor
 * writes this same file underneath us, and Resilio can rewrite it at any moment,
 * so a copy rendered at launch goes stale — the symptom being that an edit
 * appears only after leaving for the main screen and coming back. The reload is
 * skipped when the bytes are unchanged, so returning here normally costs one
 * small file read and no view churn.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var file: File? = null
    private var focusLine = 0

    /** The plain text currently on screen, kept so highlighting can be redone
     *  without re-reading the file, and so it can be restored when find closes. */
    private var shown: String = ""

    private var find: FindInPage? = null
    private var findIndex: Int = -1
    private var findOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        useLightStatusBarIcons()

        val path = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Note"
        focusLine = intent.getIntExtra(EXTRA_FOCUS_LINE, 0)
        file = path?.let { File(it) }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Once the toolbar is passed to setSupportActionBar, the ActionBar owns
        // the navigation icon and a Toolbar.setNavigationOnClickListener is
        // silently ignored — the up arrow does nothing. Handle it here instead.
        //
        // Predictive back is on by default from targetSdk 36, and an activity
        // that registers no OnBackPressedCallback gets no back handling at all,
        // so the system gesture and the hardware key both stop working.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // Back belongs to the find bar while it is open. Leaving the
                // note instead is what a reader pressing back twice in quick
                // succession least wants to happen.
                override fun handleOnBackPressed() {
                    if (findOpen) closeFind() else finish()
                }
            },
        )

        binding.findInput.addTextChangedListener(
            SimpleTextWatcher { runFind() },
        )
        // The action key on a search IME means "next match", which is what
        // pressing Enter in a find box is universally expected to do.
        binding.findInput.setOnEditorActionListener { v, _, _ ->
            stepFind(forward = true)
            true
        }
        binding.findNext.setOnClickListener { stepFind(forward = true) }
        binding.findPrev.setOnClickListener { stepFind(forward = false) }
        binding.findClose.setOnClickListener { closeFind() }

        render(scrollToFocus = true)
    }

    override fun onResume() {
        super.onResume()
        render(scrollToFocus = false)
    }

    /**
     * Re-reads the file and updates the view only if the text actually changed,
     * so a resume that finds nothing new leaves the scroll position and the
     * rendered text exactly as they were.
     */
    private fun render(scrollToFocus: Boolean) {
        val file = file
        if (file == null || !file.isFile) {
            setBody(getString(R.string.viewer_missing))
            return
        }

        val text = try {
            file.readText()
        } catch (e: Exception) {
            getString(R.string.viewer_unreadable, e.message ?: "?")
        }
        val shown = text.ifBlank { getString(R.string.viewer_empty) }
        if (shown == binding.body.text.toString()) return

        // Hold the viewport where the reader left it. Re-applying the focus
        // scroll on resume would yank them back to the line they opened.
        //
        // The scrolling ancestor is the ScrollView, not the TextView: the
        // TextView is wrap_content inside a HorizontalScrollView, so it has no
        // vertical scroll of its own and both body.scrollY and
        // TextView.scrollToLine are no-ops. Reading keepY off the TextView left
        // the position preserved by accident rather than by intent, and the
        // focus jump did nothing at all.
        val keepY = binding.scroll.scrollY
        setBody(shown = text)
        binding.path.text = file.absolutePath
        if (scrollToFocus && focusLine > 0) {
            scrollToLine(focusLine)
        } else {
            binding.scroll.scrollTo(0, keepY)
        }
        // A reload under an open find bar invalidates every offset held, so the
        // matches have to be rebuilt against the new text.
        if (findOpen) runFind()
    }

    private fun setBody(shown: String) {
        if (this.shown == shown && binding.body.text.toString() == shown) return
        this.shown = shown
        find = null
        findIndex = -1
        binding.body.text = shown
    }

    /**
     * Scrolls [line] into view. [line] is **1-based**, which is what every
     * caller produces — SearchHit.lineNumber is `index + 1` and a to-do row
     * sends its 0-based line + 1 — while Layout is 0-based, so the conversion
     * happens here where it cannot be forgotten at a call site.
     */
    private fun scrollToLine(line: Int) {
        binding.body.post {
            val layout = binding.body.layout
            val index = line - 1
            val top = if (layout != null && index in 0 until layout.lineCount) {
                layout.getLineTop(index)
            } else {
                0
            }
            binding.scroll.scrollTo(0, top)
        }
    }

    // ---- find in page ----------------------------------------------------

    private fun openFind() {
        findOpen = true
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.setText("")
        binding.findCount.text = getString(R.string.find_zero)
        runFind()
        // requestFocus alone does not reliably raise the keyboard, and a find
        // bar with no way to type into it is a dead end on a phone.
        binding.findInput.post {
            binding.findInput.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeFind() {
        findOpen = false
        binding.findBar.visibility = View.GONE
        binding.findInput.setText("")
        find = null
        findIndex = -1
        // Drop the spans; the reader is back to plain text at the same place.
        binding.body.text = shown
    }

    /**
     * Rebuilds matches for the current query and shows the first.
     *
     * Called on every keystroke, so it re-derives from [shown] rather than from
     * whatever spannable is currently attached — accumulating spans onto an
     * already-spanned string is how a find bar ends up highlighting matches
     * from a query the reader has already deleted.
     */
    private fun runFind(stepTo: Int = 0) {
        val query = binding.findInput.text?.toString().orEmpty()
        val found = FindInPage.find(shown, query)
        find = found
        val total = found?.ranges?.size ?: 0
        if (total == 0) {
            findIndex = -1
            binding.body.text = shown
            binding.findCount.text =
                if (query.isEmpty()) getString(R.string.find_zero) else getString(R.string.find_none)
            return
        }
        findIndex = stepTo.coerceIn(0, total - 1)
        highlight()
        binding.findCount.text = getString(R.string.find_count, findIndex + 1, total)
    }

    private fun stepFind(forward: Boolean) {
        val current = find ?: return
        if (current.ranges.isEmpty()) return
        findIndex = current.step(if (findIndex < 0) 0 else findIndex, forward)
        highlight()
        binding.findCount.text =
            getString(R.string.find_count, findIndex + 1, current.ranges.size)
    }

    /**
     * Repaints the spans and brings the current match into view.
     *
     * The bulk of the matches are highlighted, up to [FindInPage.HIGHLIGHT_LIMIT]:
     * a one-letter query on a long page can match tens of thousands of times, and
     * a span each is enough to make scrolling stutter — which is the opposite
     * of what someone using find on a long page needs.
     *
     * The current match is painted outside that budget. One more span is free,
     * and losing the highlight on exactly the match the reader just navigated to
     * is the one outcome find must never produce — a plain
     * `for (i in 0 until limit)` silently drops it once the index passes the
     * limit, because the active match is then past the end of the loop.
     */
    private fun highlight() {
        val current = find ?: return
        val index = findIndex
        if (index < 0) return

        val spannable = SpannableString(shown)
        val limit = minOf(current.ranges.size, FindInPage.HIGHLIGHT_LIMIT)
        for (i in 0 until limit) {
            if (i != index) paintMatch(spannable, current.ranges[i], FOUND_FIND)
        }
        paintMatch(spannable, current.ranges[index], ACTIVE_FIND, bold = true)

        binding.body.text = spannable
        scrollToOffset(current.lineAt(current.ranges[index].first))
    }

    private fun paintMatch(
        spannable: SpannableString,
        range: IntRange,
        color: Int,
        bold: Boolean = false,
    ) {
        spannable.setSpan(
            BackgroundColorSpan(color),
            range.first,
            range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (bold) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                range.first,
                range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * Scrolls a **1-based** line into view with a little of the preceding
     * context above it, rather than jamming it against the top edge where
     * there is nothing to read the match in the context of.
     */
    private fun scrollToOffset(line: Int) {
        binding.body.post {
            val layout = binding.body.layout ?: return@post
            val index = (line - 1).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
            val top = layout.getLineTop(index)
            val lead = binding.scroll.height / 3
            binding.scroll.scrollTo(0, (top - lead).coerceAtLeast(0))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_find) {
            openFind()
            return true
        }
        if (item.itemId == R.id.action_edit) {
            val path = intent.getStringExtra(EXTRA_PATH) ?: return true
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_PATH, path)
                    .putExtra(
                        EditorActivity.EXTRA_TITLE,
                        intent.getStringExtra(EXTRA_TITLE) ?: "Note",
                    ),
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FOCUS_LINE = "focusLine"

        /** Every match. */
        private val FOUND_FIND = Color.parseColor("#66FFC107")

        /** The one being looked at right now, so it stands out from the rest. */
        private val ACTIVE_FIND = Color.parseColor("#FFFFC107")
    }
}

private fun android.widget.TextView.scrollToLine(oneBasedLine: Int) {
    val layout = layout ?: return
    val index = (oneBasedLine - 1).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
    val y = layout.getLineTop(index)
    scrollTo(0, (y - height / 3).coerceAtLeast(0))
}
