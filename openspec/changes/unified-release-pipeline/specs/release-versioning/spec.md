# release-versioning

Binds: extension (Chrome + Firefox) and Android. One product version covers all three artifacts.

## ADDED Requirements

### Requirement: One version across seven locations

The product version SHALL be recorded identically in exactly seven locations, and
tooling SHALL refuse to operate when they disagree.

The seven are the six that exist today —
`frontend/app/package.json`, `frontend/app/package-lock.json` (both the top-level
`version` and `packages[""].version`), `frontend/app/manifest.chrome.json`,
`frontend/app/manifest.firefox.json`, and the generated `frontend/app/manifest.json`
when present — plus the one added here: `android/version.json`, which carries a
single top-level `{"version": "x.y.z"}`.

`android/version.json` is JSON with a top-level `version` field specifically so that
`versionsIn()` (`frontend/app/scripts/ext.mjs:99-109`) and `rewriteVersion()`
(`ext.mjs:118-125`) handle it unmodified. The implementation change is confined to
`versionFiles()` (`ext.mjs:88-96`).

#### Scenario: All locations agree

- **WHEN** `npm run check` runs in `frontend/app` and every version location holds the same `x.y.z`
- **THEN** it exits 0 and reports the version and the number of locations inspected

#### Scenario: A location has drifted

- **WHEN** `android/version.json` records `0.1.7` while the manifests record `0.1.6`
- **THEN** `npm run check` exits non-zero naming the mismatching locations
- **AND** `npm run version:*`, `npm run package`, and `npm run release` all refuse to run

#### Scenario: A location is not valid semver

- **WHEN** any location holds a value that is not `x.y.z`
- **THEN** `npm run check` exits non-zero identifying that location

#### Scenario: The generated manifest is absent

- **WHEN** `frontend/app/manifest.json` does not exist, as in a fresh clone
- **THEN** `npm run check` inspects the remaining six locations and exits 0 if they agree

### Requirement: A bump moves every location atomically

`npm run version:patch|minor|major` SHALL rewrite all present version locations in a
single invocation, leaving none behind.

#### Scenario: Patch bump

- **WHEN** the version is `0.1.6` and `npm run version:patch` runs
- **THEN** all seven locations read `0.1.7`
- **AND** `npm run check` exits 0

#### Scenario: Bump refuses on a dirty version state

- **WHEN** the locations disagree before the bump
- **THEN** the bump refuses and rewrites nothing

### Requirement: Android versionCode is derived, never authored

`android/app/build.gradle.kts` SHALL read `versionName` from `android/version.json`
and compute `versionCode` as `major * 10000 + minor * 100 + patch`. No file SHALL
record `versionCode` as a literal.

This guarantees the strictly-increasing integer Google Play requires without adding
an eighth thing to keep in lockstep.

#### Scenario: Version code derivation

- **WHEN** `android/version.json` holds `0.1.7`
- **THEN** Gradle reports `versionName = "0.1.7"` and `versionCode = 107`

#### Scenario: Version code increases with the version

- **WHEN** the version moves `0.1.7` → `0.2.0` → `1.0.0`
- **THEN** `versionCode` moves `107` → `200` → `10000`, strictly increasing

#### Scenario: Malformed version file

- **WHEN** `android/version.json` is absent, unparseable, or not `x.y.z`
- **THEN** the Gradle configuration phase fails with a message naming the file

### Requirement: Version tooling runs without a JDK or Android SDK

`npm run check`, `npm run version:*`, and `npm run package` SHALL depend only on Node
and the repository contents. Reading `android/version.json` is a JSON file read, not
a Gradle invocation.

`frontend/app` has zero npm dependencies and `npm install` is never required; this
requirement preserves that property.

#### Scenario: Extension development with no Android toolchain

- **WHEN** a contributor with no JDK and no Android SDK on `PATH` runs `npm run check` and `npm run package` in `frontend/app`
- **THEN** both succeed and produce the two store zips

### Requirement: A release tag matches the recorded version

`npm run release -- <patch|minor|major>` SHALL bump every location, commit as
`chore: release vX.Y.Z`, and tag `vX.Y.Z`. It SHALL refuse on a dirty tree or an
existing tag.

The release commit carries subject and body only — no `Co-Authored-By` or other
attribution trailer — so the commit stays byte-identical to what the tooling generates.

#### Scenario: Tag and version agree at build time

- **WHEN** the workflow runs for tag `v0.1.7`
- **THEN** it asserts `frontend/app/package.json` reads `0.1.7` before building anything

#### Scenario: Tag pushed from the wrong commit

- **WHEN** tag `v0.1.7` points at a commit whose recorded version is `0.1.6`
- **THEN** the workflow fails before producing any artifact
