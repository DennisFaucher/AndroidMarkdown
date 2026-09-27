package com.faucherd.markdownnotes

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge inset handling.
 *
 * From targetSdk 35 the OS enforces edge-to-edge, so a plain top-bar layout
 * draws underneath the status bar. That is not cosmetic here: it also swallows
 * taps on anything in the top strip, which is why the viewer's up arrow was
 * unclickable rather than merely misaligned.
 */
fun View.applySystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    requestApplyInsets()
}

/** The toolbar is dark, so status-bar icons need to be light. */
fun androidx.appcompat.app.AppCompatActivity.useLightStatusBarIcons() {
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * Editor variant: the action bar has to clear the *soft keyboard*, not just the
 * navigation bar. Consuming the IME inset and folding it into the root's bottom
 * padding keeps the promote/demote buttons reachable while typing — which is the
 * entire point of having them.
 */
fun View.applyEditorInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    requestApplyInsets()
}
