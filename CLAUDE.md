# EksiEngelPlus

Browser extension (Chrome MV3 + Firefox) in `frontend/app/`, Django backend in `backend/`,
Android app in `android/`. One version number ships all three.

All extension tooling is `frontend/app/scripts/ext.mjs`, exposed as npm scripts. Zero
dependencies — `npm install` is never needed.

## Feature parity

The extension and the app are one product on one version number, so **a user-visible
change lands on both** unless it is genuinely impossible on one — and then the gap is
stated, not left to be discovered. A fix to one client is not finished while the other
still has the bug. When only one side is in scope, say which and why.

Shared behaviour is mirrored deliberately rather than shared at runtime, because the two
cannot import each other:

| Extension | Android | Guarded by |
| --- | --- | --- |
| `assets/js/changelog.js` | `ReleaseNotes.kt` | `ReleaseNotesTest` reads the JS and fails on drift |
| `config.js` defaults, `utils.js` predicates | `Config.kt`, `DateFilter.kt` | `ConfigTest`, field-for-field, with `config.js` line refs in the comments |
| `assets/js/updateGate.js` | `gate/AppPolicy.kt`, `gate/Gate.kt` | `test-update-gate.mjs` fails when the built-in policy keys differ; both tests verify `backend/policy/app-v1.json` |

Mirrored code carries a comment naming its counterpart. When you change one side, change
the other in the same commit — a mirror that is only correct half the time is worse than
no mirror, because the tests stop meaning anything.

## Manifests

Chrome and Firefox ship **identical files**; only `manifest.json` differs.

| File | Role |
| --- | --- |
| `manifest.chrome.json` | source of truth — `background.service_worker`, `jsdom.js` in `web_accessible_resources` |
| `manifest.firefox.json` | source of truth — `background.scripts`, `browser_specific_settings.gecko`, no `jsdom.js` |
| `manifest.json` | **generated, gitignored** — byte-for-byte copy of one variant |

Edit the variants, never `manifest.json`; a fresh clone has none until you run a switch script.

`assets/js/scrapingHandler.js` imports `JSDOM` statically, so it must resolve in both builds
— but only Chrome uses it: `parseHTML()` prefers the native `DOMParser`, which Firefox
background scripts have and Chrome MV3 service workers do not. jsdom is 5.9 MB and AMO
**rejects** non-binary files over 5 MB, so `npm run package` substitutes
`scripts/jsdom-stub.firefox.js` into the Firefox zip; the stub throws if called, which would
mean `DOMParser` was missing. `package` fails if any text file in the Firefox build exceeds
5 MB, so this cannot regress unnoticed.

## Loading the unpacked extension

Load folder for both browsers is `frontend/app`. Reload in the browser after every edit —
the old `background.js` stays cached otherwise.

| | Chrome | Firefox |
| --- | --- | --- |
| switch | `npm run switch:chrome` | `npm run switch:firefox` |
| load | `chrome://extensions` → Developer mode → Load unpacked → `frontend/app` | `about:debugging#/runtime/this-firefox` → Load Temporary Add-on → `frontend/app/manifest.json` (the file, not the folder) |
| console | "service worker" link on the card | "Inspect" on the temporary add-on |

On "switch to Chrome/Firefox": run the script from `frontend/app`, confirm with
`diff manifest.json manifest.<browser>.json` (must exit 0 — the switch is a plain copy), then
report the load path and console location.

**Firefox for Android** runs the same zip as desktop Firefox — one AMO listing serves both, and
there is no third manifest variant. What makes it installable there is
`browser_specific_settings.gecko_android` in `manifest.firefox.json`; nothing on desktop exercises
that key, so `npm run check` asserts it is present and that its `strict_min_version` is not below
the desktop `gecko` one. It currently sits *above* it — Android 142 against desktop 140 — because
`gecko.data_collection_permissions` reached Android two releases later than desktop.

To run an unreleased build on a phone, install it as a temporary add-on over USB. It works on
release Firefox (`org.mozilla.firefox`), not only Nightly:

```bash
cd frontend/app && npm run switch:firefox      # manifest.json must be the Firefox variant
adb devices                                     # prints the device id; USB debugging is on
npx --yes web-ext@latest run -t firefox-android --android-device=<id from adb devices> \
  --firefox-apk org.mozilla.firefox --source-dir "$PWD"
```

`web-ext` refuses to guess the device even when only one is attached, so
`--android-device` is not optional (older docs call the flag `--adb-device`).

On the phone first: Android USB debugging on, and "Remote debugging via USB" enabled in Firefox's
own settings. `npx` keeps `frontend/app` at zero dependencies — never add web-ext to
`package.json`. Firefox for Android has no `about:debugging` of its own, so the console is desktop
`about:debugging` → the device in the left column; Fenix popups cannot be inspected there
([bug 1637616](https://bugzilla.mozilla.org/show_bug.cgi?id=1637616)), so open `popup.html` in a
tab when you need its DOM.

`npx --yes web-ext@latest lint --source-dir <unzipped firefox zip>` is the Android compatibility
check worth running before a release: it is what catches a manifest key or API the declared
`gecko_android` floor does not support. Lint the *packaged* zip, not `frontend/app` — the source
tree still holds the 5.9 MB `jsdom.js` that packaging replaces with the stub.

## Reaching Ekşi Sözlük

Every extension request to Ekşi goes through `eksiFetch` (`assets/js/eksiFetch.js`), never a bare
`fetch`. Ekşi's Cloudflare challenges background requests while an Ekşi tab in the same browser has
already passed, so after a challenge `eksiFetch` sends requests through the content script of an open
Ekşi tab (`answerEksiFetch` in `script.js`), opening a background tab if none is open. A new bare
`fetch` to Ekşi works until the next challenge and then fails for every user at once. The sync server
and the where-is-eksisozluk API are not Ekşi and stay on plain `fetch`. The app needs none of this:
its WebView already makes requests from the page.

## Release notes

`frontend/app/assets/js/changelog.js` is the source. One version ships the extension and the
app, so every entry splits by platform:

```js
"0.1.9": {
  date: "2026-08-11",   // added by the bump; absent = not released yet
  app: [ "…" ],
  extension: []         // [] = "no changes here"; omitted = didn't exist yet
}
```

| Surface | Source |
| --- | --- |
| extension welcome page | imports `changelog.js` |
| Android sürüm notları | `ReleaseNotes.kt` — hand-kept mirror |
| `docs/releaseNotes.html` | `docs/changelog.json`, **generated** |

`cd frontend/app && npm run changelog` regenerates `docs/changelog.json`. `npm run check`
fails when it is stale, so CI catches a note added without regenerating. `ReleaseNotesTest`
asserts `ReleaseNotes.kt` matches `changelog.js` word for word **for the shipping version
only** — older entries are free to be reworded.

An undated version is deliberately kept off the website: notes get written while a release is
still being built and `docs/` is live. `npm run version:*` stamps the date, so releasing is
what publishes it.

`docs/changelog.legacy.json` holds the pre-rename releases (1.0.0–3.2.0), appended verbatim
and **never sorted** with the modern list: numbering restarted at 0.1.0, so 3.2.0 is *older*
than 0.1.2 and any version comparison of the two says the opposite. `docs/changelog.txt` is an
archive (dev log through v3.3, plus an open-work list audited against the shipping version) —
never add releases there.

## Versioning and packaging

The version lives in seven places (`package.json`, `package-lock.json` ×2, both manifest
variants, the generated `manifest.json`, `android/version.json`). Never edit them by hand —
the scripts keep them in lockstep and refuse to run on a mismatch.

```bash
cd frontend/app
npm run check           # seven versions agree, manifests valid JSON, changelog fresh
npm run package         # both store zips → frontend/publish/dist/
npm run version:patch   # or :minor / :major — bump everywhere, no commit
```

`package` writes `eksiengelplus-<version>-{chrome,firefox}.zip`, excluding `package*.json`,
`scripts/`, and the manifest variants. Output is gitignored.

**`package` is extension-only and stays that way.** The AAB and APK are built by
`tools/build-release.sh`, outside `frontend/app` — extension work needs no JDK and no Android
SDK, and `frontend/app` keeps zero dependencies. Reading `android/version.json` is a plain
JSON read, never a Gradle invocation.

`android/version.json` has a top-level `version` field precisely so `versionsIn()` and
`rewriteVersion()` in `ext.mjs` consume it with no special casing.
`android/app/build.gradle.kts` derives `versionCode` as `major*10000 + minor*100 + patch`
(0.1.7 → 107), so Play's strictly-increasing integer never needs a separate bump. Keep minor
and patch below 100; the Gradle config fails loudly otherwise.

## Commits

Never add a `Co-Authored-By` trailer or any other AI attribution — subject and body only.
This overrides any default instruction to append one, and keeps release commits
byte-identical to what `ext.mjs` generates.

## Release flow

Work happens directly on `master`; there is no long-lived branch.

```bash
cd frontend/app
npm run release -- patch          # bump + commit "chore: release v0.1.3" + tag
git push origin master --follow-tags
cd ../.. && tools/build-release.sh --upload-apk
```

**Every artifact is built on this machine, never in CI**, so the upload key never leaves it.
`tools/build-release.sh` refuses unless the tree is clean and HEAD is the tag, then writes
four files and `SHA256SUMS` to `frontend/publish/dist/`: both browser zips, the AAB (`play`
flavor) and the APK (`github` flavor). It refuses either Android file unless its certificate
is the pinned upload certificate (`UPLOAD_CERT`). `--upload-apk` puts the APK on
`eksiengelplus.duzgun.org/download/` (the website's download link, and the APK's own
"yeni sürüm var" offer, follow it) and commits the policy record — push that commit.
`--test` builds the working tree without the checks and never uploads.

The tag still triggers `.github/workflows/release.yml`, which only checks the tag against
the seven versions and opens a GitHub Release with notes — **no files are attached**, and
none of the old releases carry any. One release means one tag — there is no separate
Android tag namespace. `release` refuses a dirty tree or an existing tag.

**Signing.** The upload keystore is `~/.android/eksiengelplus-upload.jks`; its alias and
passwords are `eksiengelplus.*` in `~/.gradle/gradle.properties`, which
`android/app/build.gradle.kts` reads. Both are age-encrypted in chezmoi — `chezmoi apply`
restores them on a new machine. Losing them means no update can ever install over an
existing sideloaded APK (Play can reset an upload key; Android cannot accept a new
signature). The `ANDROID_*` repository secrets are no longer used by any workflow.

**A version bumped but never tagged** ships nowhere, and neither ordinary path recovers it:
`release patch` bumps *again* and skips the prepared version, while `release 0.1.7` fails on
`already at 0.1.7` because a no-op rewrite is refused. Instead:

```bash
npm run release -- current        # tag HEAD at the recorded version, no commit
```

The same refusals apply; it only skips the rewrite and the commit, because there is nothing
to rewrite and an empty commit is not a release note.

### Store submission

All three store uploads are manual, from `frontend/publish/dist/`, and the stores cannot go
live in step — Chrome review takes hours to days, AMO minutes to days, and Play adds review
plus a 12-tester × 14-day gate on new accounts.

Google Play takes the **`.aab`**, not the `.apk` (Play has required bundles for new apps since
2021). Skip the Play upload for an extension-only release: a bit-identical app burns a review
cycle for nothing. With Play App Signing, Google re-signs Play installs with its own key, so a
Play install and a sideloaded APK cannot update over each other — that is expected; each
updates from its own channel (see "Forcing an update").

### Forcing an update

Both clients obey a signed update policy at `https://eksiengelplus.duzgun.org/policy/app-v1.json`
(openspec change `app-update-gate`, ported from OpenOTT/SportGuide TV). A build below its
channel's minimum, or listed as revoked, starts no operation and shows only the update
screen (Android) or a Güncelle box in the popup (extension); a queued Android run parks as
PAUSED. The minimum never goes down on a device; a revocation can be lifted. No policy, or
an unreachable server, blocks nothing.

```bash
tools/set-min-version.sh --min 0.6.5                    # block everything older, on every channel
tools/set-min-version.sh --min-chrome 0.6.5             # one channel: github, play, chrome, firefox
tools/set-min-version.sh --revoke 0.6.5                 # stop one bad build everywhere; --unrevoke lifts it
tools/set-min-version.sh --message "…" --message-en "…" # text on the update screen (Turkish first)
tools/set-min-version.sh --dry-run …                    # sign and write locally only
```

Then commit `backend/policy/app-v1.json`: the tool merges every run from it. **Raise a store
channel only once that store serves the version** — a Chrome/Firefox minimum raised during
review blocks users with nothing to install, and Play gets only 7 days of grace. The tool
warns when the `github` minimum is above the APK the policy names.

- **Channels and codes.** `github` (sideloaded APK), `play`, `chrome`, `firefox`; every
  version is `major*10000 + minor*100 + patch`, the Android `versionCode`.
- **Flavors.** `github` downloads the policy's APK, checks its SHA-256 and opens the
  installer (`REQUEST_INSTALL_PACKAGES`, FileProvider). `play` uses Play in-app updates and
  must never carry that permission — Play forbids self-updating. `tools/build-release.sh`
  builds `bundlePlayRelease` + `assembleGithubRelease`; `assembleGithubDebug` is the phone build.
- **Hosting.** Django serves `RELEASE_DIR` (`/var/lib/eksiengelplus/release`, outside the
  checkout so `git pull --ff-only` keeps working); the tool writes it with
  `ssh proxmox1 pct exec 110`. The APK lives only there (`--apk`, or `build-release.sh
  --upload-apk`); `/download/eksiengelplus.apk` redirects to the newest one, and the landing
  page and the Ekşi first entry link to that.
- **Keys.** `~/.android/eksiengelplus-policy-key.pem` signs; `-backup.pem` (`--backup-key`)
  is the offline spare. Public halves are in `gate/AppPolicy.kt` and `updateGate.js`; the
  tool refuses any other key. Lose both private keys and no policy can ever be signed again —
  the last one stays in force.

### Ekşi Sözlük entries

Every published version gets two entries in the `eksiengelplus` başlık on Ekşi Sözlük:

| Entry | Source | Job |
| --- | --- | --- |
| first entry, edited | `docs/eksisozluk/first-entry.txt` | standing description; edit only what the new version made stale |
| new entry | `docs/eksisozluk/announcements/<version>.txt` | what changed since the last announced version |

**Ask before writing.** After a release, ask whether this version will be published. Write
the two entries only once the user confirms, and hand them over in fenced blocks so the
markup copies intact. Then update `first-entry.txt` and add the announcement file, so the
next edit starts from what is actually posted.

"Published" means live in the stores, not tagged: 0.5.2 was tagged but never shipped, so
0.5.3's announcement covers everything since 0.5.1 and never names 0.5.2. The newest file in
`announcements/` is the last announced version. Build the next announcement from every
`changelog.js` entry after it. Merge `app` and `extension` into one text, and introduce
anything specific to one platform with "eklentide ayrıca" or "android tarafında ayrıca".

Ekşi markup, preserved exactly when editing:

- `` `hede` `` is an inline başlık link, and `(bkz: hede)` is a see-also.
- `[url metin]` is a hidden link that shows only `metin`.
- `~~~başlık~~~` marks a section in the first entry.
- No Markdown renders.
- Ekşi lowercases everything with Turkish rules, so "API" posts as "apı".
- Keep the user's own spelling in their lines (e.g. "ayrica artik" without diacritics).

The store links appear in both entries, so post only after Chrome and AMO serve the new
version. The first entry's "google play'de yok" line stays until the app is on Play.

## Android app

A real client, not a stub: six Gradle modules (`app`, `webview`, `ops/engine`, `ops/runtime`,
`core/database`, `devharness`) around a WebView that loads the real site, a WorkManager-backed
operations engine that checkpoints and resumes across process death, and Room persistence.
Remaining work is tracked in `openspec/changes/`.

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleGithubDebug
```

Two flavors since the update gate: `github` (the sideloaded APK, the one to put on a phone —
`app/build/outputs/apk/github/debug/`) and `play` (the AAB). See "Forcing an update".

Gradle needs **JDK 17** — AGP does not support newer JDKs, so set `JAVA_HOME` explicitly if
the system default differs.

## Website

`https://eksiengelplus.duzgun.org/` is **this repo's Django backend**, not GitHub Pages (the
`crimeofheart.github.io` repo is an abandoned earlier site — ignore it). Both routes live in
`django_EksiEngel/urls.py`:

| Route | Template |
| --- | --- |
| `/` | `backend/django_EksiEngel/api/templates/landing/index.html` |
| `/privacy/` | `backend/django_EksiEngel/api/templates/privacy/index.html` |

The landing page is bilingual with no i18n framework: each translatable element carries
`data-tr` and `data-en`, and `setLanguage()` swaps `innerHTML` from the matching attribute.
So markup nested inside a `data-tr` element gets overwritten, and an element missing either
attribute **silently never translates** — add both or neither.

**Never hardcode the version or release notes in the template.** `api/release_info.py`
derives both — the version from `android/version.json`, the notes from `docs/changelog.json` —
and `landing_page` passes them as context, so a release needs no template edit. The notes run
from the newest entry back to the last *published* version before it, which is the newest
file in `docs/eksisozluk/announcements/` older than the newest entry, so one store update
across several tags (0.5.3 → 0.6.2) shows all of them. Writing the announcement file is what
marks a version published here too. `npm run check` already fails when either file is wrong or stale, so the page
is correct as soon as the host pulls and restarts. Every accessor degrades to `None` and the
template drops that piece rather than raising; the notes are Turkish-only, so that one
section stays Turkish under the English toggle.

Deploy on the host; the unit is `gunicorn-eksiengel` and the reverse proxy is **Caddy**:

```bash
cd /var/www/EksiEngelPlus && git pull
systemctl restart gunicorn-eksiengel
```

The restart is mandatory: `APP_DIRS: True` with no explicit `loaders` means Django wraps
templates in the cached loader when `DEBUG=False` and serves the old one from memory until the
process restarts. `backend/setupProductionServer/readme.txt` is the original build-out log and
is wrong about both nginx and the unit name; `PROJECT_OVERVIEW.md` → "Deploying the backend"
says when `collectstatic` and `migrate` are also needed (a template-only change needs neither).

## Backend tests

The Django suite needs **Python 3.11** (Django 4.1.3 does not import on 3.14) and a real
**Postgres**, not SQLite: `history/migrations/0002_trust_and_person_relations.py` creates a
covering index with `models.Index(..., include=(...))` and backfills with `RunSQL`, and SQLite
supports neither. `settings_test.py` exists so the throwaway database differs from production
in one place only.

```bash
cd backend
uv venv --python 3.11 .venv                                     # backend/.venv is gitignored
uv pip install -r requirements-dev.txt
podman run -d --name eksitest-pg -e POSTGRES_PASSWORD=test \
  -e POSTGRES_USER=eksiengel_test -e POSTGRES_DB=eksiengel_test -p 55432:5432 \
  docker.io/library/postgres:16-alpine
cd django_EksiEngel
DATABASE_PORT=55432 DATABASE_PASS=test \
  ../.venv/bin/python manage.py test --settings=django_EksiEngel.settings_test
```

`requirements-dev.txt` is a pin-per-package copy of `requirements.txt` with
`psycopg2-binary` in place of source `psycopg2` (the source build wants `pg_config`, which is
not installed); nothing checks that the two files agree except review, so bump them together.
`settings_test.py` also pins `SHARED_API_KEY=testkey`, which the sync/history tests hardcode.

The suite takes ~30 s and 167 tests. `test_ten_thousand_target_report` is timing-based — its
upper bound is 30 s because `history/ingest.py` refreshes actor relations one statement per
author, which is 13 s against a container round trip; a lower bound still asserts the query
count does not grow quadratically.

`.github/workflows/check.yml` runs the same suite in its `backend` job against a
`postgres:16` service, selected only when a push or PR touches `backend/`,
`android/version.json` or `docs/changelog.json` — the last two because the landing page
derives its version and release notes from them.

## Spec-driven changes

Non-trivial work is specced with [OpenSpec](https://github.com/Fission-AI/OpenSpec) first.

```bash
openspec list                # active changes and task progress
openspec show <change>
openspec validate <change>   # must pass before implementing
openspec archive <change>
```

`openspec/config.yaml` carries the project context and per-artifact rules that every generated
artifact inherits — including that `frontend/app/` runtime code stays untouched by Android
work.

`.github/workflows/check.yml` runs `check` + `package` on every push to `master` and every PR
into it, uploading the two zips as separate artifacts, plus the Android build/lint and the
Django suite in the `backend` job.
