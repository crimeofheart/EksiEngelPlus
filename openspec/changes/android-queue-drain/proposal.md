## Why

Queued Android operations sometimes stop between runs and only resume after a pull to refresh in İşlem durumu. The finishing worker enqueued the next run as new unique work with `REPLACE`, cancelling itself and dropping its foreground service just as the next run had to start one. From the background Android 12+ refuses that start, and the job can also be deferred, so the queue waits for the reconciler's drain, which only a refresh or cold start runs.

## What Changes

- One `OperationWorker` drains the whole queue inside the foreground service it already holds. Work ends only when the queue is empty or a run parks on purpose (pause, lost session, budget, crash).
- The worker records its WorkManager id on each run it executes (`workRequestId`). Liveness checks, force-stop and re-run after process death follow that claim, because adopted runs have no tag of their own.
- Queueing a request and the worker's final look at the queue are each one Room transaction, so a request cannot land on the queue just after the worker left.
- A queued entry that no longer decodes is dropped instead of stranding everything behind it.
- A run parked on the foreground budget resumes with a fresh budget. Before, nothing reset `fgsMillisUsed`, so the next day's continuation was exhausted on arrival and parked again every day, holding the queue for good. One budget now covers every run a worker carries.
- Time with the app on screen no longer counts against the budget. `ForegroundBudget.releaseForeground()` existed for this and had no caller. The worker now follows the process lifecycle (`AppVisibility`, on `ProcessLifecycleOwner`): showing the app resets the budget to zero, as the platform resets its own timer, and leaving it starts billing again.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `android-operations`: queued operations drain without user action.

## Impact

Android only: `ops/runtime` (worker, reconciler, operation context, new `AppVisibility`, new `androidx.lifecycle:lifecycle-process` dependency at the catalog's existing lifecycle version) and two DAO queries in `core/database`. No schema change because `workRequestId` already exists. The extension is untouched. Its queue is an in-process dequeue loop in the service worker and has none of the WorkManager or foreground-service hand-off this fixes.
