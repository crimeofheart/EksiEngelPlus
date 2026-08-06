# android-rate-limiting Specification

## Purpose
TBD - created by archiving change android-operations-engine. Update Purpose after archive.
## Requirements
### Requirement: Actions are paced proactively at the documented limit

Every mutation SHALL pass through a token bucket configured at **12 actions per
minute** — the limit surfaced to users at `notificationHandler.js:60`.

The extension does not pace at all. It fires as fast as it can and absorbs the
resulting 429s, which wastes a full cooldown every time it overshoots. Pacing
proactively costs nothing when under the limit and avoids the penalty entirely.

The rate SHALL NOT be user-configurable upward. A user cannot consent on behalf of
the server, and an app that lets people dial up request rates against a third
party reads very differently to a store reviewer.

Reads SHALL use a separate, looser pacer, since they are not what the server
limits.

#### Scenario: Sustained rate

- **WHEN** many actions are requested back to back
- **THEN** they are released at no more than 12 per minute

#### Scenario: Idle then burst

- **WHEN** the bucket has been idle long enough to refill
- **THEN** the accumulated tokens are spent immediately, up to the bucket's capacity

#### Scenario: Reads are not throttled to the action rate

- **WHEN** list pages are scraped
- **THEN** they are limited by the read pacer, not by the 12/min action budget

### Requirement: A 429 penalises every caller, not just the one that received it

On `RateLimited`, the pacer SHALL drain its tokens and refuse to issue any until
the returned delay has elapsed.

The extension gets this wrong twice over: `programController._performActionWithRetry`
(`:615`) and `background.js handleCooldown` (`:639`) each sleep only the caller
that hit the limit, and the second ignores `Retry-After` entirely in favour of a
hard-coded 62 seconds. A shared bucket makes the penalty apply once and to everyone.

#### Scenario: Penalty is global

- **WHEN** one action returns 429 with a 30 second delay
- **THEN** every subsequent action waits, not only the one that was rejected

#### Scenario: The delay comes from the server

- **WHEN** `Retry-After` is present
- **THEN** the penalty uses it rather than a fixed constant

### Requirement: The bucket survives process death

Bucket state SHALL be persisted and restored.

Without this, being killed mid-run resets the budget, and resuming immediately
fires a full bucket at a server that already counted those requests — turning a
crash into an instant 429 storm.

#### Scenario: Resume after being killed

- **WHEN** the process dies and the operation resumes
- **THEN** the bucket continues from its persisted state rather than full

### Requirement: Repeated 429s widen the interval

Each 429 SHALL multiply the refill interval, up to a ceiling; sustained success
SHALL decay it back toward the configured rate.

12/min is documented, not measured. If it is wrong, or tightened later, the client
adapts instead of hammering a limit it cannot see.

#### Scenario: Backoff

- **WHEN** several 429s occur in succession
- **THEN** the effective rate drops below the configured one

#### Scenario: Recovery

- **WHEN** a long run of successes follows
- **THEN** the interval decays back toward the configured rate but never below it

### Requirement: Retries are bounded and never blind

A failed action SHALL be retried at most three times, matching
`programController.js:615`.

Retries SHALL apply only to failures that can plausibly succeed on repeat. A
`SelfTarget`, a `SessionExpired`, or an unrecognised code SHALL NOT be retried:
`background.js:652` retries any failure at all, which re-fires non-idempotent
mutations on ambiguous errors and costs a 62-second stall per permanently-failing
id.

#### Scenario: Rate-limited action is retried after the penalty

- **WHEN** an action returns 429 and the penalty elapses
- **THEN** it is attempted again, up to the attempt limit

#### Scenario: Session expiry is not retried

- **WHEN** an action returns session-expired
- **THEN** no retry occurs and the operation pauses for authentication

#### Scenario: Unknown codes are not retried

- **WHEN** an action fails with an unrecognised numeric code
- **THEN** it is recorded as failed without retry, because the meaning is unknown

