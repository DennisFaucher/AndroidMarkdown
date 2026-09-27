package com.faucherd.markdownnotes

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * An EditText that reports caret movement.
 *
 * The editor's promote/demote buttons need to know where the caret was, but a
 * button tap blurs the field first, so reading the selection inside onClick
 * gives the wrong answer. Tracking it here is the only reliable point — and it
 * also fires when the user merely *taps* to reposition the caret, which
 * changes no text and so triggers no TextWatcher at all.
 */
class CaretAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onCaretMoved: ((start: Int, end: Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCaretMoved?.invoke(selStart, selEnd)
    }
}
