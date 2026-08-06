package org.duzgun.eksiengelplus.ops.engine

/**
 * Ports the machine in resumableOperation.js:10, plus three states the extension
 * has no concept of.
 *
 * PAUSED_AUTH exists because /giris is behind Cloudflare Turnstile: a lost
 * session cannot be recovered without a human, so retrying only burns budget.
 * PAUSED_BUDGET exists because Android 15 caps a dataSync foreground service at
 * roughly six hours per rolling twenty-four while a full run needs about
 * fourteen. PAUSED_NETWORK keeps connectivity loss from being mistaken for
 * either.
 */
enum class OperationState {
    IDLE,
    RUNNING,
    PAUSING,
    PAUSED,
    PAUSED_AUTH,
    PAUSED_BUDGET,
    PAUSED_NETWORK,
    STOPPING,
    STOPPED,
    COMPLETED,
    /** Found RUNNING at startup with no live worker -- the process died. */
    INTERRUPTED,
    ;

    val isPaused: Boolean
        get() = this in setOf(PAUSED, PAUSED_AUTH, PAUSED_BUDGET, PAUSED_NETWORK)

    /** Terminal states never run again. STOPPED is deliberate; COMPLETED is success. */
    val isTerminal: Boolean get() = this == STOPPED || this == COMPLETED

    val isResumable: Boolean get() = isPaused || this == INTERRUPTED
}

sealed interface OperationEvent {
    data object Start : OperationEvent
    data object RequestPause : OperationEvent
    data object PauseAcknowledged : OperationEvent
    /** The 30s acknowledgement window elapsed; force the pause and re-verify on resume. */
    data object PauseTimedOut : OperationEvent
    data object RequestStop : OperationEvent
    data object StopAcknowledged : OperationEvent
    data object Resume : OperationEvent
    data object SessionLost : OperationEvent
    data object BudgetExhausted : OperationEvent
    data object NetworkLost : OperationEvent
    data object Finished : OperationEvent
    data object FoundStale : OperationEvent
}

data class Transition(val state: OperationState, val checkpointDirty: Boolean = false)

/**
 * Pure and IO-free, so every transition is unit-testable without a device.
 *
 * Illegal transitions return null rather than being silently ignored: a state
 * machine that quietly absorbs nonsense hides the bug that produced it.
 */
object OperationStateMachine {

    fun next(current: OperationState, event: OperationEvent): Transition? = when (current) {
        OperationState.IDLE -> when (event) {
            OperationEvent.Start -> Transition(OperationState.RUNNING)
            else -> null
        }

        OperationState.RUNNING -> when (event) {
            OperationEvent.RequestPause -> Transition(OperationState.PAUSING)
            OperationEvent.RequestStop -> Transition(OperationState.STOPPING)
            OperationEvent.SessionLost -> Transition(OperationState.PAUSED_AUTH)
            OperationEvent.BudgetExhausted -> Transition(OperationState.PAUSED_BUDGET)
            OperationEvent.NetworkLost -> Transition(OperationState.PAUSED_NETWORK)
            OperationEvent.Finished -> Transition(OperationState.COMPLETED)
            OperationEvent.FoundStale -> Transition(OperationState.INTERRUPTED)
            else -> null
        }

        OperationState.PAUSING -> when (event) {
            OperationEvent.PauseAcknowledged -> Transition(OperationState.PAUSED)
            // Forced after the timeout: the last unit may or may not have landed,
            // so resume has to re-verify rather than trust the cursor.
            OperationEvent.PauseTimedOut -> Transition(OperationState.PAUSED, checkpointDirty = true)
            OperationEvent.RequestStop -> Transition(OperationState.STOPPING)
            OperationEvent.SessionLost -> Transition(OperationState.PAUSED_AUTH)
            else -> null
        }

        OperationState.STOPPING -> when (event) {
            OperationEvent.StopAcknowledged -> Transition(OperationState.STOPPED)
            else -> null
        }

        OperationState.PAUSED,
        OperationState.PAUSED_AUTH,
        OperationState.PAUSED_BUDGET,
        OperationState.PAUSED_NETWORK,
        OperationState.INTERRUPTED,
        -> when (event) {
            OperationEvent.Resume -> Transition(OperationState.RUNNING)
            OperationEvent.RequestStop -> Transition(OperationState.STOPPED)
            else -> null
        }

        // Terminal. Nothing reopens them; a new run is a new operation.
        OperationState.STOPPED, OperationState.COMPLETED -> null
    }
}
