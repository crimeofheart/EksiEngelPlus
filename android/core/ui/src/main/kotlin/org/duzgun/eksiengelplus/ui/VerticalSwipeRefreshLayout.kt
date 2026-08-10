package org.duzgun.eksiengelplus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/**
 * A SwipeRefreshLayout that only claims a drag which is actually vertical.
 *
 * The stock one decides on Y movement alone: it never compares the drag against
 * how far it has travelled sideways, so a horizontal swipe with any downward
 * drift is taken as a pull-to-refresh the moment the Y delta clears touch slop.
 * On the browser that is the tab swipe, and losing it mid-gesture is worse than
 * merely not working -- interception delivers ACTION_CANCEL to the child, the
 * page's own handler is left holding a half-finished drag, and the surface stays
 * translated wherever the finger happened to be.
 *
 * `requestDisallowInterceptTouchEvent` is not the fix, though the WebView does
 * call it when a page's touch handler consumes the gesture: SwipeRefreshLayout
 * deliberately ignores that request for any child with nested scrolling
 * disabled, which a plain WebView is. The child cannot defend itself here, so
 * the decision has to be made on this side.
 *
 * Once a gesture is judged horizontal it stays that way until the finger lifts.
 * Re-deciding per event would hand the drag over halfway through, which is the
 * behaviour this exists to prevent.
 */
class VerticalSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f

    /** Set for the rest of the gesture once it is judged horizontal. */
    private var horizontal = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                horizontal = false
            }

            MotionEvent.ACTION_MOVE -> if (!horizontal) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                // Strictly greater, so a perfectly diagonal drag goes to the
                // refresh rather than to the page: a tie is not evidence of a
                // sideways intent.
                if (dx > touchSlop && dx > dy) horizontal = true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> horizontal = false
        }

        // super still sees every ACTION_DOWN, which is what resets its own drag
        // state, so refusing the moves of one gesture cannot strand the next.
        return !horizontal && super.onInterceptTouchEvent(ev)
    }
}
