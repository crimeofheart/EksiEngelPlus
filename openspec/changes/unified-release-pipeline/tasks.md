## 1. Baseline

- [x] 1.1 Record the current version and the pre-change baseline: run `cd frontend/app && npm run check` and note the version and location count it reports; run `npm run package` and confirm both zips appear in `frontend/publish/dist/`
- [x] 1.2 Confirm the working tree is clean and `master` is current

## 2. Android version file and Gradle skeleton

- [x] 2.1 Create `android/version.json` holding `{"version": "<current>"}` matching the version from 1.1 exactly
- [x] 2.2 Add the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) pinned to the Gradle version matching AGP 8.13+
- [x] 2.3 Add `android/settings.gradle.kts` declaring the `:app` module and the plugin/dependency repositories
- [x] 2.4 Add `android/gradle/libs.versions.toml` with the version catalog: AGP, Kotlin 2.2.x, JDK 17 toolchain
- [x] 2.5 Add `android/build.gradle.kts` (root, plugins declared `apply false`)
- [x] 2.6 Write the `versionCode` helper reading `android/version.json` via `groovy.json.JsonSlurper` and computing `major*10000 + minor*100 + patch`; throw at configure time when the file is absent, unparseable, not `x.y.z`, or when minor or patch exceeds 99
- [x] 2.7 Add `android/app/build.gradle.kts` wiring `versionName` from the file and `versionCode` from the helper; `minSdk 26`, `compileSdk 36`, `targetSdk 36`
- [x] 2.8 Add a minimal `android/app/src/main/AndroidManifest.xml` with `INTERNET` and no launcher activity — this is a shell, not an app
- [x] 2.9 Add Gradle build output to `.gitignore` (`android/.gradle/`, `android/build/`, `android/app/build/`, `android/local.properties`)
- [x] 2.10 Verify: `cd android && ./gradlew :app:assembleDebug` succeeds and reports the expected `versionName`/`versionCode` pair
- [x] 2.11 Verify the derivation table by temporarily setting the file to `0.1.7`, `0.2.0`, and `1.0.0` and confirming `107`, `200`, `10000`; restore the real version afterwards
- [x] 2.12 Verify a malformed `android/version.json` fails the Gradle configure phase with a message naming the file; restore afterwards

## 3. Extend version lockstep to seven locations

- [x] 3.1 Add a `REPO_ROOT` constant to `frontend/app/scripts/ext.mjs` derived from `APP_DIR`
- [x] 3.2 Append `path.join(REPO_ROOT, "android", "version.json")` to `versionFiles()` (`ext.mjs:88-96`) — do not modify `versionsIn` or `rewriteVersion`
- [x] 3.3 Verify `npm run check` now reports seven locations and exits 0
- [x] 3.4 Verify drift is caught: hand-edit `android/version.json` to a wrong value, confirm `npm run check` exits non-zero naming it, confirm `npm run package` and `npm run version:patch` both refuse; restore
- [x] 3.5 Verify a non-semver value in `android/version.json` is rejected; restore
- [x] 3.6 Verify an atomic bump: run `npm run version:patch`, confirm all seven locations moved together and `npm run check` passes, then revert the bump
- [x] 3.7 Verify the fresh-clone path: delete the generated `manifest.json`, confirm `npm run check` inspects seven locations and exits 0, then restore with `npm run switch:chrome`
- [x] 3.8 Verify no-toolchain operation: with no JDK on `PATH`, run `npm run check` and `npm run package` and confirm both succeed

## 4. Continuous-integration workflow

- [x] 4.1 Write `.github/workflows/check.yml` with a `verify` job (Node 22, `npm run check`) running on every push to `master` and every PR into it, unfiltered
- [x] 4.2 Add the `extension` job to `check.yml`, needing `verify`, path-filtered to `frontend/app/**`, running `npm run package` and uploading both zips as separate artifacts
- [x] 4.3 Add the `android` job to `check.yml`, needing `verify`, path-filtered to `android/**`, using `actions/setup-java@v4` (temurin 17) and `gradle/actions/setup-gradle@v4`, running `./gradlew :app:assembleDebug lintDebug testDebugUnitTest`
- [x] 4.4 Add the Room schema-drift guard to the `android` job — fail if `android/core/database/schemas/` is dirty after the build (a no-op until Room exists, wired now so it is never retrofitted)
- [x] 4.5 Delete `.github/workflows/extension-check.yml` in the same commit as 4.1–4.4
- [ ] 4.6 Verify on a branch: push an extension-only change and confirm `verify` + `extension` run and `android` is skipped
- [ ] 4.7 Verify on a branch: push an `android/**`-only change and confirm `verify` + `android` run and `extension` is skipped

## 5. Release workflow

- [x] 5.1 Write `.github/workflows/release.yml` triggering on `v*` with a `verify` job asserting `GITHUB_REF_NAME` minus `v` equals `package.json`'s version, then running `npm run check`
- [x] 5.2 Add the `extension` job, needing `verify`, running `npm run package` and uploading both zips as workflow artifacts
- [x] 5.3 Add the `android` job, needing `verify`, decoding `ANDROID_KEYSTORE_B64` with `ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD`/`ANDROID_STORE_PASSWORD` and running `./gradlew :app:bundleRelease :app:assembleRelease`
- [x] 5.4 Add the unsigned-degradation path to the `android` job: when `ANDROID_KEYSTORE_B64` is unset, build `assembleDebug`, log plainly that the artifact is unsigned, and exit 0
- [x] 5.5 Add the `release` job, needing both build jobs, downloading all artifacts and attaching four files with `softprops/action-gh-release@v3`
- [x] 5.6 Add the `workflow_dispatch` input gating Play submission, with a step using `r0adkll/upload-google-play@v1` and `PLAY_SERVICE_ACCOUNT_JSON` that runs only when the input is set
- [x] 5.7 Make the Play step fail explicitly when submission is requested but signing secrets are absent — never upload an unsigned or debug artifact
- [x] 5.8 Delete `.github/workflows/extension-release.yml` in the same commit as 5.1–5.7
- [ ] 5.9 Verify end to end on a fork: push a throwaway tag with no signing secrets configured, confirm the Release carries both zips plus an unsigned APK and that the log says so
- [ ] 5.10 Verify the guard: push a tag whose name disagrees with the recorded version and confirm the workflow fails before building anything

## 6. Documentation

- [x] 6.1 Update `CLAUDE.md` — "six places" becomes seven, listing `android/version.json`
- [x] 6.2 Add to `CLAUDE.md` that `npm run package` stays extension-only and the APK/AAB are CI products, so no JDK or Android SDK is needed for extension work
- [x] 6.3 Update the release-flow section of `CLAUDE.md` for the renamed workflows and note that one `v*` tag now yields four release assets
- [x] 6.4 Document the Play-submission opt-in and the required CI secrets

## 7. Close out

- [x] 7.1 Re-verify the extension is unbroken: `cd frontend/app && npm run check && npm run package` with no JDK and no Android SDK on `PATH`, producing both zips
- [x] 7.2 Verify both manifest variants still switch cleanly: `npm run switch:chrome && diff manifest.json manifest.chrome.json` exits 0, same for Firefox
- [ ] 7.3 Load `frontend/app` unpacked in Chrome and in Firefox and confirm a block from an entry dropdown still succeeds in both — exercises the JSDOM path in Chrome and the `DOMParser` path in Firefox
- [x] 7.4 Confirm `git diff --stat -- frontend/app/assets/` is empty: no extension runtime code was touched
- [ ] 7.5 Run `openspec validate unified-release-pipeline` clean, then `openspec archive unified-release-pipeline`
