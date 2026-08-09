package org.duzgun.eksiengelplus.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps content out from under the status bar and the navigation bar.
 *
 * `android:fitsSystemWindows="true"` used to do this and no longer finishes the
 * job. From targetSdk 35 the framework draws every window edge to edge, and
 * `android:windowOptOutEdgeToEdgeEnforcement` -- the documented escape hatch --
 * is ignored at targetSdk 36, which this app is. In practice the status bar came
 * out right and the navigation bar sat on top of the last row of every screen:
 * the browser's page, the bottom of the settings list, the buttons under the
 * author list.
 *
 * Applied to `android.R.id.content` rather than to each layout's root, so a
 * screen gets it by calling this once and cannot get it wrong in XML. Insets are
 * returned unconsumed: a child that wants to know where the bars are -- a
 * bottom sheet, a snackbar -- still finds out.
 *
 * The IME is included with the system bars. Padding for the keyboard and padding
 * for the navigation bar are the same padding, and taking the larger of the two
 * is what `Type.systemBars() or Type.ime()` already computes.
 */
fun Activity.fitContentInsideSystemBars() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
        )
        view.updatePadding(
            top = bars.top,
            bottom = bars.bottom,
            left = bars.left,
            right = bars.right,
        )
        insets
    }
    // The first pass can land before the listener is attached -- a cold start
    // reaching onCreate after the window already has its insets. Without this
    // the padding appears only on the next relayout, which on the browser is
    // whenever the user first scrolls.
    ViewCompat.requestApplyInsets(content)
}
