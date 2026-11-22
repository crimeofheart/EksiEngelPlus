## Why

Release CI built, signed and attached all four artifacts to every GitHub Release,
which kept the Android upload key in repository secrets that nobody could read
back. The maintainer could not sign a build without CI, could not verify which key
CI used, and published binaries they did not need on GitHub. The app is going to
Google Play, uploaded by hand, and future updates depend on keeping that key.

## What Changes

- `release.yml` no longer builds or attaches anything: a `v*` tag is checked
  against the seven recorded versions and gets a GitHub Release with notes only.
  The `extension`, `android` and `prune` jobs and the Play-submission input go.
- `tools/build-release.sh` builds the two browser zips, the AAB (`play`) and the
  APK (`github`) on the maintainer's machine into `frontend/publish/dist/`, refuses
  a dirty tree or an untagged HEAD, and refuses any Android file not signed with the
  pinned upload certificate. `--upload-apk` publishes the APK on
  eksiengelplus.duzgun.org through `tools/set-min-version.sh --apk`.
- `android/app/build.gradle.kts` reads the signing config from
  `~/.gradle/gradle.properties` (`eksiengelplus.*`), the `ANDROID_*` environment
  variables remaining a fallback. Keystore and properties are age-encrypted in
  chezmoi.
- `/download/eksiengelplus.apk` redirects to the newest uploaded APK; the landing
  page and the Ekşi first entry link there instead of to GitHub Releases, whose
  existing assets were deleted.
- `tools/set-min-version.sh --release` goes: GitHub no longer has the APK.

`frontend/app/` runtime code is **not** touched; `scripts/ext.mjs` only prints the
next step after `release`.

## Non-goals

- Uploading to the stores from a script. All three uploads stay manual.
- Changing `check.yml`: it still builds and tests every push and keeps its short-lived
  unsigned CI artifacts.
- Deleting the now-unused `ANDROID_*` repository secrets.

## Capabilities

### New Capabilities
- `release-builds`: release artifacts are built and signed locally under a pinned key.

### Modified Capabilities
None.

## Impact

`.github/workflows/release.yml`, `tools/build-release.sh`, `tools/set-min-version.sh`,
`android/app/build.gradle.kts`, `backend/.../api/update_policy.py` and `urls.py`, the
landing template, `docs/eksisozluk/first-entry.txt`, `CLAUDE.md`, `openspec/config.yaml`.
