package com.faucherd.markdownnotes

import android.content.Intent
import android.os.Bundle
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
                override fun handleOnBackPressed() = finish()
            },
        )

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
        setBody(shown)
        binding.path.text = file.absolutePath
        if (scrollToFocus && focusLine > 0) {
            scrollToLine(focusLine)
        } else {
            binding.scroll.scrollTo(0, keepY)
        }
    }

    private fun setBody(text: String) {
        if (binding.body.text.toString() != text) binding.body.text = text
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
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
    }
}

private fun android.widget.TextView.scrollToLine(oneBasedLine: Int) {
    val layout = layout ?: return
    val index = (oneBasedLine - 1).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
    val y = layout.getLineTop(index)
    scrollTo(0, (y - height / 3).coerceAtLeast(0))
}
