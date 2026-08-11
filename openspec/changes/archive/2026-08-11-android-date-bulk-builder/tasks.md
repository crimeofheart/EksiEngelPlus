## 1. Put the date rule where a request can carry it

- [x] 1.1 Move `DateCriteria`, `DateFilterRule` and `DateFilter` from `core/datastore/Config.kt` into `core/model/DateFilter.kt`, unchanged; add the serialization plugin to `core:model`, which needs it for `DateFilterRule`
- [x] 1.2 Update the six files that reference them — `Config.kt`, `Stores.kt`, `DateFilterTest`, `ConfigTest`, `OperationWorker`, `SettingsActivity`. `core:datastore` already exposes `core:model` with `api`, so only the package changes
- [x] 1.3 `./gradlew testDebugUnitTest` — the move must be provably behaviour-free before anything is built on it

## 2. Model the three choices

- [x] 2.1 Add `core/model/DateBulk.kt` with `DateBulkSource` (3) and `DateBulkAction` (8), named exactly as `enums.js:102-117`
- [x] 2.2 Give `DateBulkAction` the mode/target/then triple from the spec table as properties, so the mapping lives with the enum and not in an activity
- [x] 2.3 Give `DateBulkSource` the `TargetType` it reads, and `null` for `AUTHOR_LIST` — the absence is what marks it as the source that resolves nicks instead
- [x] 2.4 Unit-test both mappings against the spec table

## 3. Teach the engine the source and the criterion

- [x] 3.1 Add `relationListOf: TargetType? = null` and `dateRule: DateFilterRule? = null` to `OperationRequest`; both defaulted, so a checkpoint written before this change still deserialises
- [x] 3.2 `OpsModule.kt:128` — pass `request.relationListOf ?: TargetType.USER` to `RelationListTask` instead of the hardcoded `TargetType.USER`, fixing the muted source reading the blocked list
- [x] 3.3 `OperationWorker.kt:411-426` — prefer `request.dateRule` over `config.dateFilterRules` when it is set, still resolved once for the run
- [x] 3.4 Test that a `MUTED_USERS` request scrapes `relationType=u`, which is the defect this fixes and the one no test caught
- [x] 3.5 Test that `dateRule` overrides the saved rules, and that a request without one is unaffected

## 4. The chooser

- [x] 4.1 Add `dialog_date_bulk.xml`: source spinner, criterion spinner, a day-count field with a unit spinner (gün/ay/yıl), a date field, and an action spinner
- [x] 4.2 Show the day-count row for NEWER_THAN/OLDER_THAN and the date row for BEFORE_DATE/AFTER_DATE, driven by `DateCriteria.usesDays`, which already exists for this
- [x] 4.3 Normalise months and years to days on the way into the request; the unit is a data-entry convenience and must not reach `DateFilterRule`
- [x] 4.4 Replace `ListsActivity.askDateBasedAction():219` with the dialog; delete the three `bulk_date_*` strings it used
- [x] 4.5 `ListsViewModel.runDateBased` takes the composition: build the request from `DateBulkSource` and `DateBulkAction`, and for `AUTHOR_LIST` resolve nicks from `AuthorListRepository` and send `BanSource.LIST` with them
- [x] 4.6 Keep the existing refusal when there is no criterion, now keyed on the dialog's own value rather than on settings
- [x] 4.7 Turkish strings for three sources, four criteria, three units and eight actions

## 5. Remember the composition

- [x] 5.1 Add a `DateBulkPrefs` serializable to `EksiConfig`, defaulted to muted users / OLDER_THAN / 3650 days / `SESSIZDEN_CIKAR` per `config.js:58-66`; a defaulted field needs no `CURRENT_VERSION` bump and no migration step
- [x] 5.2 Save on start, restore on open
- [x] 5.3 Test that restoring leaves `dateFilterRules` untouched

## 6. Hold the parity down

- [x] 6.1 Add a `ParityTest` case asserting every `DateBulkSource` and `DateBulkAction` name in `enums.js` has a Kotlin counterpart
- [x] 6.2 `./gradlew :app:assembleDebug testDebugUnitTest lintDebug`
- [x] 6.3 `cd frontend/app && npm run check && npm run package`

## 7. Verification

- [x] 7.1 Verify on device that the muted source now acts on muted users — the check is that the run's total matches the muted count, not the blocked count
- [x] 7.2 Verify the author-list source runs the stored list and reports as a list run
- [x] 7.3 Verify a combined action follows only the users whose undo succeeded
- [x] 7.4 Verify BEFORE_DATE with a calendar date, which is the branch the three old presets could never reach
- [x] 7.5 Verify settings' `dateFilterRules` are unchanged after a run with a different criterion
- [x] 7.6 Verify a run paused and resumed after editing settings keeps its own criterion
- [x] 7.7 Run `openspec validate android-date-bulk-builder` clean, then `openspec archive android-date-bulk-builder`

## 8. The saved rules only narrow restrictions

Found while this change was still open, and folded in here rather than specced
separately: it is the same distinction as the per-run criterion, seen from the
other side.

- [x] 8.1 Confirm against the extension where the saved rules are actually applied — `background.js:772` (FAV), `:836` (FOLLOW), `:908` (TITLE), all blocks; `UNDOBANALL`, `UNMUTEALL` and the migration reach no filtering code
- [x] 8.2 Add `OperationRequest.addsRestriction`: `BanMode.BAN` with `TargetType` USER, MUTE or TITLE. Following is not a restriction — it adds a relation but takes nothing away from the person it names
- [x] 8.3 `activeDateRules` returns the saved rules only for such a run; a run's own `dateRule` still wins outright and applies whatever the direction, since the extension's default composition is itself an unmute
- [x] 8.4 Test the defect directly: `UNDOBANALL` under the default ten-year rule must be gated by nothing
- [x] 8.5 Test the surrounding cases — blocking/muting/title blocking still narrowed, following not, migration not, `BLOCK_MUTED_USERS` still narrowed because it blocks
- [x] 8.6 Correct the two comments that asserted the old behaviour: `EksiConfig.enableDateFilter` and `ParityTest.deliberatelyDifferentDefault`
- [x] 8.7 Correct `help_date_filter_body`, which told the user the rules narrow "bir işlem" — any operation
- [x] 8.8 Release note on 0.2.0, both surfaces, and regenerate `docs/changelog.json`
- [x] 8.9 `./gradlew :app:assembleDebug test testDebugUnitTest lintDebug` and `cd frontend/app && npm run check`
- [x] 8.10 Verify on device that "tüm engelleri kaldır" now clears a decade-old account, which is the case that was silently skipped
- [x] 8.11 Verify a blocking run still spares one, so the fix did not disarm the filter in the direction it belongs
