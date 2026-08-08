package org.duzgun.eksiengelplus.feature.lists

import android.app.Activity
import android.content.Intent
import com.google.android.material.snackbar.Snackbar
import org.duzgun.eksiengelplus.ops.runtime.OpsNotifier

/**
 * Shows a [UiMessage], with a way through when it offers one.
 *
 * A Snackbar rather than a Toast because a Toast cannot carry an action, and an
 * action is the whole point for a run that has just been queued: it is the only
 * thing on screen pointing at where the run went.
 *
 * Plain messages keep the short, dismissable feel of the Toast they replace.
 */
fun Activity.showMessage(message: UiMessage) {
    val root = findViewById<android.view.View>(android.R.id.content)
    val bar = Snackbar.make(
        root,
        message.text,
        if (message.showOperations) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
    )
    if (message.showOperations) {
        bar.setAction(R.string.ops_show) {
            startActivity(
                Intent(OpsNotifier.ACTION_SHOW_OPERATIONS)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
    bar.show()
}
