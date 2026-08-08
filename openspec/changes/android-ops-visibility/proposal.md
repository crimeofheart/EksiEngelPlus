## Why

A bulk run spends most of its life waiting. At the documented ceiling of 12
actions a minute the pacer holds each action for ~5s, and a 429 holds every
caller for up to 30s more — so at any given moment the app is far more likely to
be waiting than acting. Nothing said so. The notification showed a whole-run ETA
derived from that same rate limit, which never moved; İşlem durumu showed
counters that sat still; and Durdur pressed during a wait did nothing at all
until the wait expired, because the only thing reading the command bus ran
between actions.

The result was an app that looked frozen precisely when it was working normally,
and unresponsive precisely when the user most wanted out.

Device testing then surfaced a second family of defects in the same area: runs
that had begun reporting themselves as `başlamadı`, queues that never drained
after a reinstall, and cancellation reported to the user as a failed operation.

## What Changes

- Pacing waits become interruptible. The injected sleep slices and polls the
  command bus, so Durdur and Duraklat act within 250ms instead of after the
  wait.
- The wait itself is counted down, replacing the whole-run ETA — in the
  notification and, via `OperationWaits`, in the İşlem durumu running row.
- Both places that announce a run offer a **Göster** action through to İşlem
  durumu; so does the completion notification.
- The signal guard covers the whole runner loop, not just `ensureActive()`, so a
  signal raised inside a wait parks the run rather than surfacing as
  "İşlem başarısız".
- A run is marked `RUNNING` before it touches the network, so one opening with a
  cooldown is not listed as `başlamadı` and does count as live.
- The queue drains at startup, not only when a run reaches a terminal state.
- `runCancellable` replaces `runCatching` where a failure is shown to the user:
  `CancellationException` is not a failure.
- `ensureLayer`, `preloadTab` and `warmNeighbours` are restored to `bridge.js`.
  They were called from three places and defined nowhere, throwing on every page
  load and leaving the swipe preview blank.

## Non-goals

- Changing the rate limit, or making it user-configurable upward. A user cannot
  consent on the server's behalf.
- Reworking how progress is persisted. The checkpoint remains the record; the
  wait is deliberately not persisted.
- A general in-app notification framework. Only messages that hand a run off get
  an action.

## Capabilities

### New Capabilities

_None._ This change makes existing behaviour observable and interruptible rather
than introducing a new capability.

### Modified Capabilities

- `android-operations`: a run's lifecycle state must reflect that it has begun
  before its first checkpoint; signals must be honoured from anywhere in the
  run, including inside a wait; the queue must drain whenever nothing is live,
  including at startup.
- `android-rate-limiting`: a wait must be observable and interruptible — the
  pacer reports how long is left and abandons a wait without spending the token
  it was waiting for.

## Impact

- `:ops:engine` — `ActionPacer`/`ReadPacer` sleep contract; `TargetRunner` signal
  guard and the new `park()`.
- `:ops:runtime` — `OperationWorker` interruptible sleep and RUNNING marking,
  `OperationWaits` (new), `OpsNotifier` countdown/actions/content intents,
  `OperationReconciler` startup drain.
- `:core:database` — `checkpoints().setState`.
- `:feature:lists` — `UiMessage`, `showMessage` (Snackbar), İşlem durumu running
  row, `OperationsActivity` intent-filter.
- `:webview` — `bridge.js` restored functions.
- `frontend/app/` runtime code is **not** touched.
