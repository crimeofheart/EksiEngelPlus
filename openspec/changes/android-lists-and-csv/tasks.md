## 1. Module scaffold

- [x] 1.1 Add `:feature:lists` to `android/settings.gradle.kts` and create `android/feature/lists/build.gradle.kts` as an Android library (Hilt, KSP, serialization, `minSdk 26`, JVM 17) depending on `:core:database`, `:core:model`, `:eksi:client`, WorkManager and Material
- [x] 1.2 Add `implementation(project(":feature:lists"))` to `android/app/build.gradle.kts`; confirm `:app:assembleDebug` still builds with the empty module
- [x] 1.3 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 2. CSV codec (pure, JVM-tested)

- [x] 2.1 Write `CsvCodec.parseLine` in `:feature:lists` — quoted-field handling, ported from `authorListPage.js:112-132`
- [x] 2.2 Write `CsvCodec.parseImport(text)` — split on `/\r?\n/`, detect a `username` header, skip blank lines and blank first fields, return `(nick, epochDay?)` pairs
- [x] 2.3 Wire date parsing: `YYYY-MM-DD` first, then `TurkishDateParser`; an unparseable date yields a null date, never a dropped row
- [x] 2.4 Write `CsvCodec.writeExport(rows, out)` — streams `Username,RegistrationDate` plus `<nick>,<YYYY-MM-DD or blank>` rows joined with `\n`, formatting the epoch day in UTC, unquoted, matching `notificationHandler.js:186-194`
- [x] 2.5 Write `CsvCodec.suggestedFilename(listType, today)` → `eksiengel_<blocked|muted|followed>_users_<YYYY-MM-DD>.csv`
- [x] 2.6 Unit-test the codec in `src/test/`: headerless file, `username` header, quoted comma, Turkish date, `dün` (unparseable, nick kept), blank date column, CRLF input, and a round trip of export→import
- [x] 2.7 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 3. Relation list sync

- [x] 3.1 Add `RelationUserDao.pruneStale(listType, seenBefore): Int` and `AuthorListDao.getAll(): List<AuthorListEntity>` as new `@Query` methods; confirm the committed Room schema `1.json` is unchanged
- [x] 3.2 Write `ListSyncer` — for `BLOCKED`/`MUTED` drive `ScrapeClient.relationPage` page by page from the stored cursor until `relations.isLast`; for `FOLLOWED` drive `followPage(FOLLOWING, ownNick, n)` until an empty array
- [x] 3.3 Upsert each page into `relation_user` with `lastSeenAt` = sync start, then advance `list_sync_state.cursorPage` in the same suspend step
- [x] 3.4 On reaching the terminator: `pruneStale` (only when the pass started at page 1), set `isPartial = false`, stamp `lastFullRefreshAt`. On any early exit: set `isPartial = true` and prune nothing
- [x] 3.5 Map `SessionExpiredException` to an explicit session-lost result rather than an empty-list success
- [x] 3.6 Wrap `ListSyncer` in `ListSyncWorker` (`CoroutineWorker`, unique work per `ListType`, `ExistingWorkPolicy.KEEP`) and record the `BanSource.REFRESH_*_LIST` mapping for the telemetry sender that `android-settings-telemetry` will add
- [x] 3.7 Unit-test `ListSyncer` against a fake `ScrapeClient` and an in-memory DAO: resume from page 8, partial run prunes nothing, complete run prunes departed users, session loss does not clear rows
- [x] 3.8 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 4. Lists screen

- [x] 4.1 Create `ListsActivity` + layout — three rows (blocked, muted, followed), each with count, last-full-refresh time, a partial badge, and refresh/stop
- [x] 4.2 Create `ListsViewModel` combining `RelationUserDao.countOf`, `ListSyncStateDao.observe` and `OperationCheckpointDao` running-state into one UI state `Flow`
- [x] 4.3 Disable a list's refresh while an operation is `RUNNING`, per the design's request-pressure mitigation
- [x] 4.4 Add the overflow-menu entry in `BrowserActivity` that opens `ListsActivity`, and register the Activity in `AndroidManifest.xml`
- [ ] 4.5 Verify on device: counts rise live during a sync, the partial badge appears when a sync is stopped mid-run — UNBLOCKED, not yet run: a logged-in device is now available and a sync is read-only, so this is ready to do
- [x] 4.6 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 5. CSV export

- [x] 5.1 Add an export action per list that launches `ActionCreateDocument("text/csv")` with the suggested filename
- [x] 5.2 On result, join the list rows against `registration_date_cache` and stream through `CsvCodec.writeExport` into the SAF `OutputStream` off the main thread
- [x] 5.3 Treat a null `Uri` (dismissed picker) as a no-op; surface write failures as a message, not a crash
- [ ] 5.4 Verify a real export opens in a spreadsheet and imports into the extension's author list page unchanged — UNBLOCKED, not yet run: needs a synced list first (task 4.5)
- [x] 5.5 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 6. Author list import and editing

- [x] 6.1 Create `AuthorListActivity` + layout — a multiline paste field, a row count, Save / Append / Clear, and an Import file action
- [x] 6.2 Wire `ActionOpenDocument` for `text/csv` and `text/plain`, reading the stream into `CsvCodec.parseImport`
- [x] 6.3 Add `AuthorListDao.replaceAll(rows)` as a `@Transaction` doing `clear()` then `upsertAll()`, so a failed import leaves the previous list intact
- [x] 6.4 Seed `registration_date_cache` from parsed dates in the same transaction
- [x] 6.5 Report the outcome: rows imported, rows skipped, dates recognised
- [x] 6.6 Instrumented test: replace-mode import that throws partway leaves the old list present and unmodified
- [x] 6.7 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 7. LIST operations read the saved list

- [x] 7.1 Fill `OperationRequest.nicks` from `AuthorListDao.getAll()` at enqueue time, so neither `OpsModule` nor `ListActionTask` re-reads the table
- [x] 7.2 Refuse to enqueue a LIST operation, with a clear message, when the saved list is empty
- [x] 7.3 Add a "run on this list" entry point from `AuthorListActivity` that enqueues a LIST operation
- [x] 7.4 Confirm a resumed LIST run continues against the set in its serialised request, not a re-read of the table
- [x] 7.5 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 8. Sync progress UI

- [x] 8.1 Add `SyncProgress(page, seen)` and an `onProgress` callback to `ListSyncer.sync`, invoked after each page is durable — ordered before `shouldStop` so the trailing lambda keeps its existing meaning
- [x] 8.2 Publish progress from `ListSyncWorker` via `setProgress`, with `progressData`/`progressOf` owning the two `Data` keys
- [x] 8.3 Observe `getWorkInfosForUniqueWorkFlow` per list in `ListsViewModel`, mapping to `SyncStatus.Idle` / `Queued` / `Running(progress)`
- [x] 8.4 Render it: spinner on the row, freshness line doubling as the progress line, Refresh disabled while syncing, Stop enabled only while syncing, partial badge suppressed during a refresh
- [x] 8.5 Unit-test progress emission: one report per page, reported only after the rows are stored, and none after a stop
- [x] 8.6 Instrumented-test the `Data` round trip and the per-list work names
- [x] 8.7 Verify on emulator: idle, queued-without-network, and stop-returns-to-idle all render correctly
- [ ] 8.8 Verify the `Running` state with live page and user counts — UNBLOCKED, not yet run: follows from task 4.5
- [x] 8.9 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`
- [x] 8.10 Add a busy state to `AuthorListViewModel` via `launchBusy` — refuses a second start, always clears in a `finally`, and moves parsing off the main thread with the write
- [x] 8.11 Track the exporting list in `ListsViewModel`, so the busy state outlives a rotation the way the coroutine does
- [x] 8.12 Render both: spinner plus disabled mutators on the author list, spinner plus `dışa aktarılıyor…` on the exporting row, export also barred while that list syncs
- [x] 8.13 Move the export result strings out of the view model into `strings.xml`, matching the author-list screen
- [x] 8.14 Instrumented-test the busy contract: the flag rises and clears on success, clears after a throw, and a dismissed picker leaves the list untouched
- [x] 8.15 Verify on emulator: a save re-enables every control and leaves no spinner behind
- [ ] 8.16 Verify the export busy state on device — BLOCKED: the export button is gated on a non-empty list, which needs a session

## 9. Actions and presentation

- [x] 9.1 Add `takibe al` and `takipten çıkar` to the run chooser, reusing `TargetType.FOLLOW` with both ban modes
- [x] 9.2 Rebuild the chooser as three coloured groups of two, so each action sits beside its inverse
- [x] 9.3 Match the extension's button language across the lists screens: square, 2dp stroke, uppercase, wide tracking, resume-bar green as the accent
- [x] 9.4 Take the group colours from the extension's own notification palette rather than picking them by eye
- [x] 9.5 Keep the longest label on one line; verified on device that all six buttons share a height
- [x] 9.6 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 11. Extension parity

- [x] 11.1 Settings screen carrying every option in `config.js`, with defaults checked against it line by line
- [x] 11.2 Date filter rules: editor, and enforcement in target resolution with an unknown date failing closed
- [x] 11.3 The six remaining ban sources, and the author list's combined unblock-and-follow actions
- [x] 11.4 Bulk operations read the account's own lists at run time rather than our synced copy
- [x] 11.5 Removing title bans reads the title-ban list (`r=i`), not the blocked-user list
- [x] 11.6 The date-filtered run asks its direction and refuses without an enabled rule
- [x] 11.7 Operations are queued rather than discarded when one is already running
- [x] 11.8 Finished runs are recorded, so history has something to show
- [x] 11.9 An operations screen: running, queued and finished, with pause, resume and stop
- [x] 11.10 Verified on a real device: sync resumes from its cursor (page 177 of a 9,624-user list) with live page and count
- [ ] 11.11 Telemetry sender — BLOCKED: needs the shared API key, which belongs in BuildConfig and is deliberately absent from the repo, plus the 24-field Action payload contract
- [ ] 11.12 Title bans as a fourth synced list, so the count is visible before a run
- [x] 11.13 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 12. Guardrails

- [x] 12.1 Wiring tests: the Application supplies a WorkerFactory, every worker constructs, every dispatched component is declared, foreground permissions present
- [x] 12.2 Parity tests read the extension's own source: settings, defaults, ban sources, author-list actions
- [x] 12.3 Every Room table is touched by production code, so a silently unwritten one fails the build
- [x] 12.4 No layout asks the platform to uppercase Turkish
- [x] 12.5 The bridge payload format encodes defaults, with a test pinning the field that broke
- [x] 12.6 Each guardrail verified by breaking it first
- [ ] 12.7 End-to-end smoke against a local server — the one that would have caught the worker factory, the receiver, reconcile, the queue drop and the missing history write

## 10. Close out

- [x] 10.1 Run the full Android check: `cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew test :app:assembleDebug`
- [x] 10.2 Run the instrumented tests on an emulator
- [ ] 10.3 End-to-end on device: sync blocked → export CSV → import it as the author list → run a LIST unblock against two controlled accounts, then reverse it — NOT RUN: the only step that mutates a real account, so it waits on throwaway accounts rather than the owner's
- [x] 10.4 Confirm `git diff --stat -- frontend/app/ backend/` is empty
- [ ] 10.5 Run `openspec validate android-lists-and-csv` clean, then `openspec archive android-lists-and-csv`
