## 1. Interruptible waits

- [x] 1.1 Slice the injected sleep in `OperationWorker`, polling the command bus
      every 250ms and raising the same signals `ensureActive()` does
- [x] 1.2 Pin that abandoning a wait spends no token — `ActionPacerTest`,
      verified by breaking `acquire()` to take the token before sleeping
- [x] 1.3 Widen the signal guard in both runners from `ensureActive()` to the
      whole loop; add `park()` absorbing a stop raised by the checkpoint itself
- [x] 1.4 Add `FakeContext.permitSignal` and two `TargetRunnerTest` cases for a
      signal raised inside the permit wait, verified by narrowing the guard back
- [x] 1.5 `cd frontend/app && npm run check && npm run package`

## 2. The wait made visible

- [x] 2.1 Replace the rate-limit-derived ETA in `OpsNotifier.progress` with the
      wait counting down; delete `humanDuration`, its only caller
- [x] 2.2 Throttle notification updates to second boundaries rather than the
      250ms poll
- [x] 2.3 Add `OperationWaits` and provide it as a `@Singleton`
- [x] 2.4 Publish from the worker, clearing in a `finally` so a signal mid-wait
      leaves nothing ticking
- [x] 2.5 Render it in the İşlem durumu running row, combining the checkpoint
      flow with the wait flow rather than collecting each separately
- [x] 2.6 Introduce and then revert `WaitReason` — recorded because the revert is
      the decision, not an accident
- [x] 2.7 `cd frontend/app && npm run check && npm run package`

## 3. A way through

- [x] 3.1 Add `ACTION_SHOW_OPERATIONS` and an intent-filter on
      `OperationsActivity`; make it `singleTop`
- [x] 3.2 `Göster` action plus content intent on the progress notification
- [x] 3.3 Content intent and auto-cancel on the completion alert
- [x] 3.4 `UiMessage` and `showMessage`; the queued-run messages become Snackbars
      carrying `göster`, everything else keeps Toast semantics
- [x] 3.5 Assert the action resolves in `WiringTest`, since nothing checks the
      constant against the manifest at compile time
- [x] 3.6 `cd frontend/app && npm run check && npm run package`

## 4. Lifecycle correctness

- [x] 4.1 `checkpoints().setState` and mark a run `RUNNING` before any network
      work, leaving the cursor alone
- [x] 4.2 Regression test that a started run is live before its first checkpoint
      and queues the next request behind it
- [x] 4.3 Drain the queue at startup reconciliation, starting the run directly
      rather than re-entering `enqueue`
- [x] 4.4 `runCancellable`, replacing `runCatching` at the three sites that
      report a failure to the user
- [x] 4.5 Fix the clear-all button stretching the queued section — `action()`
      carries `weight = 1`, correct in a row and wrong in a column
- [x] 4.6 Stop `resolveId` swallowing pause and stop signals — they are
      RuntimeExceptions, so its catch-all charged the user's Durdur to the
      target as an unresolvable nick
- [x] 4.7 Retext the running rows on each tick instead of rebuilding them: the
      rebuild destroyed Duraklat and Durdur under the user's finger, so taps
      went nowhere for the length of a cooldown
- [x] 4.8 `cd frontend/app && npm run check && npm run package`

## 5. Fixed-window pacing

- [x] 5.1 Replace the leaky bucket and its AIMD with a fixed 12-per-62s window,
      so every wait is a whole window and starts from the same number
- [x] 5.2 Ignore `Retry-After`; a rejection costs one full window, and the next
      window begins when the penalty ends
- [x] 5.3 Migrate the persisted snapshot from tokens/interval to window state
- [x] 5.4 Rewrite `ActionPacerTest` for the window model; verify the
      Retry-After rule by restoring the header and watching it fail
- [x] 5.5 Measure the wait from when the allowance runs out, not from when the
      window opened — otherwise it shortens by however long the actions took
- [x] 5.6 Match the extension exactly: 62s (`background.js:642`) and a 50ms gap
      between mutations (`background.js:548`)
- [x] 5.7 `cd frontend/app && npm run check && npm run package`

## 6. Browsing shell

- [x] 6.1 Restore `ensureLayer`, `preloadTab` and `warmNeighbours` in
      `bridge.js`; they were called from three places and defined nowhere
- [x] 6.2 `node --check bridge.js`
- [x] 6.3 `cd frontend/app && npm run check && npm run package`

## 7. Verification

- [x] 7.1 Full `./gradlew test` and `:app:assembleDebug` clean
- [x] 7.2 Every new test verified by breaking the code it covers
- [ ] 7.3 Run the instrumented suites on a device — NOT RUN: no device was
      attached when these were written, so `WiringTest.theShowOperationsActionResolvesToAnActivity`
      and `aRunThatHasStartedIsLiveBeforeItsFirstCheckpoint` have never executed
- [ ] 7.4 Confirm on device that the in-app countdown ticks alongside the
      notification — the one behaviour resting on `OperationWaits` being a single
      instance across worker and UI
- [ ] 7.5 Run `openspec validate android-ops-visibility` clean, then
      `openspec archive android-ops-visibility`
