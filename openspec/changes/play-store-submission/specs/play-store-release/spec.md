## ADDED Requirements

### Requirement: The Play listing says what the app is, and what it is not

Binds: Google Play listing for the `play` flavor (`org.duzgun.eksiengelplus`).

The listing SHALL use the name `EksiEngelPlus`, category Social, e-mail
`eksiengelplus@gmail.com`, website `https://eksiengelplus.duzgun.org` and
privacy policy `https://eksiengelplus.duzgun.org/privacy/`. The short
description SHALL stay within 80 characters and the full description within
4000, in Turkish (default, tr-TR) and English (en-US). Both descriptions SHALL
state, before listing any feature, that the app is not an official Ekşi Sözlük
app and is not affiliated with Ekşi Sözlük.

Graphics SHALL be `android/play/icon-512.png` (512×512, no alpha channel) and
`android/play/feature-graphic.png` (1024×500), both built from the app's own
launcher art, never from Ekşi Sözlük's logo. At least two phone screenshots
(9:16 or 16:9) SHALL be committed under `android/play/screenshots/`, taken with
a disposable test Ekşi account so no real user's nickname appears.

The exact texts and paths SHALL be recorded in `android/play/README.md`
alongside this spec, so the listing can be reproduced without the Console.

#### Scenario: Listing is assembled
- **WHEN** the listing is filled in Play Console for a new release
- **THEN** the name, category, contact, website, privacy URL, both descriptions, the icon, the feature graphic and at least two screenshots come from `android/play/`, and each description opens with the not-official disclaimer

#### Scenario: Screenshot leaks a real user
- **WHEN** a committed screenshot shows a nickname that is not the disposable test account or a public title's author list taken while signed in as that account
- **THEN** it is replaced before the listing uses it

### Requirement: Every App content answer is the one the code and privacy page imply

Binds: Play Console App content forms for the `play` flavor.

The submission SHALL answer the Play Console declarations as follows, and the
answers SHALL match the shipped code and the served privacy page:

- **App access** — all functionality restricted; a test Ekşi Sözlük account's
  credentials and a numbered path (open the site page the app shows, tap
  "giriş", sign in, then "engelle" from an entry's menu or "toplu işlemler" and
  "işlem durumu") SHALL be supplied.
- **Data safety** — collects required data types: yes; encrypted in transit:
  yes; no data shared with third parties; no account creation on our server;
  deletion requestable in-app and at `https://eksiengelplus.duzgun.org/privacy/#silme`.
  Types: Personal info → User IDs (required), App activity → Other actions
  (optional), Device or other IDs (optional), App info and performance →
  Diagnostics (optional).
- **Foreground service** — type **Data sync**
  (`android.permission.FOREGROUND_SERVICE_DATA_SYNC`, WorkManager
  `androidx.work.impl.foreground.SystemForegroundService` declared with
  `android:foregroundServiceType="dataSync"`), with a description of the
  user-started bulk operation and a link to a screen recording that shows the
  operation running after the app is backgrounded.
- **Ads** — no ads. **Target audience** — 18 and over only. **Content rating**
  — Social / communication, user interaction yes, no location sharing,
  unrestricted internet yes, no digital purchases. **News** — no.

The Data safety answers SHALL stay in step with the "Sunucumuza gönderilenler"
section of `backend/django_EksiEngel/api/templates/privacy/index.html` and with
`openspec/specs/android-persistence/spec.md`; a change to either SHALL be
reflected in `android/play/README.md` in the same commit.

#### Scenario: Data safety disagreement
- **WHEN** the privacy page starts naming a sent field that the Data safety table omits
- **THEN** the submission is not sent until both name the field and its required/optional status

#### Scenario: Foreground service rejected for a missing video
- **WHEN** the Data sync declaration is submitted without the recording link
- **THEN** the submission is incomplete and the video is recorded and linked first

### Requirement: The first production release passes a closed test

Binds: Play Console release tracks.

The first submission SHALL upload the `play` AAB built by
`tools/build-release.sh` for the recorded version to a **closed testing** track,
which enrolls the app in Play App Signing with the pinned upload certificate.
Production SHALL NOT be requested until at least **12 testers have been opted
in for 14 consecutive days**. `android/play/README.md` SHALL name the track, the
AAB file name, the tester count and the start date once the track exists.

#### Scenario: Track started
- **WHEN** the 0.6.5 AAB is uploaded to closed testing
- **THEN** the app is enrolled in Play App Signing and the 14-day clock starts from the first opt-in

#### Scenario: Production request too early
- **WHEN** fewer than 12 testers have been opted in for 14 consecutive days
- **THEN** the production release is not requested, regardless of how green the app content is

### Requirement: The play update channel is raised only after Play serves the build

Binds: Android `play` flavor and `tools/set-min-version.sh`.

The `play` minimum SHALL remain unchanged until Google Play serves the submitted
version to everyone; only then SHALL the maintainer run
`tools/set-min-version.sh --min-play <version>`. Because the `play` flavor is
not allowed to install an APK itself, a `play` minimum raised before Play offers
the build leaves the app usable for 7 days
(`openspec/specs/app-update-gate/spec.md`, "The update comes from where the build
came from") — that grace SHALL NOT be used as a substitute for waiting.

#### Scenario: Play launch
- **WHEN** Play's production rollout reaches 100% for 0.6.5
- **THEN** `tools/set-min-version.sh --min-play 0.6.5` is run, and only the `play` channel moves

#### Scenario: Minimum raised during review
- **WHEN** `--min-play` is raised while the build is still in review
- **THEN** that is treated as a defect in this process, and the policy is corrected rather than relied on

### Requirement: The three review risks are answered before review

Binds: Play submission review notes.

The submission SHALL carry review notes for the three policies the app can be
read against, in the release's own words:

- **Spam / WebView of a site we do not own** — the app's purpose is bulk
  blocking, muting and following with the user's own session, with background
  runs, a date filter and cross-device sync; the site is the surface those
  operations act on, not the product.
- **Impersonation / IP** — the name contains "Ekşi" but every description says
  the app is unofficial and unaffiliated, and no Ekşi Sözlük logo or brand asset
  is used in the icon, feature graphic or screenshots.
- **Device and network abuse** — the app paces itself under eksisozluk.com's
  request limit and acts only through the user's own authenticated session.

#### Scenario: Review asks why a WebView
- **WHEN** review requests justification for embedding eksisozluk.com
- **THEN** the answer cites the bulk operations, background runs, date filter and sync, and the fact that no content is republished

### Requirement: The store-facing backend surface is tested in CI

Binds: `backend/django_EksiEngel/`.

`api/test_release_info.py` and `api/test_update_policy.py` SHALL run in CI on
every push to `master` and every pull request, together with the rest of
`backend/django_EksiEngel`'s test modules, because they cover the release notes
the landing page renders and the signed update policy that a store release makes
public — neither of which had run outside the maintainer's machine before this
change. The job SHALL be selected when the backend tree changes and when the two
files those modules read (`android/version.json`, `docs/changelog.json`) change.

#### Scenario: Release notes break
- **WHEN** `release_info` stops running the landing page's notes back to the last announced version
- **THEN** the backend job fails on the push, not on the live site

#### Scenario: A release ships without the job
- **WHEN** a commit touches only `backend/`, `android/version.json` or `docs/changelog.json`
- **THEN** the path filter selects the backend job, so the modules those files feed are executed
