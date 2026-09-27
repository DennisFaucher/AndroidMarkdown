package com.faucherd.markdownnotes

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.faucherd.markdownnotes.databinding.ActivityEditorBinding
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * Raw-text editor for one vault file — Phase 2.
 *
 * Two constraints shaped this, both learned the hard way elsewhere:
 *
 *  - **No Tab key on a phone.** Promote/demote have to be buttons, and tapping
 *    one blurs the EditText. The selection is therefore captured before the tap
 *    and re-applied afterwards, or the caret jumps to the end and demote lands
 *    on the wrong line. The caret is tracked from onSelectionChanged rather than
 *    read on click, because by click time focus is already gone.
 *
 *  - **Never corrupt a note.** Saves are temp-file + rename (SafeWriter), and a
 *    file that changed on disk since it was opened is never silently
 *    overwritten — the user picks, and "keep mine" preserves the other version
 *    as a conflict sibling. This matters because the phone is a *third* writer
 *    alongside the Mac, the Linux server, and Resilio itself.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var file: File

    /**
     * Hash of the content as last known to be on disk, or [MISSING] if the file
     * did not exist when it was opened. A real hash is 40 hex characters, so the
     * empty string is an unambiguous marker — the same trick the server uses
     * when it hands back `version: ""` for an unsaved document.
     */
    private var diskHash: String = MISSING

    /**
     * Exactly what was loaded, so "has the user changed anything" is a string
     * comparison. Deriving it from [diskHash] instead would report a freshly
     * opened empty note as dirty, since hashOf("") is a real hash and does not
     * equal [MISSING].
     */
    private var loadedText: String = ""
    private var loadedOk = false

    private val undoStack = ArrayDeque<String>()
    private var suppressUndo = false

    /**
     * True while one of our own transforms is mutating the buffer. Those push an
     * undo entry explicitly *before* mutating; without this flag the debounced
     * typing-push would also record the post-transform state, and the first Undo
     * would pop that no-op entry instead of the transform.
     */
    private var transforming = false

    /** Set while a run of keystrokes is being coalesced into one undo step. */
    private var burstArmed = false
    private var pendingPre: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pushPending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyEditorInsets()
        useLightStatusBarIcons()

        val path = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Note"
        file = File(path ?: "")

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = attemptLeave()
            },
        )

        val snap = SafeWriter.snapshot(file)
        if (snap == null && file.exists()) {
            // Present but unreadable — a permissions problem, not a new note.
            Toast.makeText(this, R.string.editor_unreadable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // A file that does not exist yet opens as an empty buffer, and the first
        // save creates it. The "Today" row and new-page flow both land here.
        loadedOk = true
        diskHash = snap?.hash ?: MISSING
        loadedText = snap?.text ?: ""
        binding.body.setText(loadedText)
        binding.body.setSelection(0)

        wireButtons()
        binding.body.onCaretMoved = { start, _ -> lastSelection = start }
        watchText()
    }

    // ---- selection tracking -------------------------------------------

    /**
     * The caret is recorded here rather than read inside a click handler:
     * a button tap blurs the EditText first, and after blur the selection is
     * no longer the one the user was working with. This is the same class of bug
     * as the web app's IndentButtons losing `focusedBlock` on toolbar clicks.
     */
    private fun caret(): Int = lastSelection.coerceIn(0, binding.body.text?.length ?: 0)

    private var lastSelection = 0

    private fun watchText() {
        binding.body.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                // Capture the state at the START of a typing burst. A burst is a
                // run of changes coalesced into one undo step, so only the first
                // one may set this.
                if (!transforming && !burstArmed) pendingPre = s?.toString()
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // suppressUndo covers undo()/load-theirs setText calls: those must
                // not arm a burst, or the pre-undo text gets pushed back onto the
                // stack and the *next* Undo walks forward into it.
                if (!loadedOk || transforming || suppressUndo) return
                autoContinueBullet()
                if (transforming) return
                if (burstArmed) return
                burstArmed = true
                pushPending = Runnable {
                    pendingPre?.let { pushState(it) }
                    pendingPre = null
                    burstArmed = false
                    pushPending = null
                }
                // Coalesce a burst of typing into a single undo step.
                handler.postDelayed(pushPending!!, 600)
            }
        })
    }

    // ---- undo ---------------------------------------------------------

    /**
     * An undo entry is always the state to *return to*, so what gets recorded is
     * the text from before the change. Recording the post-change state instead
     * makes the first Undo pop an entry identical to what's on screen, which
     * looks like Undo is simply broken.
     */
    private fun pushState(snapshot: String) {
        if (undoStack.lastOrNull() == snapshot) return
        undoStack.addLast(snapshot)
        while (undoStack.size > 60) undoStack.removeFirst()
    }

    /** Drop any in-flight typing burst, since the caller records its own state. */
    private fun cancelPendingBurst() {
        pushPending?.let { handler.removeCallbacks(it) }
        pushPending = null
        pendingPre = null
        burstArmed = false
    }

    private fun pushCurrent() {
        cancelPendingBurst()
        pushState(binding.body.text?.toString() ?: return)
    }

    private fun undo() {
        cancelPendingBurst()
        val previous = undoStack.removeLastOrNull() ?: return
        suppressUndo = true
        binding.body.setText(previous)
        binding.body.setSelection(previous.length)
        suppressUndo = false
        lastSelection = previous.length
    }

    // ---- transforms ---------------------------------------------------

    private fun apply(op: (String, Int, Int) -> LineOps.Result) {
        val editable = binding.body.text ?: return
        val text = editable.toString()
        val s = caret()
        val result = op(text, s, s)
        if (result.spans.isEmpty()) return

        pushCurrent()
        transforming = true
        try {
            // Applied to the Editable in place rather than via setText, so the
            // buffer's own undo/redo metadata survives.
            val sel = LineOps.applySpans(result) { s, e, t -> editable.replace(s, e, t) }
                .coerceIn(0, editable.length)
            binding.body.setSelection(sel, sel)
            lastSelection = sel
        } finally {
            transforming = false
        }
    }

    private fun autoContinueBullet() {
        if (suppressUndo || transforming) return
        val editable = binding.body.text ?: return
        val sel = binding.body.selectionStart
        if (sel <= 0) return
        // Fires whenever the caret sits just after a newline, so pressing Enter
        // mid-document continues the bullet. It must NOT be limited to the end
        // of the buffer — that is the normal place to write, and gating on it
        // made auto-continue silently never happen.
        if (editable[sel - 1] != '\n') return
        val lineStart = editable.lastIndexOf('\n', sel - 2).let { if (it < 0) 0 else it + 1 }
        val prevLine = editable.substring(lineStart, sel - 1)
        val cont = LineOps.continuationFor(prevLine) ?: return

        // Deliberately no undo push here. The Enter keystroke already armed a
        // burst holding the text from *before* the newline, and that is the
        // state Undo should return to — so one Undo reverts the newline and its
        // continuation together. Pushing the intermediate state instead would
        // leave a stray empty line behind.
        transforming = true
        try {
            editable.insert(sel, cont)
            val caretPos = sel + cont.length
            binding.body.setSelection(caretPos, caretPos)
            lastSelection = caretPos
        } finally {
            transforming = false
        }
    }

    private fun wireButtons() {
        binding.promote.setOnClickListener { apply(LineOps::outdent) }
        binding.demote.setOnClickListener { apply(LineOps::indent) }
        binding.toggleTodo.setOnClickListener { apply(LineOps::toggleTodo) }
        binding.undo.setOnClickListener { undo() }
        binding.save.setOnClickListener { save() }

        // Buttons must not steal focus, or the soft keyboard collapses the
        // moment the user reaches for demote.
        for (id in listOf(
            R.id.promote, R.id.demote, R.id.toggleTodo, R.id.undo, R.id.save,
        )) {
            findViewById<View>(id).isFocusable = false
        }
    }

    // ---- saving -------------------------------------------------------

    private fun isDirty(): Boolean =
        loadedOk && (binding.body.text?.toString() ?: "") != loadedText

    private fun save() {
        if (!loadedOk) return
        val mine = binding.body.text?.toString() ?: return
        val current = SafeWriter.snapshot(file)

        if (current == null && diskHash == MISSING) {
            // Opened as a new note and nothing has appeared on disk since — an
            // ordinary create. A blank note stays virtual rather than becoming a
            // 0-byte file: an empty file in the vault is noise to every other
            // writer, and the web app shows unwritten days without one.
            if (mine.isBlank()) {
                toast(getString(R.string.save_nothing))
                return
            }
            writeMine(mine, null)
            return
        }

        if (current == null) {
            // Deleted underneath us (Resilio conflict resolution, manual delete).
            AlertDialog.Builder(this)
                .setTitle(R.string.conflict_title)
                .setMessage(R.string.conflict_deleted)
                .setPositiveButton(R.string.conflict_keep_mine) { _, _ ->
                    writeMine(mine, null)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (current.hash == diskHash) {
            writeMine(mine, null)
            return
        }

        // The file moved under us. Never overwrite silently.
        //
        // No setMessage here: AlertController gives the message and the item
        // list the same slot, so setting both renders the message and silently
        // drops the choices — leaving a data-loss dialog with no way to act.
        // The labels carry the explanation instead.
        AlertDialog.Builder(this)
            .setTitle(R.string.conflict_title)
            .setItems(
                arrayOf(
                    getString(R.string.conflict_keep_mine),
                    getString(R.string.conflict_reload_theirs),
                    getString(R.string.cancel),
                ),
            ) { _, which ->
                when (which) {
                    0 -> writeMine(mine, current.text)
                    1 -> {
                        suppressUndo = true
                        binding.body.setText(current.text)
                        suppressUndo = false
                        diskHash = current.hash
                        loadedText = current.text
                        lastSelection = 0
                        undoStack.clear()
                        toast(getString(R.string.conflict_reloaded))
                    }
                }
            }
            .show()
    }

    /**
     * Write [mine] to the canonical path. When [theirs] is non-null the current
     * on-disk version is first moved to a `.conflict-<ISO>.md` sibling, so both
     * versions survive. Nothing is discarded either way.
     */
    private fun writeMine(mine: String, theirs: String?) {
        var preserved: File? = null
        if (theirs != null) {
            preserved = SafeWriter.preserveAsConflictCopy(file)
            if (preserved == null) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.save_failed)
                    .setMessage(getString(R.string.conflict_preserve_failed))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
        }
        try {
            SafeWriter.writeAtomic(file, mine)
        } catch (e: Exception) {
            // Put the preserved copy back so the original path isn't left empty.
            preserved?.let { runCatching { it.renameTo(file) } }
            AlertDialog.Builder(this)
                .setTitle(R.string.save_failed)
                .setMessage(e.message ?: getString(R.string.save_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        diskHash = SafeWriter.hashOf(mine)
        loadedText = mine
        undoStack.clear()
        toast(
            if (preserved != null)
                getString(R.string.saved_with_copy, preserved.name)
            else getString(R.string.saved),
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- leaving ------------------------------------------------------

    private fun attemptLeave() {
        if (!loadedOk || !isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.editor_save) { _, _ -> save() }
            .setNeutralButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        attemptLeave()
        return true
    }

    override fun onDestroy() {
        pushPending?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"

        /** Sentinel for "no such file", never equal to a real SHA-1 hex digest. */
        private const val MISSING = ""
    }
}
