## 1. Bound the registration-date cache

- [x] 1.1 Add `expiredCount(minFetchedAt)` and `clear()` to `RegistrationDateCacheDao`
- [x] 1.2 Call `trimExpired` once at the start of an operation run in `OperationWorker`, before anything reads the cache
- [x] 1.3 Unit-test that a prune deletes expired rows and leaves fresh ones, so the prune can never cost a refetch — `EksiDatabaseTest.expiredCountAgreesWithWhatTrimDeletes`, which also pins the two predicates together
- [x] 1.4 Verify `trimExpired` and `size` now have production callers — the defect this change exists for was that they did not. Turned into a standing check: `ParityTest.maintenance methods are reachable from production code`

## 2. Maintenance section in Settings

- [x] 2.1 Add a maintenance section to `activity_settings.xml`: cache total, expired count, database size, and two buttons
- [x] 2.2 Read the counts and the database file size into `SettingsActivity`, off the main thread — on resume rather than observed; the numbers change from a worker, not from this screen
- [x] 2.3 Clear cache: unconditional, refreshes the counts in place
- [x] 2.4 Clear stored data: confirm first, name what is deleted, refuse while an operation is running
- [x] 2.5 Decide and document exactly which tables "stored data" covers, and leave configuration out of it — `clearAllTables()`, so a table added later is covered without anyone extending a list; config lives in DataStore and survives
- [x] 2.6 Unit-test the refusal, so a running operation cannot be deleted out from under — `MaintenanceTest`, 5 cases
- [x] 2.7 Format the size for a Turkish locale rather than concatenating a number and "MB" — `Formatter.formatShortFileSize`
- [x] 2.8 Include the WAL and SHM files in the size, or the figure disagrees with the platform's own storage screen after a large sync

## 3. Help screen

- [x] 3.1 Add `HelpActivity` to `feature:settings`, reachable from Settings
- [x] 3.2 Port the Kullanım Kılavuzu from `faq.html:427-570`, rewritten for this app's navigation
- [x] 3.3 Cover: acting from the browsing UI, the lists screen, bulk operations, the date filter and its rules, the author list and its CSV format
- [x] 3.4 State the date-filter divergence explicitly — on by default here, and every enabled rule must pass
- [x] 3.5 All copy in `strings.xml`, no screenshots, text that scales
- [x] 3.6 Declare the activity in the module manifest — a screen reached by intent and declared nowhere is the exact defect `android-device-hardening` was written for

## 4. Release notes

- [x] 4.1 Add release notes keyed by version, with a fallback line, ported from `changelog.js`
- [x] 4.2 Record the last version shown in the identity store — `Identity.lastNotesVersion`
- [x] 4.3 Show the notes on first launch after install or upgrade, once per version, dismissible
- [x] 4.4 Unit-test: fallback when the version has no entry, and when the version is blank — `ReleaseNotesTest`
- [x] 4.5 `claimReleaseNotes` is read-and-write in one `updateData`, so a recreate cannot show the notes twice and a blank stored version means a fresh install sees them
- [x] 4.6 Guard against drift — **narrowed from the original task.** The first draft demanded an entry for every version in `changelog.js`. That is wrong: one version number covers three artifacts, and a release whose whole content was a Firefox packaging fix has nothing to tell an Android user, so the rule would have produced filler notes. `ReleaseNotesTest` instead asserts the version in `android/version.json` has notes of its own, which is the case that actually matters — shipping with the fallback showing.
- [ ] 4.7 Add an entry to `ReleaseNotes` whenever `npm run version:*` bumps, the same obligation `changelog.js` already carries. The test fails the build if it is forgotten.

## 5. Verification

- [ ] 5.1 `cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:lintDebug`
- [ ] 5.2 Confirm `frontend/app/` is untouched: `git diff --stat -- frontend/app/`
- [ ] 5.3 Verify on device: the counts are real, clearing the cache zeroes them, clearing data is refused mid-run
- [ ] 5.4 Verify on device: the notes appear once after an upgrade and not on the next launch
- [ ] 5.5 Run `openspec validate android-maintenance-and-help` clean, then `openspec archive android-maintenance-and-help`
