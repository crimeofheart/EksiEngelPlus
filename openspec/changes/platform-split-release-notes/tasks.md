## 1. Split the source by platform

- [x] 1.1 Restructure `changelog.js`: each version keyed `app` / `extension`, with `[]` meaning "no changes here" and an omitted key meaning "did not exist yet"
- [x] 1.2 Replace `getNotes()` with `getSections(version, order)`; add `noChangesNote` and `platformLabels`
- [x] 1.3 `welcome.js`: render a bold platform heading per section, extension first; add the `.note-platform` rule to `welcome.html`
- [x] 1.4 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 2. Mirror it on Android

- [x] 2.1 `ReleaseNotes.kt`: same shape, `Platform` enum, `Section`, `forVersion()` returning sections app-first
- [x] 2.2 Backfill every shipped version, including the ones previously omitted for having no Android change
- [x] 2.3 `ReleaseNotesActivity`: bold heading spans per section in the single scrolling TextView
- [x] 2.4 `ReleaseNotesTest`: cover the three states — real notes, explicit none, platform absent — plus both fallbacks
- [x] 2.5 Add the copy guard: parse the shipping version's platform lists out of `changelog.js` and assert word-for-word equality both directions
- [x] 2.6 Verify the guard is not vacuous — corrupt one string in `changelog.js`, confirm the test fails, restore
- [x] 2.7 `./gradlew :feature:settings:test`

## 3. Generate the website's copy

- [x] 3.1 Recover 2.7.0, 3.0.0, 3.1.0 and 3.2.0 from `changelog.txt` into `docs/changelog.legacy.json`, alongside the existing eleven entries
- [x] 3.2 Add `cmdChangelog()` to `ext.mjs`: import `changelog.js` as a module rather than parsing it, emit platform badges, append the legacy tail without sorting
- [x] 3.3 Wire the staleness check into `cmdCheck()`, and add `npm run changelog`
- [x] 3.4 Verify the staleness check is not vacuous — corrupt `docs/changelog.json`, confirm `npm run check` fails, regenerate
- [x] 3.5 `docs/releaseNotes.html`: accept `[Eklenti]` / `[Uygulama]` in the badge regex, and give them their own colours and width
- [x] 3.6 Mark `changelog.txt` as an archive, pointing at the new source and keeping its development history and TODO backlog
- [x] 3.7 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 4. Date releases, and publish by dating

- [x] 4.1 Add `date` to every shipped version, from the `v*` tags; 0.1.0 predates them and takes its date from `changelog.txt`
- [x] 4.2 Exclude undated entries from `docs/changelog.json`, so notes written ahead of a release stay off the live site
- [x] 4.3 `stampReleaseDate()` in `cmdVersion`: date the bumped version, refuse to move a date already set, mutate the loaded module so the regeneration that follows sees it
- [x] 4.4 Include `changelog.js` and `docs/changelog.json` in the release commit
- [x] 4.5 Test the stamp in isolation: right block, no collateral edits, second stamp refused, dots escaped rather than treated as wildcards
- [x] 4.6 Document the whole flow in `CLAUDE.md`
- [x] 4.7 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 5. Verification

- [ ] 5.1 Load the welcome page in Chrome and Firefox and confirm both sections render, including the "no changes" line
- [ ] 5.2 Open sürüm notları on device and confirm the app's section leads
- [ ] 5.3 Open `docs/releaseNotes.html` in a browser and confirm all 23 releases render, the two new badges included
- [x] 5.4 Exercise the bump path end to end on a scratch copy: `version patch` bumped 7 locations, dated 0.1.9, regenerated to 24 releases, `check` passed afterwards, and a second `changelog` run was a no-op
- [ ] 5.5 Run `openspec validate platform-split-release-notes --strict` clean, then `openspec archive platform-split-release-notes`
