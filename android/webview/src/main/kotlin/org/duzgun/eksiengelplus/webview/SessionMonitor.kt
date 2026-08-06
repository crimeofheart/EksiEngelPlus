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

    /** Paths whose load means the session may have changed. */
    fun shouldReprobe(url: String?): Boolean {
        val path = url?.substringAfter("://")?.substringAfter('/')?.substringBefore('?') ?: return false
        return path.isEmpty() || path.startsWith("giris") || path.startsWith("cikis")
    }

    suspend fun refresh(now: Long = System.currentTimeMillis(), minIntervalMs: Long = 60_000) {
        if (now - lastProbeAt < minIntervalMs && _state.value !is SessionState.Unknown) return
        lastProbeAt = now
        val nick = runCatching { scrape.ownNick() }.getOrNull()
        _state.value = if (nick.isNullOrBlank()) SessionState.LoggedOut else SessionState.LoggedIn(nick)
    }

    /** Forces a probe regardless of the interval, e.g. right after a login page. */
    suspend fun refreshNow() {
        lastProbeAt = 0
        refresh()
    }
}
