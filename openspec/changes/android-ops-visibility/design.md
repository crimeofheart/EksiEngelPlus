## Context

`ActionPacer.acquire()` computed a wait under its mutex, released it, and then
slept the whole thing in one `sleep(wait)` (`ActionPacer.kt:84-103`). The only
reader of the command bus was `RoomOperationContext.ensureActive()`
(`RoomOperationContext.kt:126`), called once per target from the top of the
runner loop (`Tasks.kt:38-50`). So a command posted during a wait was seen only
after it expired.

At the default 12 permits per minute the base interval was `60000 / 12 = 5000ms`,
so this is not an edge case: a healthy run spent most of its wall time inside
exactly that sleep.

The notification's ETA came from the same constant
(`OpsNotifier.kt:74-78`): `remaining / actionsPerMinute * 60000`. It restated the
ceiling, and did not move while the run sat in a wait.

The bucket's cooldowns were also inconsistent in two separate ways. It honoured
`Retry-After`, which the server sent as 23 or 24 as often as 60 — the remainder
of a window whose start the client cannot see — so the wait ended inside a window
that was already spent. And once that was fixed, the wait was still measured from
when the window opened, which subtracts however long the twelve actions took to
send: 62s, then 57s, drifting down each pass.

## Goals / Non-Goals

**Goals:**

- Durdur and Duraklat act during a wait, not after it.
- The waiting is visible, and identical wherever it is shown.
- A run's reported state matches what it is actually doing.

**Non-Goals:**

- Raising the ceiling. 12 a minute stays 12 a minute; only its shape changes,
  and it is not user-configurable upward — a user cannot consent on the
  server's behalf.
- Persisting the wait. It is deliberately in-memory.
- Replacing the checkpoint as the record of progress.

## Decisions

**Pacing is a fixed window, not a leaky bucket.** 12 actions per 62s, spent as
fast as a 50ms floor allows and then waited out in full. Both constants come from
the extension rather than from our own reasoning — `background.js:642`
(`let waitTimeInSec = 62`) and `background.js:548` (`await utils.sleep(50)`,
"Small delay to avoid rate limiting") — because the two clients hit the same
account and the extension's values have been running against this server for
years. The AIMD backoff and its decay went with the bucket: they existed to find
a limit we could not see, and the limit is the window.

The trade-off is the shape of the traffic, not its volume: a burst of twelve
followed by an idle minute rather than one action every five seconds. Safe
against a sliding 60s window, which `no sixty-second span ever contains more than
twelve actions` proves. Less safe than even spacing against a *fixed* window
aligned to the server's clock, where two bursts could straddle a boundary — that
would surface as an occasional 429, which now costs one clean 62s wait and
continues.

**The cooldown is measured from exhaustion, not from when the window opened.**
Measuring from the open assumes our clock agrees with the server's about when
the window began, which is the assumption `Retry-After` already disproved. From
exhaustion is never shorter than the window being enforced.

**The wait is sliced in the worker, not the pacer.** `ActionPacer` already takes
`sleep` as a parameter, so the interruptible version is supplied at the one place
that has a command bus and a notification to update. The pacer stays a pure
window with no knowledge of operations, and its unit tests keep injecting a
clock-advancing lambda.

**Abandoning a wait is safe because the permit is taken after the sleep, not
before.** `acquire()` loops: it computes a wait, sleeps, then re-enters the lock
and takes a permit. A signal raised in the sleep therefore costs nothing. This is
load-bearing enough to be pinned by its own test rather than left as a comment.

**Ticks retext rows rather than rebuilding them.** Rendering the running section
from a flow combined with the countdown called `removeAllViews()` once a second
for the length of a cooldown, so a tap begun on Duraklat landed on a view that no
longer existed. The countdown now updates the progress `TextView` in place. This
is also why the two flows are collected separately again, despite the earlier
decision to combine them: the combined form was correct about state and wrong
about identity.

**A `WaitReason` enum was introduced and then removed.** It distinguished the
bucket's ordinary turn from a 429 penalty, so that only a penalty would count
down. Once the countdown applied to both — they are the same thing from outside,
the API not letting the next action through — it distinguished nothing any caller
used, and was reverted rather than left as an unused abstraction.

**The signal guard moved from `ensureActive()` to the whole loop.** Waits happen
in `performWithRetry` (`Tasks.kt:186`), well below the old `try`. Guarding the
loop rather than adding a second `try` around the permit call keeps one answer to
"where does a signal park this run", for signals that can now come from anywhere.

**`park()` absorbs a stop raised by the checkpoint itself.**
`RoomOperationContext.checkpoint` raises `StopSignal` when the row is gone
(`RoomOperationContext.kt:155`), which is how a cancel reaches a live run. Without
this, a signal raised while handling a signal escaped as a failure.

**The wait is published through `OperationWaits`, mirroring the command bus.**
Both are in-memory singletons crossing the worker/UI boundary in one process. A
persisted wait would count down a pause nothing is observing after a restart. The
alternative — writing the remaining milliseconds to the checkpoint row — was
rejected at one Room write per second per run for a label.

**Navigation is an implicit intent.** `:feature:lists` depends on `:ops:runtime`;
naming `OperationsActivity` from `OpsNotifier` would invert that. The cost is
that the action string and the intent-filter are matched only at runtime, which
`WiringTest` covers.

**A run is marked `RUNNING` by a targeted `UPDATE`, not an upsert.** The row
carries the cursor, `startedAt` and the serialised request; an upsert built from
the worker's in-memory view would risk overwriting them, which is the class of
bug that previously dated every run to 1970.

## Risks / Trade-offs

- **A notification and a foreground-info update once a second for the life of a
  wait.** Throttled to second boundaries rather than the 250ms poll, and
  `setOnlyAlertOnce`/`setSilent` are already set. Accepted.
- **`OperationWaits` assumes one process.** True for WorkManager's default
  configuration here. If the worker were ever moved to its own process the
  countdown would silently stop appearing in-app while continuing in the
  notification — the first thing to check if that symptom appears.
- **The running section is rebuilt whenever a checkpoint changes**, which during
  a run is every few actions rather than every second. Accepted for one or two
  rows; the per-second path no longer rebuilds at all, which is what made the
  buttons tappable again.
- **Carried forward deliberately:** the ad-slot collapsing added while chasing
  phantom gaps stays, at the user's request, though the gaps turned out to belong
  to the official app taking over the link.
- **Signals travel as `RuntimeException`s, so any catch-all can swallow them.**
  `resolveId` did exactly that. The remaining catch-alls (`RelationClient`'s
  around the HTTP call, the worker's around `task.run`) sit outside the permit
  waits, but nothing structurally prevents the next one from reintroducing the
  bug. Making the signals uncatchable was considered and rejected: every
  candidate base type either breaks `runCatching` semantics elsewhere or abuses
  `Error`.
- **The instrumented tests added here have not been executed.** No device was
  attached when they were written; they compile and are pinned by review only.
