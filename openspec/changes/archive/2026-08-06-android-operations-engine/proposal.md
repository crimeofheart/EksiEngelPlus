# Android operations engine

## Why

`android-foundations` shipped the layers — parser, clients, Room, DataStore — but
nothing drives them. This change adds the part that turns "can perform one
mutation" into "can perform ten thousand over days without losing its place".

Three things make that non-trivial, and all three are already known rather than
guessed:

- **Rate limiting.** ~12 actions/minute. A 10,000-user run is therefore ~14 hours
  of wall clock.
- **Android 15 caps a `dataSync` foreground service at ~6 hours per rolling 24.**
  That cannot be reconciled with 14 hours, so multi-session execution is a
  structural requirement, not a refinement.
- **Sessions cannot be renewed headlessly.** `/giris` is behind Turnstile, so an
  expired session must park the operation and route the user back to the WebView.

## What Changes

- **`ActionPacer`** — a persisted token bucket at 12 actions/minute, the limit the
  extension documents. Replaces the extension's blast-and-absorb approach and its
  two divergent cooldown implementations with one component.
- **`RetryPolicy`** — three attempts, honouring the `Retry-After` the client
  already returns. Applies a 429 penalty **globally**, not just to the caller that
  received it.
- **`OperationStateMachine`** — the seven states from `resumableOperation.js` plus
  `PAUSED_AUTH`, `PAUSED_BUDGET` and `PAUSED_NETWORK`. Pure Kotlin, no IO.
- **`OperationWorker`** — a `CoroutineWorker` promoted to a foreground service by
  WorkManager, with a soft time budget and a scheduled continuation when it runs out.
- **`OperationReconciler`** — startup crash recovery, replacing the manual
  `resumableOp_<id>` sweep in `background.js:23-59`.
- **`OpsNotifier`** — two channels: silent progress with pause/stop actions, and
  high-importance alerts.
- **The six single-shot sources** from `background.js`: SINGLE, LIST, FAV, FOLLOW,
  TITLE, UNDOBANALL.

## Capabilities

### New Capabilities

- `android-rate-limiting`: how actions are paced, how 429s propagate, and why the
  penalty is global.
- `android-operations`: the operation lifecycle — states, checkpoints, crash
  recovery, and the foreground-service time budget.

### Modified Capabilities

None. `android-http-stack` and `android-persistence` are consumed unchanged.

## Impact

**New** — `android/ops/engine`, `android/ops/tasks`, and unit tests for the pacer,
the state machine and each task.

**Modified** — `android/settings.gradle.kts`, `:app` dependencies, and the
manifest gains `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC`.

**Untouched** — `frontend/app/`, `backend/`, and the version lockstep.

## Non-goals

- UI. The engine writes state to Room; screens arrive later.
- The WebView shell and JS bridge.
- Date filters, migrations, and list refresh — those are later changes.
- Telemetry reporting of completed operations.
- Measuring the real rate limit. 12/min is taken as given, matching the extension.
