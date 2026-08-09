## MODIFIED Requirements

### Requirement: Actions are paced proactively at the documented limit

Every mutation SHALL pass through a token bucket whose rate traces to a recorded
measurement against the live server, published at
`docs/android/rate-limit-measurement.md` with the observation date, the account
pair used, the request count, the elapsed time, and the verbatim 429 response.

Until that measurement exists the rate SHALL remain **12 actions per minute** and
the spec SHALL say plainly that the figure is unverified. 12/min originates in a
user-facing string at `frontend/app/assets/js/notificationHandler.js:60`, written
to explain a delay rather than to record an observation. A number that only ever
appeared in a sentence to a user is not evidence about a server.

When the measurement lands and disagrees with 12/min, the pacer configuration and
that extension string SHALL change together. Two components asserting different
limits is worse than either being wrong, because the disagreement hides which one
was measured.

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
- **THEN** they are released at no more than the configured rate

#### Scenario: Idle then burst

- **WHEN** the bucket has been idle long enough to refill
- **THEN** the accumulated tokens are spent immediately, up to the bucket's capacity

#### Scenario: Reads are not throttled to the action rate

- **WHEN** list pages are scraped
- **THEN** they are limited by the read pacer, not by the action budget

#### Scenario: The configured rate is traceable

- **WHEN** the action rate is read from configuration
- **THEN** its value is the one recorded in `docs/android/rate-limit-measurement.md`, and no other source claims a different limit

## ADDED Requirements

### Requirement: A 429 without `Retry-After` uses a measured cooldown

When a 429 carries no `Retry-After`, the pacer SHALL wait the cooldown recorded in
`docs/android/rate-limit-measurement.md`, and that document SHALL state whether
the header was observed present, absent, or inconsistent.

The extension waits a hard-coded 62 seconds in this case
(`background.js handleCooldown:639`) — a guess with a suspiciously precise look to
it. A constant is the right *shape* of answer here; the objection is that nobody
ever checked which constant. Whatever the fallback becomes, it SHALL be the one
that was observed to clear the limit, not the one that was already in the file.

The fallback SHALL be at least as long as the server's advertised delay whenever
one has ever been seen, and SHALL never be shortened to make a run finish faster.

#### Scenario: The header is absent

- **WHEN** a 429 arrives with no `Retry-After`
- **THEN** the pacer waits the measured cooldown before issuing any action

#### Scenario: The header is present

- **WHEN** `Retry-After` is present, as integer seconds or as an HTTP date
- **THEN** it takes precedence over the measured fallback, and an HTTP date is resolved against the response's own `Date` header rather than the device clock

#### Scenario: A second 429 during the cooldown

- **WHEN** a retry at the advertised time is rejected again
- **THEN** the widening behaviour applies and the discrepancy is recorded, because it means the advertised delay understates the real one
