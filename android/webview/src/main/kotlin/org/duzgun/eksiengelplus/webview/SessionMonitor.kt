package org.duzgun.eksiengelplus.webview

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient

sealed interface SessionState {
    data object Unknown : SessionState
    data class LoggedIn(val nick: String) : SessionState
    data object LoggedOut : SessionState
}

/**
 * Observable login state.
 *
 * Authoritative check is the homepage avatar, not a cookie: a cookie can be
 * present and expired, so it is usable as a fast negative and never as a
 * positive. Confirmed on device -- the avatar selector is the only reliable
 * signal.
 *
 * Matters beyond the UI: an operation parked in PAUSED_AUTH cannot resume until
 * something notices a session exists again, and nothing else is watching.
 */
@Singleton
class SessionMonitor @Inject constructor(
    private val scrape: ScrapeClient,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var lastProbeAt = 0L

    /**
     * Paths whose load means the session may have changed.
     *
     * While logged out, any navigation qualifies. Ekşi does not reliably land on
     * one of the named paths after a successful login, so keying only off them
     * left the bar reading "giriş yapılmadı" for the rest of the session -- until
     * the app was restarted and the startup probe found the session that had been
     * there all along.
     *
     * The extra probes are bounded by refresh()'s own interval, and they stop
     * entirely once a session is found, so a logged-in user pays nothing.
     */
    fun shouldReprobe(url: String?): Boolean = shouldReprobe(url, _state.value)

    /** Split out so the rule is testable without reaching into the monitor's state. */
    internal fun shouldReprobe(url: String?, state: SessionState): Boolean {
        val path = url?.substringAfter("://")?.substringAfter('/')?.substringBefore('?') ?: return false
        if (state !is SessionState.LoggedIn) return true
        return path.isEmpty() || path.startsWith("giris") || path.startsWith("cikis")
    }

    suspend fun refresh(now: Long = System.currentTimeMillis(), minIntervalMs: Long = 60_000) {
        // A minute is the right spacing for confirming a session still exists, and
        // far too long for noticing one appear: a fresh login would sit unnoticed
        // while the user wondered why the bar still said logged out.
        val interval = if (_state.value is SessionState.LoggedIn) minIntervalMs else LOGGED_OUT_INTERVAL_MS
        if (now - lastProbeAt < interval && _state.value !is SessionState.Unknown) return
        lastProbeAt = now
        val nick = runCatching { scrape.ownNick() }.getOrNull()
        _state.value = if (nick.isNullOrBlank()) SessionState.LoggedOut else SessionState.LoggedIn(nick)
    }

    /** Forces a probe regardless of the interval, e.g. right after a login page. */
    suspend fun refreshNow() {
        lastProbeAt = 0
        refresh()
    }

    private companion object {
        /** Short enough that a login is noticed on the next page, not the next minute. */
        const val LOGGED_OUT_INTERVAL_MS = 3_000L
    }
}
