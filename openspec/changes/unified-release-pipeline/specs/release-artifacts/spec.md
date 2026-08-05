# release-artifacts

Binds: extension (Chrome + Firefox) and Android. Defines what a `v*` tag produces.

## ADDED Requirements

### Requirement: One tag produces three artifacts

Pushing a `v*` tag SHALL produce a single GitHub Release carrying four files: the
Chrome zip, the Firefox zip, the Android AAB, and the Android APK — all built from
that tag's commit and all recording the same version.

There SHALL NOT be a separate Android tag namespace. One release means one tag.

#### Scenario: Tag publishes everything

- **WHEN** `v0.1.7` is pushed to `master`
- **THEN** a GitHub Release `v0.1.7` appears carrying `eksiengelplus-0.1.7-chrome.zip`, `eksiengelplus-0.1.7-firefox.zip`, the AAB, and the APK

#### Scenario: No path filtering on release

- **WHEN** a tagged commit touches only `frontend/app/**`
- **THEN** the Android artifacts are still built and attached, because the release must be complete regardless of what changed

#### Scenario: One side fails to build

- **WHEN** the Android build fails
- **THEN** no GitHub Release is created and no partial set of artifacts is published

### Requirement: Release job ordering

`release.yml` SHALL run `verify` first; `extension` and `android` SHALL depend on it
and MAY run concurrently; the `release` job SHALL depend on both.

`verify` asserts the tag matches the recorded version and runs `npm run check` across
all seven version locations. Nothing is built until it passes.

#### Scenario: Verify gates the builds

- **WHEN** `npm run check` fails
- **THEN** neither the `extension` nor the `android` job starts

### Requirement: Missing signing secrets must not block extension releases

When the Android signing secrets are absent, the `android` job SHALL produce an
unsigned debug build and the release SHALL proceed. It SHALL NOT fail the workflow.

The pipeline exists to decouple the three artifacts from each other's problems.
Blocking an extension hotfix on an unconfigured keystore would reintroduce exactly
the coupling this change removes.

#### Scenario: Keystore not yet configured

- **WHEN** `ANDROID_KEYSTORE_B64` is unset and `v0.1.7` is pushed
- **THEN** both zips and an unsigned debug APK are attached, the release is created, and the job log states the build was unsigned

#### Scenario: Keystore configured

- **WHEN** `ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `ANDROID_STORE_PASSWORD` are all set
- **THEN** the job produces a signed release AAB and APK

### Requirement: Store submission is separate from artifact publication

Publishing artifacts to the GitHub Release SHALL be unconditional on every tag.
Submission to Google Play SHALL be opt-in per release, gated behind an explicit
`workflow_dispatch` input. Chrome Web Store and addons.mozilla.org uploads remain
manual.

The three stores cannot publish in step — Chrome review takes hours to days, AMO
minutes to days, and Play adds review plus a 12-tester × 14-day gate. Coupling an
extension CSS fix to a Play review cycle for a bit-identical APK is waste.

#### Scenario: Ordinary release

- **WHEN** `v0.1.7` is pushed with no dispatch input
- **THEN** all four artifacts are attached to the Release and nothing is sent to Play

#### Scenario: Release intended for Play

- **WHEN** the workflow is dispatched with the Play submission input set
- **THEN** the signed AAB is additionally uploaded to the Play internal track using `PLAY_SERVICE_ACCOUNT_JSON`

#### Scenario: Play submission requested without signing secrets

- **WHEN** Play submission is requested but the keystore secrets are absent
- **THEN** the workflow fails explicitly rather than uploading an unsigned or debug artifact

### Requirement: `npm run package` remains extension-only

`npm run package` SHALL continue to build exactly the two store zips and SHALL NOT
invoke Gradle. Composing the three artifacts is CI's job.

Requiring a JDK and the Android SDK to package the extension would break the
zero-dependency local workflow for contributors who never touch Android.

#### Scenario: Local packaging

- **WHEN** `npm run package` runs in `frontend/app` with no JDK on `PATH`
- **THEN** it writes both zips to `frontend/publish/dist/` and exits 0

#### Scenario: Firefox size ceiling still enforced

- **WHEN** any text file in the Firefox build exceeds 5 MB
- **THEN** `npm run package` fails, preserving the addons.mozilla.org guard that substitutes `scripts/jsdom-stub.firefox.js` for the 5.9 MB `assets/js/jsdom.js`

### Requirement: Continuous checks are path-filtered, releases are not

`check.yml` SHALL run version verification on every push to `master` and every PR
into it. The `extension` and `android` build jobs within it SHALL be path-filtered to
`frontend/app/**` and `android/**` respectively.

#### Scenario: Extension-only change

- **WHEN** a PR touches only `frontend/app/assets/js/background.js`
- **THEN** version verification and the extension build run, and the Android build is skipped

#### Scenario: Android-only change

- **WHEN** a PR touches only `android/app/build.gradle.kts`
- **THEN** version verification and the Android build run, and the extension build is skipped

### Requirement: The extension build is never regressed by Android work

Every change under `android/**` SHALL leave `npm run check` and `npm run package`
passing, and SHALL NOT modify any file under `frontend/app/assets/`.

`assets/js/jsdom.js` in particular stays and stays required: Chrome MV3 service
workers have no DOM, so `parseHTML()` (`frontend/app/assets/js/scrapingHandler.js:19-38`)
falls through to JSDOM there, while Firefox background scripts use the native
`DOMParser` and receive the stub at package time.

#### Scenario: Android work does not touch extension runtime

- **WHEN** CI builds any commit
- **THEN** `npm run check` and `npm run package` succeed and both browser zips are produced

#### Scenario: Both parser paths still work

- **WHEN** the extension is loaded unpacked in Chrome and in Firefox after an Android-side change
- **THEN** a block initiated from an entry dropdown succeeds in both, exercising the JSDOM path in Chrome and the `DOMParser` path in Firefox
