## Context

`ActionPacer.acquire()` computed a wait under its mutex, released it, and then
slept the whole thing in one `sleep(wait)` (`ActionPacer.kt:84-103`). The only
reader of the command bus was `RoomOperationContext.ensureActive()`
(`RoomOperationContext.kt:126`), called once per target from the top of the
runner loop (`Tasks.kt:38-50`). So a command posted during a wait was seen only
after it expired.

At the default 12 permits per minute the base interval is `60000 / 12 = 5000ms`,
so this is not an edge case: a healthy run spends most of its wall time inside
exactly that sleep.

The notification's ETA came from the same constant
(`OpsNotifier.kt:74-78`): `remaining / actionsPerMinute * 60000`. It restated the
ceiling, and did not move while the run sat in a wait.

## Goals / Non-Goals

**Goals:**

- Durdur and Duraklat act during a wait, not after it.
- The waiting is visible, and identical wherever it is shown.
- A run's reported state matches what it is actually doing.

**Non-Goals:**

- Changing the pacing rate or its AIMD behaviour.
- Persisting the wait. It is deliberately in-memory.
- Replacing the checkpoint as the record of progress.

## Decisions

**The wait is sliced in the worker, not the pacer.** `ActionPacer` already takes
`sleep` as a parameter, so the interruptible version is supplied at the one place
that has a command bus and a notification to update. The pacer stays a pure
token bucket with no knowledge of operations, and its unit tests keep injecting a
clock-advancing lambda.

**Abandoning a wait is safe because the token is taken after the sleep, not
before.** `acquire()` loops: it computes a wait, sleeps, then re-enters the lock
and takes a token. A signal raised in the sleep therefore costs nothing. This is
load-bearing enough to be pinned by its own test rather than left as a comment.

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
- **The running section re-renders fully each second.** `removeAllViews()` and
  rebuild, for one or two rows. Accepted over introducing a diffing adapter.
- **Carried forward deliberately:** the ad-slot collapsing added while chasing
  phantom gaps stays, at the user's request, though the gaps turned out to belong
  to the official app taking over the link.
- **The instrumented tests added here have not been executed.** No device was
  attached when they were written; they compile and are pinned by review only.
