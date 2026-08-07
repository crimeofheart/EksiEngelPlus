## 1. Assembly gaps

- [x] 1.1 Implement `Configuration.Provider` in `EksiEngelPlusApp` and supply `HiltWorkerFactory`; remove `WorkManagerInitializer` from the manifest
- [x] 1.2 Declare `OperationCommandReceiver` in `:ops:runtime`'s manifest, not exported
- [x] 1.3 Call `OperationReconciler.reconcile()` at startup, before anything reads operation state
- [x] 1.4 Verify on a device that a worker starts: `WM-WorkerWrapper: Starting work for ...` in logcat
- [x] 1.5 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 2. Work payloads

- [x] 2.1 Persist the operation request in `operation_checkpoint.requestJson`; carry only the id in `Data`
- [x] 2.2 Read the request from input data first, then the checkpoint, so work enqueued by an older build still finishes
- [x] 2.3 Drop the request from the continuation path too
- [x] 2.4 Instrumented test: enqueue 5,000 nicks without breaching the 10 KB cap
- [x] 2.5 Verify the extension is untouched

## 3. Cancelling a run

- [x] 3.1 Delete the checkpoint on cancel rather than marking it `STOPPED`
- [x] 3.2 Raise `StopSignal` from `checkpoint()` when the row is gone, so a live run cannot recreate it as `RUNNING`
- [x] 3.3 Add `iptal` to the resume bar, at the far end
- [x] 3.4 Verify on a device that a cancelled run stays gone across a restart
- [x] 3.5 Verify the extension is untouched

## 4. Explicit actions for expensive ones

- [x] 4.1 Post a paused notification carrying `Devam et` and `Durdur`, on the foreground notification's id
- [x] 4.2 Point the notification body at the app rather than at resume
- [x] 4.3 Replace the resume bar's tap-to-resume with a `devam et` button beside `iptal`
- [x] 4.4 Swipe the bar to dismiss for the session, claiming the gesture on `ACTION_DOWN` so `ACTION_MOVE` is delivered
- [x] 4.5 Verify on a device: swipe dismisses, restart restores, `iptal` deletes
- [x] 4.6 Verify the extension is untouched

## 5. Browsing corrections

- [x] 5.1 Gate profile items on the site's own `.relation-link` buttons; let an injector report "not ready" and be retried
- [x] 5.2 Exempt sub-frames from off-site routing
- [x] 5.3 Give the off-site hand-off a browser-only selector, and keep Ekşi-hosted intent data in the WebView
- [x] 5.4 Widen session reprobing while logged out, and drop the interval
- [x] 5.5 Intercept navigations to the XHR-only profile partials
- [x] 5.6 Verify on a device: own profile has no block items, another's does, and the official app no longer takes over
- [x] 5.7 Verify the extension is untouched

## 6. Load time

- [x] 6.1 Measure where the time goes before changing anything
- [x] 6.2 Block third-party ad and analytics hosts, returning an empty 200
- [x] 6.3 Leave Ekşi's own hosts and the font CDNs alone; unit-test both directions
- [x] 6.4 Collapse the slots the blocked hosts would have filled
- [x] 6.5 Re-measure and record: 23.4s to 6.0s cold start
- [x] 6.6 Verify the extension is untouched

## 7. Injection cost

- [x] 7.1 Mark promo candidates before the style read, not after the filter
- [x] 7.2 Narrow candidates to elements shallow enough to be an overlay
- [x] 7.3 Add a not-yet-seen guard to badge hiding
- [x] 7.4 Clear marks on navigation so a restyled element gets one fresh look
- [x] 7.5 Instrumented test asserting the per-scan cost bound, plus promo-hiding coverage that did not exist
- [x] 7.6 Verify the extension is untouched

## 8. Close out

- [x] 8.1 Run the full Android check: `cd android && ./gradlew test :app:assembleDebug`
- [x] 8.2 Confirm `git diff --stat -- frontend/app/ backend/` is empty
- [ ] 8.3 Add an end-to-end test that exercises the manifest, the Hilt-in-`Application` path and WorkManager limits — DEFERRED: the right answer to the three assembly gaps, and its own change rather than a task here
- [ ] 8.4 Run `openspec validate android-device-hardening` clean, then `openspec archive android-device-hardening`
