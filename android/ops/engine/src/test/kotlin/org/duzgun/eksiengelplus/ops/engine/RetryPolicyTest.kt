package org.duzgun.eksiengelplus.ops.engine

import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.eksi.client.RelationResult
import org.junit.Test

class RetryPolicyTest {

    private val policy = RetryPolicy(maxAttempts = 3)

    @Test fun `success is done, not retried`() {
        assertThat(policy.decide(RelationResult.Success, 1)).isEqualTo(RetryPolicy.Decision.Done)
        assertThat(policy.decide(RelationResult.AlreadyInState, 1)).isEqualTo(RetryPolicy.Decision.Done)
    }

    @Test fun `rate limiting is retried with the server's delay`() {
        val d = policy.decide(RelationResult.RateLimited(31), 1)
        assertThat(d).isEqualTo(RetryPolicy.Decision.RetryAfter(31))
    }

    @Test fun `retries stop at the attempt limit`() {
        assertThat(policy.decide(RelationResult.RateLimited(5), 3)).isEqualTo(RetryPolicy.Decision.GiveUp)
    }

    @Test fun `self-target is never retried`() {
        // A permanent property of the pair; retrying just burns budget.
        assertThat(policy.decide(RelationResult.SelfTarget, 1)).isEqualTo(RetryPolicy.Decision.GiveUp)
    }

    @Test fun `session expiry is never retried and ends the operation`() {
        // /giris is behind Turnstile -- no automated attempt can ever succeed.
        assertThat(policy.decide(RelationResult.SessionExpired, 1)).isEqualTo(RetryPolicy.Decision.GiveUp)
        assertThat(policy.isTerminalForOperation(RelationResult.SessionExpired)).isTrue()
    }

    @Test fun `an unknown code is not retried`() {
        // background.js:652 retries any failure once, which re-fires a
        // non-idempotent mutation on an ambiguous error. Guessing is the bug.
        val unknown = RelationResult.Failed(httpCode = 200, code = 7, body = "7")
        assertThat(policy.decide(unknown, 1)).isEqualTo(RetryPolicy.Decision.GiveUp)
        assertThat(policy.isTerminalForOperation(unknown)).isFalse()
    }
}
