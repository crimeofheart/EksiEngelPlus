## Why

One version number ships three artifacts, so every release note has to say which
of them it is about. Three surfaces answered that question three different ways,
and none of them well.

**The extension's welcome page said it in prose.** 0.1.8's entire note was "Bu
sürümde eklentide bir değişiklik yok. Android uygulamasında ... giderildi." The
reader has to parse a sentence to find out whether the release concerns them, and
there is no way to state "nothing changed here" except by remembering to write
it — which means silence is ambiguous between "nothing changed" and "nobody
wrote anything".

**The Android screen answered by omission.** `ReleaseNotes.kt` simply had no
entry for a version with no Android-visible change, so the app showed its generic
fallback for releases that did have something to say — just not about the app.

**The website answered not at all.** `docs/changelog.json` is hand-maintained and
stops at 2.6.0 (2023). Four Ekşi Engel releases (2.7, 3.0, 3.1, 3.2) and every
single 0.1.x are missing from it. Updating it was a separate act from writing the
note, so it never happened.

`docs/changelog.txt` is a fourth record — the original development log, still
carrying a user-facing version history that nothing reads.

## What Changes

- Key each version by platform in `changelog.js`: `[]` is an explicit "no changes
  here this time", an omitted key means the platform did not exist yet.
- Both surfaces render both sections, each leading with its own platform. A user
  on one still wants to know the other got the fix — it is the same release, and
  hiding it makes the two clients look like they diverged.
- `ReleaseNotes.kt` mirrors the shape and now carries every shipped version.
  `ReleaseNotesTest` gains a copy guard: for the version being shipped, every
  platform section must match `changelog.js` word for word, both directions.
- Generate `docs/changelog.json` from `changelog.js` plus a new
  `docs/changelog.legacy.json`, via `npm run changelog`. `npm run check` fails
  when it is stale.
- Recover 2.7.0, 3.0.0, 3.1.0 and 3.2.0 from `changelog.txt` into the legacy
  file, and mark `changelog.txt` as an archive.
- Date each release in `changelog.js`. `npm run version:*` stamps it, and an
  undated version is deliberately withheld from the website.

## Non-goals

- No single generated source shared by all three. `ReleaseNotes.kt` stays a
  hand-kept mirror rather than becoming a build product: generating it would
  couple the Android build to the `frontend/` tree, and the test makes the
  duplication safe for the only version where it matters.
- The legacy entries are not migrated into `changelog.js`. They are Ekşi Engel's
  releases, in a different numbering and format, and folding them in would put
  English badge-formatted text into a Turkish user-facing data file.
- `changelog.txt`'s development history and TODO backlog are not deleted or
  moved. Nothing else records them.
- No redesign of the website page beyond the two badges it needs.

## Impact

- Affected specs: `release-notes` (new)
- Affected code: `frontend/app/assets/js/changelog.js`, `welcome.js`,
  `assets/html/welcome.html`, `scripts/ext.mjs`, `package.json`;
  `android/feature/settings/.../ReleaseNotes.kt`, `ReleaseNotesActivity.kt`,
  `ReleaseNotesTest.kt`; `docs/changelog.json` (now generated),
  `docs/changelog.legacy.json` (new), `docs/releaseNotes.html`,
  `docs/changelog.txt`
- `frontend/app/` runtime code IS touched: `changelog.js` and `welcome.js` are
  shipped extension files. The change is confined to the welcome page's release
  notes; no operation, scraping or settings path is involved, and
  `npm run check && npm run package` pass.
- `frontend/app/package.json` gains `"type": "module"`, so Node stops re-parsing
  `changelog.js` as CommonJS on every `ext.mjs` run. The browser never reads that
  file and it is excluded from the store zips.
