package org.duzgun.eksiengelplus.ops.engine

import org.duzgun.eksiengelplus.eksi.client.RelationResult

/**
 * Decides whether a failed mutation is worth attempting again.
 *
 * The extension retries any failure once after a hard-coded 62 second stall
 * (background.js:652), which is wrong twice: a permanently-failing id costs a
 * full minute each time, and a non-idempotent mutation gets re-fired on an
 * ambiguous error.
 *
 * Here only RateLimited is retryable. Everything else is terminal by
 * construction, because RelationResult names the outcomes separately instead of
 * collapsing them into a boolean.
 */
class RetryPolicy(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    companion object {
        /** programController.js:615 uses three. */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }

    sealed interface Decision {
        /** Wait this long, then attempt again. */
        data class RetryAfter(val seconds: Int) : Decision
        data object Done : Decision
        data object GiveUp : Decision
    }

    fun decide(result: RelationResult, attempt: Int): Decision = when (result) {
        is RelationResult.Success,
        is RelationResult.AlreadyInState,
        -> Decision.Done

        is RelationResult.RateLimited ->
            if (attempt < maxAttempts) Decision.RetryAfter(result.retryAfterSeconds)
            else Decision.GiveUp

        // Retrying cannot help and actively hurts:
        //  - SelfTarget is a permanent property of the pair.
        //  - SessionExpired needs a human, because /giris is behind Turnstile.
        //  - Failed carries a code we do not understand; guessing is how you
        //    re-fire a mutation that actually succeeded.
        is RelationResult.SelfTarget,
        is RelationResult.SessionExpired,
        is RelationResult.Failed,
        -> Decision.GiveUp
    }

    fun isTerminalForOperation(result: RelationResult): Boolean =
        result is RelationResult.SessionExpired
}
