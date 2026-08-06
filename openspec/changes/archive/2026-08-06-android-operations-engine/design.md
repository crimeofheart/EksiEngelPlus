# Design — operations engine

## Context

`android-foundations` shipped parser, clients, Room and DataStore. Nothing drives
them. The gap between "can perform one mutation" and "can perform ten thousand
across days" is this change.

Three constraints, all measured rather than assumed:

- ~12 actions/minute, so 10,000 users is ~14 hours of wall clock.
- Android 15 caps a `dataSync` foreground service at ~6 hours per rolling 24.
- Sessions cannot be renewed headlessly; `/giris` is behind Turnstile.

The first two do not reconcile. That single fact shapes the whole design.

## Goals / Non-Goals

**Goals** — one pacer rather than two divergent cooldowns; an engine that never
depends on a UI surface; checkpoints that cannot corrupt state on a crash.

**Non-Goals** — UI, the WebView bridge, date filters, migrations, list refresh,
telemetry reporting. Measuring the real rate limit: 12/min is taken as given.

## Decisions

### One pacer, at 12/min

The extension has two cooldown implementations that disagree —
`_performActionWithRetry` honours `Retry-After` over three attempts;
`handleCooldown` hard-codes 62 seconds, ignores the header, and retries any
failure once. Neither paces proactively: both fire at full speed and absorb 429s.

A single token bucket at the documented 12/min replaces both. Pacing costs nothing
while under the limit and avoids the penalty entirely, and a shared bucket is what
makes a 429 penalty apply globally rather than to one unlucky caller.

Not user-configurable upward. A user cannot consent on the server's behalf, and an
app offering a "go faster against a third-party site" dial reads badly in review.

### AIMD on top of a fixed rate

12/min is documented, never measured — S4 is still open. Multiplying the interval
on each 429 and decaying it back on sustained success means a wrong constant
degrades gracefully instead of hammering an invisible limit.

### Chunked multi-day execution, surfaced before the run

The 6h/14h conflict has no clever solution. The worker tracks a soft budget under
the cap, checkpoints, and schedules a continuation. Time counts only while the
foreground service holds the process, so a visible app finishes sooner.

The part that matters is telling the user first. "Start it and it finishes over
two or three days" is acceptable when stated up front and infuriating when
discovered at hour five.

### WorkManager over a hand-rolled Service

WorkManager persists the request, so process death, task killers and reboot all
re-run the work. That is the problem `resumableOperation.js` solves manually with
`resumableOp_<id>` keys and a startup sweep; the platform already does the
bookkeeping.

### The engine never talks to a screen

The extension's `ensureNotificationTabExistsAndIsReady` (`background.js:86-164`)
find-or-creates a tab, waits for it to answer, then drives control flow through
`chrome.tabs.sendMessage`. Close the tab and the operation strands.

Engine writes to Room; UI observes. The only UI→engine path is a persisted
command, which is also what makes notification actions work with no screen open.
This deletes an entire bug class rather than guarding against it.

### Checkpoint and effect in one transaction

A cursor ahead of its rows silently skips users; behind, it re-processes them.
Both are invisible until someone notices the wrong people are blocked. One
transaction makes the interleaving unrepresentable.

### Retry only what can succeed

`background.js:652` retries any failure once after a 62-second stall, including
permanent ones — a dead id costs a full minute, and a non-idempotent mutation may
be re-fired on an ambiguous error. Here `SelfTarget`, `SessionExpired` and
unrecognised codes are terminal by construction, because `RelationResult` names
them separately.

## Risks / Trade-offs

**12/min is wrong** → AIMD absorbs it, and S4 can still measure it later without
redesign.

**Multi-day runs feel broken** → mitigated by the up-front estimate and a
persistent progress notification; not fully solvable.

**OEM process killing** → WorkManager re-runs, checkpoints bound the loss. A
battery-optimisation exemption is offered once, never required.

**The state machine grows into the tasks** → kept pure and IO-free so it stays
testable; anything needing IO belongs in the worker.

## Migration Plan

Additive. Two new modules, `:app` gains three manifest permissions. Nothing ships
to users; rollback is a revert.

Order: pacer and retry policy first (pure, fully testable), then the state
machine, then the worker and reconciler, then the six tasks. Each independently
green.

## Open Questions

- **The real rate limit** stays unmeasured by choice. If S4 later shows it differs
  materially, the constant changes and the duration estimates with it.
- **Battery-optimisation prompting** is offered once and dismissible; whether that
  is enough against aggressive OEMs is unknown until real devices report back.
