# Unified release pipeline

## Why

A third artifact is coming: a native Android app in `android/`. Today the release
machinery assumes exactly two — `frontend/app/scripts/ext.mjs` asserts six version
locations agree and refuses to run on a mismatch, and `.github/workflows/extension-release.yml`
turns a `v*` tag into two store zips.

If the Android app lands without this, its version drifts from the extensions
immediately and there is no single "release" of EksiEngelPlus — just two unrelated
publishing rituals. Retrofitting lockstep after versions have already diverged is
strictly harder than establishing it before the first Android commit exists.

This change is deliberately landed **before** the Android port, and is worth landing
even if the port is deferred: it costs 2–3 days and makes the three-artifact
invariant true from the start.

## What Changes

- **New file `android/version.json`** — `{"version": "x.y.z"}`. Chosen because it is
  JSON with a top-level `version` field, so `versionsIn()` (`ext.mjs:99-109`) reads it
  and `rewriteVersion()` (`ext.mjs:118-125`) rewrites it with **no changes to either
  function**. The entire tooling edit is one path appended to `versionFiles()`
  (`ext.mjs:88-96`) plus a `REPO_ROOT` constant, since the file sits outside `APP_DIR`.
- **Six version locations become seven.** `npm run check`, `npm run version:*`, and
  `npm run release` all extend for free and keep their refuse-on-mismatch guarantee.
- **Minimal Gradle root at `android/`** — settings, version catalog, and an `app`
  module that builds an empty signed AAB and APK. A placeholder, not the app. It
  exists so the release pipeline is provably real rather than aspirational.
- **`versionCode` is derived, never authored** — `major*10000 + minor*100 + patch`
  (0.1.7 → 107), computed in `app/build.gradle.kts` from `android/version.json`.
  Monotonic and deterministic; Play's strictly-increasing-integer requirement is
  satisfied without a second thing to bump.
- **`extension-release.yml` → `release.yml`** — same `v*` trigger, now four jobs:
  `verify` → (`extension` ‖ `android`) → `release`, attaching four files (2 zips,
  AAB, APK) to one GitHub Release.
- **`extension-check.yml` → `check.yml`** — `verify` (version consistency) runs on
  every push; the `extension` and `android` build jobs stay path-filtered to
  `frontend/app/**` and `android/**`.
- **Play submission is opt-in per release**, gated behind a `workflow_dispatch`
  input. Building all three artifacts every tag is free; sending a bit-identical
  APK through Play review because someone fixed extension CSS is not.

**Not breaking.** The `cd frontend/app && npm run release -- patch` ritual documented
in `CLAUDE.md` is unchanged, and so are the two workflows' tag trigger and outputs.

## Capabilities

### New Capabilities

- `release-versioning`: the single source of truth for the product version, which
  files record it, how they are kept in lockstep, and how the Android `versionCode`
  is derived from it.
- `release-artifacts`: what a `v*` tag produces, which jobs build what, what is
  attached to the GitHub Release, and where store submission sits relative to
  artifact publication.

### Modified Capabilities

None. `openspec/specs/` is empty — this is the repo's first change.

## Impact

**Modified**
- `frontend/app/scripts/ext.mjs` — `versionFiles()` gains one path; new `REPO_ROOT`
  constant. No change to `versionsIn`, `rewriteVersion`, packaging, or manifest
  switching.
- `.github/workflows/extension-check.yml` → `check.yml` (renamed, restructured).
- `.github/workflows/extension-release.yml` → `release.yml` (renamed, restructured).
- `CLAUDE.md` — the "Versioning and packaging" section says "six places"; becomes
  seven, plus a note that the APK is built by CI, not by `npm run package`.
- `.gitignore` — Gradle build output.

**New**
- `android/version.json`, `android/settings.gradle.kts`, `android/build.gradle.kts`,
  `android/gradle/libs.versions.toml`, `android/gradlew` + wrapper,
  `android/app/build.gradle.kts`, minimal `AndroidManifest.xml`.

**Untouched**
- All of `frontend/app/assets/**` — no extension runtime code changes. `jsdom.js`
  stays and stays required.
- `backend/**`.

**New CI secrets required**
`ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`,
`ANDROID_STORE_PASSWORD`, and later `PLAY_SERVICE_ACCOUNT_JSON`. Until the keystore
secrets exist the `android` job must degrade to an unsigned debug build rather than
failing the release — otherwise this change blocks extension releases, which is
exactly the coupling it is meant to avoid.

## Non-goals

- Any Android application code. The `app` module is an empty shell; the real port is
  `android-spike` and its successors.
- Automating Chrome Web Store or addons.mozilla.org uploads. Those stay manual.
- Making the three stores publish simultaneously — impossible. Chrome review is
  hours-to-days, AMO minutes-to-days, Play adds review plus a 12-tester × 14-day gate.
  This change guarantees the *artifacts* are built together and versioned identically.
- Moving or renaming `ext.mjs`. It becomes slightly misnamed; the documented ritual
  is worth more than the rename.
- Changing the version line. The next release is `0.1.7`, and the Android app's first
  Play submission will carry a `0.1.x` version.
