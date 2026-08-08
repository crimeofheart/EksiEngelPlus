package org.duzgun.eksiengelplus.ops.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the running operation is waiting on the API limit.
 *
 * The notification counts this down because the worker owns the number. The
 * İşlem durumu screen had no way to see it, so a run in a wait looked frozen
 * there while the notification a swipe away was visibly ticking -- the same
 * fact, told in two places, only one of which was moving.
 *
 * In memory, like the command bus, and for the same reason: it is worth exactly
 * as long as the worker it describes. A wait persisted across a process death
 * would count down a pause that nothing is observing.
 */
class OperationWaits {

    private val _remaining = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Milliseconds left per operation. Absent means running, not waiting. */
    val remaining: StateFlow<Map<String, Long>> = _remaining.asStateFlow()

    fun set(operationId: String, remainingMs: Long) {
        _remaining.value = _remaining.value + (operationId to remainingMs)
    }

    fun clear(operationId: String) {
        _remaining.value = _remaining.value - operationId
    }
}
