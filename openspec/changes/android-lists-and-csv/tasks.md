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

- [ ] 4.1 Create `ListsActivity` + layout — three rows (blocked, muted, followed), each with count, last-full-refresh time, a partial badge, and refresh/stop
- [ ] 4.2 Create `ListsViewModel` combining `RelationUserDao.countOf`, `ListSyncStateDao.observe` and `OperationCheckpointDao` running-state into one UI state `Flow`
- [ ] 4.3 Disable a list's refresh while an operation is `RUNNING`, per the design's request-pressure mitigation
- [ ] 4.4 Add the overflow-menu entry in `BrowserActivity` that opens `ListsActivity`, and register the Activity in `AndroidManifest.xml`
- [ ] 4.5 Verify on device/emulator: counts rise live during a sync, the partial badge appears when a sync is stopped mid-run
- [ ] 4.6 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 5. CSV export

- [ ] 5.1 Add an export action per list that launches `ActionCreateDocument("text/csv")` with the suggested filename
- [ ] 5.2 On result, join the list rows against `registration_date_cache` and stream through `CsvCodec.writeExport` into the SAF `OutputStream` off the main thread
- [ ] 5.3 Treat a null `Uri` (dismissed picker) as a no-op; surface write failures as a message, not a crash
- [ ] 5.4 Verify a real export opens in a spreadsheet and imports into the extension's author list page unchanged
- [ ] 5.5 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 6. Author list import and editing

- [ ] 6.1 Create `AuthorListActivity` + layout — a multiline paste field, a row count, Save / Append / Clear, and an Import file action
- [ ] 6.2 Wire `ActionOpenDocument` for `text/csv` and `text/plain`, reading the stream into `CsvCodec.parseImport`
- [ ] 6.3 Add `AuthorListDao.replaceAll(rows)` as a `@Transaction` doing `clear()` then `upsertAll()`, so a failed import leaves the previous list intact
- [ ] 6.4 Seed `registration_date_cache` from parsed dates in the same transaction
- [ ] 6.5 Report the outcome: rows imported, rows skipped, dates recognised
- [ ] 6.6 Instrumented test: replace-mode import that throws partway leaves the old list present and unmodified
- [ ] 6.7 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 7. LIST operations read the saved list

- [ ] 7.1 Change `OpsModule`'s `BanSource.LIST` factory to resolve targets from `AuthorListDao.getAll()` once at operation start
- [ ] 7.2 End a LIST operation immediately, with a clear result, when the saved list is empty
- [ ] 7.3 Add a "run on this list" entry point from `AuthorListActivity` that enqueues a LIST operation
- [ ] 7.4 Test that a resumed LIST run continues against its start-time target set, not a re-read of the table
- [ ] 7.5 Verify the extension is untouched: `cd frontend/app && npm run check && npm run package`

## 8. Close out

- [ ] 8.1 Run the full Android check: `cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew test :app:assembleDebug`
- [ ] 8.2 Run the instrumented tests on an emulator
- [ ] 8.3 End-to-end on device: sync blocked → export CSV → import it as the author list → run a LIST unblock against two controlled accounts, then reverse it
- [ ] 8.4 Confirm `git diff --stat -- frontend/app/ backend/` is empty
- [ ] 8.5 Run `openspec validate android-lists-and-csv` clean, then `openspec archive android-lists-and-csv`
