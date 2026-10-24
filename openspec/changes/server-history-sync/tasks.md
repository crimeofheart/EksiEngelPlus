## 1. Backend: schema

- [x] 1.1 Create Django app `history` and register it in `INSTALLED_APPS`
- [x] 1.2 Models `Account`, `Device`, `PairingCode`, `Task`, `Event`, `RelationState`, `DailyTally`, `DailyActor`, `NickSeen`, `AdminAccess` per design D2, with enum constants in one module
- [x] 1.3 Indexes and constraints from D2, including both partial indexes on `RelationState`, `(kind, last_added_at)`, and `db_index=False` on `Event.device` / `.task` / `.action`; generate the migration and check that `sqlmigrate` shows the partial indexes
- [x] 1.4 `api.Action.task_id` (nullable UUID, indexed) migration; `WriteActionViewSerializer` accepts it as optional
- [x] 1.5 Tests: unique `event_uuid`, unique relation key, cascade delete from `Account` removes every dependent row, and a report without `task_id` still saves
- [x] 1.6 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 2. Backend: ingest

- [x] 2.1 `history/ingest.py` step 1: event insert `ON CONFLICT DO NOTHING RETURNING`, plus `NickSeen` upsert
- [x] 2.2 Steps 2–3: per-key collapse in Python, then a sorted, set-based `RelationState` upsert (state guard on `last_changed_at`, `LEAST`/`GREATEST` add times, summed `add_count`, ANDed `legacy_only`)
- [x] 2.3 Step 4: `DailyTally` and `DailyActor` upserts with Istanbul days computed in SQL, written last and sorted
- [x] 2.4 Task upsert: monotonic status/counter merge, epoch check (409), per-account `change_seq` under the account row lock (D5)
- [x] 2.5 `manage.py history_rebuild [--verify]`: recompute `RelationState`, `DailyTally` and `DailyActor` from `Event` and diff
- [x] 2.6 Tests for every `history-ledger` relation-state scenario and the tally scenarios (day boundary, duplicate push); a property test that incremental ingest equals rebuild for random orders, duplicates included
- [x] 2.7 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 3. Backend: run reports feed the ledger

- [x] 3.1 List the production `ban_mode` values and `ActionConfig` flag combinations, then write the mapping table in `history/legacy.py`
- [x] 3.2 `legacy_events(action)`: UUIDv5 ids, find-or-create the `LEGACY` account by `legacy_actor_eksi_id`, `occurred_at = Action.date`, unmapped combinations counted
- [x] 3.3 Call it from `CollectActionDataSerializer.create` in the same transaction; a test posting a 10 k-target report finishes under 2 s and creates 10 k `LEGACY` events
- [x] 3.4 `manage.py history_backfill_legacy` (pk order, resumable, prints created/skipped counts)
- [x] 3.5 Tests: re-run creates 0, legacy undo-ban deactivates, the live path and backfill overlap without duplicates, and an empty `author_list` creates nothing (no double count)
- [x] 3.6 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 4. Backend: sync API

- [x] 4.1 `DeviceTokenAuthentication` (`Authorization: Device <token>`, SHA-256 lookup, revoked check, `last_seen_at` bumped at most once a minute)
- [x] 4.2 `POST /api/sync/enroll` (with or without a join key), returning token and join key once
- [x] 4.3 `POST /api/sync/pair/start` and `/pair/claim`: re-parent the claimer's old account's tasks, events, relation state and `DailyActor` rows (merging on conflict) in one transaction
- [x] 4.4 `POST /api/sync/push` (≤100 tasks, ≤500 events, gap marker) and `GET /api/sync/pull?cursor=` with paging
- [x] 4.5 `POST /api/sync/tasks/<id>/cancel` and `/takeover` (15-minute staleness rule, epoch bump)
- [x] 4.6 `GET /api/sync/history?before=&limit=`: own account only; no client UI
- [x] 4.7 `DELETE /api/sync/account`: subtract the account's events from `DailyTally` grouped by day, kind, direction and result, then cascade, in one transaction
- [x] 4.8 Scoped throttles (enroll and pair-claim per IP, push per device); add `Authorization` to the CORS allowed headers, with a test
- [x] 4.9 Tests for every `history-sync` server scenario: cross-account 404, the concurrent-commit cursor case (two transactions, no change skipped), deletion leaving the rebuild diff empty, and run-report legacy accounts surviving deletion
- [x] 4.10 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 5. Backend: stats and admin

- [x] 5.1 `history/stats.py`: `top(kind, scope, days, limit, include_legacy)`, `timeseries`, `user_summary`, `user_events`, `user_relations`, `user_tasks` (with the linked `api.Action` by `task_id`); list functions use keyset pagination
- [x] 5.2 `/api/stats/*` DRF views (`IsAdminUser`), with a 10-minute cache on `top` and `timeseries`
- [x] 5.3 Port `most_banned/`, `most_banned_unique/` and `user_stat/` onto `stats.py`, keeping field names; paginate `user_stat/` with a fixed query count per page (assert it with `assertNumQueries`)
- [x] 5.4 `AdminAccess` logging for per-user routes and admin pages; read-only admin for the log
- [x] 5.5 Admin dashboard (`django_EksiEngel/admin.py` `index`): current top 20 for BLOCK and MUTE and a 90-day series; `EksiSozlukUser` change page links to summary, history and relations
- [x] 5.6 Tests for every `history-stats` scenario: window, dashboard/JSON equality, response shape unchanged, and same answer after migration (fixture of legacy `Action` rows, old view output saved before porting)
- [x] 5.7 Seed 5 M synthetic events locally; `EXPLAIN` every `top` scope and `timeseries` and assert that no plan node touches `history_event`; record timings in the PR
- [x] 5.8 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 6. Extension

- [x] 6.1 `config.js`: add `syncHistory: true`
- [x] 6.2 `relationHandler.js`: return per-kind statuses alongside the folded result, with no behaviour change
- [x] 6.3 `queue.js`: assign `taskId` (UUIDv4) on enqueue, persist it with `queueData`, and drop items whose task was cancelled or taken over
- [x] 6.4 New `historySync.js`: outbox in `chrome.storage.local` (20 k cap, gap counter), enrol or join via the `chrome.storage.sync` join key, push/pull with backoff
- [x] 6.5 Emit events at the call sites (`programController.js` ~1108, `background.js` ~750/832), and task status at start, progress and end; nothing is written when `syncHistory` is false
- [x] 6.6 Run report (`background.js:1287-1297`): `task_id` always; `author_list: []` while `syncHistory` is on, full list while off
- [x] 6.7 `alarms` permission in both manifest variants; 5-minute flush alarm; pull every 60 s or 25 targets while running; honour `cancel_requested`
- [x] 6.8 Queue page: remote tasks read-only with device label and progress, plus "İptal et" and "Burada çalıştır"
- [x] 6.9 Settings (where `sendData` lives, `faq.js`): `syncHistory` toggle, pairing (show code / enter code), "Sunucudaki verilerimi sil" with confirm
- [ ] 6.10 Manual check on Chrome and Firefox desktop (the sync client was run end to end in Node against a live dev server, two installs and a cancel; not yet in a real browser): two installs on one synced profile link without a code; a run with the backend down completes and uploads later; the run report is empty-listed with history on and full with it off
- [x] 6.11 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 7. Android

- [x] 7.1 `Config.kt`: `syncHistory = true` with a `config.js` line ref; extend `ConfigTest`
- [x] 7.2 Room v2→v3 migration: `history_outbox`, task UUID on `queued_task`, sync-state row (cursor); commit the exported schema
- [x] 7.3 `OperationWorker`: outbox rows inside the checkpoint transaction; instrumented test for process death between request and commit
- [x] 7.4 `ActionReport.kt:64-71`: `task_id` always; empty `author_list` while `syncHistory` is on; extend `ActionReportTest`
- [x] 7.5 `HistorySyncWorker` (periodic, plus expedited at task end, network constraint): push/pull, cancel and epoch-409 handling
- [x] 7.6 Device token in the install-identity store; enrol on first run when `syncHistory` is on
- [x] 7.7 Operations screen: remote tasks with cancel and take over; settings: toggle, pairing, delete
- [x] 7.8 `./gradlew :app:assembleDebug` plus unit and instrumented tests pass
- [x] 7.9 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 8. Privacy, release notes, deploy

- [x] 8.1 `privacy/index.html`: what the ledger stores; kept until deletion; run reports still feed rankings when history is off; admin access and its logging; self-service deletion for synced data and by-request deletion for run-report data (both `data-tr` and `data-en`)
- [ ] 8.2 Play Data Safety: update the retention and deletion text in the Play Console (the text is in specs/android-persistence). Firefox manifest unchanged (decided: no `personallyIdentifyingInfo`)
- [x] 8.3 `changelog.js` entry (app and extension) and the `ReleaseNotes.kt` mirror; run `npm run changelog`
- [x] 8.4 (Deployed 2026-10-24: 4,698 reports backfilled to 19,911 events, rebuild verified; the only ranking differences come from one title report with no settings snapshot, left unmapped.) Before deploy, save the `most_banned_unique/` response. Deploy the backend (pull, `migrate`, restart `gunicorn-eksiengel`), then run `history_backfill_legacy` and `history_rebuild --verify`, and diff against the saved response
- [x] 8.5 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`
