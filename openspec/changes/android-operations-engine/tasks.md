## 1. Module setup

- [x] 1.1 Add `:ops:engine` and `:ops:tasks` to `settings.gradle.kts`; keep `:ops:engine` as Android (WorkManager) and put every pure piece behind interfaces so it stays testable without a device
- [x] 1.2 Add WorkManager and Hilt work to the version catalog; wire `HiltWorkerFactory` into the Application
- [x] 1.3 Add `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` to the app manifest, with the `dataSync` service type merged in

## 2. Pacing (pure, no Android)

- [x] 2.1 Write the `ActionPacer` test table first: sustained 12/min, idle-then-burst up to capacity, and that acquiring never exceeds the rate over a window
- [x] 2.2 Implement `ActionPacer` as a token bucket with an injected clock so tests need no real time
- [x] 2.3 Implement `penalize(seconds)` draining the bucket and blocking every caller until the delay elapses; test that a second caller waits on the first's 429
- [x] 2.4 Implement AIMD — widen the interval per 429, decay on sustained success, never below the configured rate; test both directions
- [x] 2.5 Persist and restore bucket state; test that resuming after a simulated kill does not start with a full bucket
- [x] 2.6 Implement `ReadPacer` at a looser rate for scrapes, and test it is independent of the action budget
- [x] 2.7 Implement `RetryPolicy`: three attempts, retry only `RateLimited`; test that `SelfTarget`, `SessionExpired` and unknown codes are terminal
- [x] 2.8 `./gradlew :ops:engine:test` green

## 3. State machine (pure, no IO)

- [x] 3.1 Define `OperationState` with the seven ported states plus `PAUSED_AUTH`, `PAUSED_BUDGET`, `PAUSED_NETWORK`
- [x] 3.2 Implement transitions as a pure function; test every legal transition and that illegal ones are rejected rather than silently ignored
- [x] 3.3 Implement the cooperative pause protocol with a 30s acknowledgement timeout that force-pauses and flags the checkpoint for re-verification
- [x] 3.4 Test that stop is terminal and pause is resumable

## 4. Operation context and checkpointing

- [x] 4.1 Define `OperationContext` exposing `ensureActive()`, `checkpoint(cursor)` and `publishProgress()`
- [x] 4.2 Implement `ensureActive()` reading the persisted command mailbox and throwing `PauseSignal`/`StopSignal`
- [x] 4.3 Write checkpoint and its effect rows in one Room transaction; instrumented test that a crash mid-write leaves neither
- [x] 4.4 Checkpoint every N units, N=1 for destructive operations
- [ ] 4.5 Implement `TaskQueueRepository` over `QueuedTaskEntity` with strictly serial dequeue

## 5. Worker and lifecycle

- [x] 5.1 Implement `OperationWorker` as a `@HiltWorker` `CoroutineWorker` with `getForegroundInfo()` of type `dataSync`
- [x] 5.2 Enqueue as unique work with `ExistingWorkPolicy.KEEP` and a CONNECTED constraint; test that a second request does not run concurrently
- [x] 5.3 Track foreground-service time against a soft budget, counting only while the FGS holds the process
- [x] 5.4 On budget exhaustion or `onTimeout()`: checkpoint, enter `PAUSED_BUDGET`, return success, schedule a delayed continuation
- [x] 5.5 Implement `OperationReconciler` from `Application.onCreate`: a RUNNING checkpoint with no live WorkManager request becomes INTERRUPTED
- [x] 5.6 Implement `PAUSED_AUTH` — checkpoint, stop the FGS, expose a resume route; never retry
- [ ] 5.7 WorkManager `TestDriver` coverage for the continuation-after-budget path

## 6. Notifications

- [x] 6.1 Create the two channels: low-importance progress, high-importance alerts
- [x] 6.2 Progress notification with determinate bar, counts, remaining estimate, and pause/stop actions
- [x] 6.3 Route notification actions through persisted commands so they work with no screen open
- [ ] 6.4 Request `POST_NOTIFICATIONS` at first operation start; degrade rather than block on denial
- [x] 6.5 Alerts for completed, session expired, budget paused, and fatal error

## 7. The six sources

- [x] 7.1 `OperationTask` interface and `SingleActionTask` (ban_source 1)
- [x] 7.2 `ListActionTask` (4) over the stored author list, resolving ids as needed
- [x] 7.3 `FavActionTask` (2): both favouriter endpoints, novice gated on `enableNoobBan`, ids backfilled per nick
- [x] 7.4 `FollowActionTask` (3) over a target's followers
- [x] 7.5 `TitleActionTask` (6) honouring the time specifier and de-duplicating authors
- [x] 7.6 `UndoBanAllTask` (5) over the scraped blocked list, checkpointing every unit
- [x] 7.7 Verify each task sends the `ban_source` integer from `enums.js`
- [x] 7.8 MockWebServer coverage per task: happy path, a mid-run 429, and a mid-run session expiry

## 8. Close out

- [ ] 8.1 `./gradlew build` green across all modules
- [ ] 8.2 Instrumented tests green on an emulator
- [ ] 8.3 Extend the dev harness to start a real operation against the live site
- [ ] 8.4 Re-verify the extension: `cd frontend/app && npm run check && npm run package`
- [ ] 8.5 Confirm `git diff --stat -- frontend/app/assets/` is empty and the version derivation is unchanged
- [ ] 8.6 `openspec validate android-operations-engine` clean
