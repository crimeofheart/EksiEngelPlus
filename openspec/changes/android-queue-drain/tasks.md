## 1. Drain the queue without user action

- [x] 1.1 Loop `OperationWorker.doWork()` over the queue, adopting the next run in `archive()`'s transaction, and resume the claimed run on re-run.
- [x] 1.2 Add `claim`/`claimedBy` to `OperationCheckpointDao`; preserve `workRequestId` across checkpoints.
- [x] 1.3 Make `OperationReconciler` liveness and force-stop follow the claim; make `enqueue()` transactional.
- [x] 1.4 Drop undecodable queue entries instead of stalling (`takeNextQueued`).
- [x] 1.5 Instrumented coverage for adopted-run liveness, re-run lookup, force-stop and unreadable entries; run `:ops:runtime` and `:core:database` connected tests and unit tests.
- [x] 1.7 Start a `PAUSED_BUDGET` continuation with a fresh budget, share one budget across the runs a worker carries, and pin the rule in `BudgetCarriedIntoTest`.
- [x] 1.8 Bill the budget only while the app is off screen: add `AppVisibility`, reset the budget on `releaseForeground()`, add `holdForeground()`, and cover it in `ForegroundBudgetVisibilityTest`.
- [ ] 1.6 On a device, queue three runs, turn the screen off, and confirm all three finish without opening the app.
