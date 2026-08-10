package org.duzgun.eksiengelplus.ui

import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Wires the swipe-down gesture on a screen's scrolling content.
 *
 * Every main screen already renders from a live flow, so the gesture is not what
 * makes the data arrive -- it is how the user asks for the fetch behind it. What
 * differs per screen is only [onRefresh]; the tint, and the fact that the
 * spinner is never left spinning by the handler itself, should not.
 *
 * The spinner is *not* stopped here. Each screen turns it off from the state it
 * actually observes -- a sync that finished, a reconcile that returned -- because
 * a spinner that stops when the handler returns says the work is done when it has
 * only been asked for.
 */
fun SwipeRefreshLayout.onPullToRefresh(onRefresh: () -> Unit) {
    setColorSchemeColors(ContextCompat.getColor(context, R.color.eksi_refresh_spinner))
    setOnRefreshListener { onRefresh() }
}
