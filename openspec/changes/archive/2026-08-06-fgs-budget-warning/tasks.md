## 1. Budget threshold

- [x] 1.1 Add a configurable warning fraction to `ForegroundBudget`, default 0.8
- [x] 1.2 Add a one-shot `shouldWarn()` that returns true only on the first crossing within a slice
- [x] 1.3 Unit-test: no warning below the threshold, exactly one at and above it, and none after `releaseForeground()` puts consumption back under

## 2. Notification

- [x] 2.1 Add a budget-warning alert to `OpsNotifier` with a content intent that opens the app
- [x] 2.2 State the remaining item count and that opening the app finishes it without using background time
- [x] 2.3 Reuse the alerts channel; keep the existing exhaustion notification as the fallback

## 3. Wiring

- [x] 3.1 Check `shouldWarn()` in `RoomOperationContext.ensureActive()`, alongside the exhaustion check
- [x] 3.2 Pass a callback rather than the notifier itself, so `ops:engine` stays free of Android
- [x] 3.3 Instrumented test: crossing the threshold fires the callback exactly once

## 4. Verify

- [x] 4.1 `./gradlew build` green
- [x] 4.2 Instrumented tests green on an emulator
- [x] 4.3 Re-verify the extension: `cd frontend/app && npm run check`
- [x] 4.4 `openspec validate fgs-budget-warning` clean, then archive
