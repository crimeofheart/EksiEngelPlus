# Design — unified release pipeline

## Context

`frontend/app/scripts/ext.mjs` (365 lines, zero dependencies) is the whole extension
toolchain: manifest switching, version lockstep, zipping, and the release ritual. Its
version half is three small functions:

- `versionFiles()` (`ext.mjs:88-96`) — returns the list of files that record a version,
  conditionally including the generated `manifest.json`.
- `versionsIn(file)` (`ext.mjs:99-109`) — `readJson`, then collects `json.version` and
  `json.packages[""].version`, failing if neither exists.
- `rewriteVersion(file, current, next)` (`ext.mjs:118-125`) — regex text substitution
  on `"version"\s*:\s*"<current>"`, deliberately not re-serializing, so tab- and
  space-indented manifests are not reformatted. The leading quote in the pattern is
  what keeps `strict_min_version` and `lockfileVersion` from matching.

`checkVersions()` (`ext.mjs:242-265`) flat-maps `versionsIn` over `versionFiles()`,
asserts every value is semver, asserts they are all distinct-of-one, and returns it.

The workflows are thin: `extension-check.yml` is path-filtered to `frontend/app/**`;
`extension-release.yml` triggers on `v*`, asserts `GITHUB_REF_NAME` minus the `v`
equals `package.json`'s version, runs `check`, runs `package`, and attaches the zips.

Constraint that shapes everything below: `frontend/app` has zero npm dependencies and
must stay buildable with no JDK and no Android SDK.

## Goals / Non-Goals

**Goals**

- Extend version lockstep to Android with the smallest possible change to proven code.
- Make a `v*` tag produce all three artifacts, or none.
- Keep an unconfigured Android keystore from ever blocking an extension release.
- Land before the Android port so the invariant is never retrofitted.

**Non-Goals**

- Android application code. The `app` module is an empty shell.
- Automating Chrome Web Store or AMO uploads.
- Simultaneous publication across three stores — not achievable.
- Renaming or relocating `ext.mjs`.

## Decisions

### `android/version.json` over `gradle.properties`

The Android version could live in `gradle.properties` (`VERSION_NAME=0.1.7`), in
`app/build.gradle.kts` as a literal, or in a JSON file.

JSON wins because `versionsIn()` and `rewriteVersion()` then need **zero changes**.
A JSON file with a top-level `version` key is already exactly what those two functions
consume — `readJson` parses it, `json.version` is found, and the rewrite regex matches
`"version": "0.1.7"` the same way it matches it in `package.json`. The entire
implementation is one path appended to `versionFiles()` plus a `REPO_ROOT` constant,
since the file sits outside `APP_DIR`.

`gradle.properties` would have required a second parser branch in `versionsIn` and a
second substitution pattern in `rewriteVersion` — more code, and both functions are
load-bearing for five other files.

Gradle reads JSON at configure time via `groovy.json.JsonSlurper`, which is on the
Gradle classpath already. No plugin, no dependency.

*Alternative rejected:* deriving the Android version from `package.json` directly, with
no `android/version.json` at all. Tempting — one fewer file — but it makes the Android
build reach into `frontend/app`, coupling the two trees in the wrong direction and
breaking any future extraction of `android/` into its own repo. An explicit file that
tooling keeps in sync is the looser coupling.

### `versionCode` derived arithmetically

`major * 10000 + minor * 100 + patch`. `0.1.7` → `107`, `1.0.0` → `10000`.

Play requires a strictly increasing integer per upload. Authoring it by hand adds an
eighth thing to keep in lockstep and a new failure mode (a forgotten bump silently
rejects the upload after CI has already succeeded). Deriving it means the semver bump
is the only action, and monotonicity is structural.

Ceiling: minor and patch must stay below 100. At the current release cadence that is
decades away, and the Gradle helper fails loudly if either exceeds 99 rather than
silently producing a colliding code.

*Alternative rejected:* CI run number or commit count as `versionCode`. Monotonic, but
it decouples the code from the version, so a re-run of a failed release job produces a
different code for identical bytes, and local builds can't reproduce a store artifact.

### Four jobs, not one

`verify` → (`extension` ‖ `android`) → `release`.

Splitting `verify` out means the cheap check (Node, a few JSON reads) gates the
expensive ones (Gradle, Android SDK provisioning) rather than running redundantly
inside each. Splitting `extension` from `android` lets them run concurrently and makes
a failure attributable at a glance. A single `release` job depending on both is what
enforces "four files or no release" — `softprops/action-gh-release` runs once, with
everything already downloaded as artifacts.

### Degrade to unsigned rather than fail when secrets are missing

The `android` job checks for `ANDROID_KEYSTORE_B64` and, when absent, builds
`assembleDebug` and logs that the artifact is unsigned.

This is the decision most likely to look wrong later, so the reasoning is worth
recording: the entire point of this change is to stop the three artifacts from
blocking each other. An extension hotfix that cannot ship because nobody has uploaded
a keystore yet would be a strictly worse situation than today, where the extension
release does not know Android exists. The failure mode we accept — a Release carrying
a debug APK — is visible, logged, and harmless, because Play submission is a separate
opt-in step that *does* fail hard without signing (see the spec).

### Play submission gated behind `workflow_dispatch`

Unconditional Play upload on every tag would send a bit-identical APK through review
whenever the extension gets a CSS fix. Play review is not free — it consumes calendar
time and, on a new personal developer account, interacts with the 12-tester × 14-day
gate.

`workflow_dispatch` input rather than a `-play` tag suffix, because a suffix would
fork the tag namespace we just deliberately unified, and `extension-release.yml`'s
existing `tag == version` assertion would need to learn to strip it.

### Rename the workflows

`extension-check.yml` → `check.yml`, `extension-release.yml` → `release.yml`. They are
no longer extension-specific. Tag- and push-triggered workflows are referenced by
filename nowhere outside `.github/`, so the rename is free. `ext.mjs` keeps its name
despite the same argument applying, because the `cd frontend/app && npm run release`
ritual is documented in `CLAUDE.md` and in the header comment of the release workflow.

## Risks / Trade-offs

**A contributor hand-edits `android/version.json`** → `npm run check` fails on the next
run and the release scripts refuse. Same protection the other seven locations have.

**Gradle configuration-phase JSON read fails on a malformed file** → fails loudly at
configure time naming the file, rather than producing a build with a wrong version.
Covered by a scenario in `release-versioning`.

**`minor` or `patch` exceeds 99, colliding `versionCode`** → the helper throws during
configuration. A collision would otherwise be discovered only at Play upload, after a
release had already been cut.

**The Android job's SDK provisioning makes releases slow** → acceptable; it runs
concurrently with the extension job, and releases are infrequent. Gradle caching via
`gradle/actions/setup-gradle@v4` keeps warm runs short.

**The empty `app` module rots** → it is exercised by every `check.yml` run on
`android/**` and every release, so a break surfaces immediately rather than at the
start of the port.

**Renaming workflows loses run history** → GitHub keys history by filename, so the old
runs detach. Cosmetic, one-time, and the alternative is permanently misleading names.

## Migration Plan

1. Land `android/version.json` at the current version (`0.1.6`) plus the Gradle
   skeleton, with no workflow changes. `npm run check` still passes on seven locations
   because the new file is not yet in `versionFiles()`.
2. Add the path to `versionFiles()`. Now seven. Verify `npm run check` passes and that
   hand-breaking `android/version.json` makes it fail.
3. Add `check.yml`; delete `extension-check.yml` in the same commit.
4. Add `release.yml`; delete `extension-release.yml` in the same commit.
5. Update `CLAUDE.md` — "six places" becomes seven, plus a note that the APK is a CI
   product and `npm run package` remains extension-only.
6. Exercise on a fork with a throwaway tag before tagging on `master`.

**Rollback:** steps 3–4 are pure workflow files; reverting the commit restores the
previous two. Step 2 is a one-line revert. `android/version.json` and the Gradle
skeleton are inert if unreferenced.

## Open Questions

- **Version floor for the Play launch.** Lockstep means the Android app's first
  submission carries `0.1.x`. Mechanically fine — Play only requires `versionCode` to
  increase. If a `1.0.0` launch is wanted, the bump happens across all seven locations
  in the release immediately before submission, not by decoupling the versions.
  Deferred to `android-play-release`.
- **Where the keystore lives.** Play App Signing means the upload key is the only
  secret CI needs, but it still has to be generated and stored. Deferred until the
  first real Android build exists.
