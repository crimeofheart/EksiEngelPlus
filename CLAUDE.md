# EksiEngelPlus

Browser extension (Chrome MV3 + Firefox) in `frontend/app/`, Django backend in `backend/`,
Android app in `android/`. One version number ships all three.

All extension tooling is `frontend/app/scripts/ext.mjs`, exposed as npm scripts. Zero
dependencies — `npm install` is never needed.

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
archive (old dev log + TODO backlog) — never add releases there.

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

**`package` is extension-only and stays that way.** The APK and AAB are CI products —
extension work needs no JDK and no Android SDK, and `frontend/app` keeps zero dependencies.
Reading `android/version.json` is a plain JSON read, never a Gradle invocation.

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
```

The tag triggers `.github/workflows/release.yml`, producing **four assets** on one GitHub
Release: both browser zips, the AAB, and the APK. One release means one tag — there is no
separate Android tag namespace. Download the assets and upload them to the stores. `release`
refuses a dirty tree or an existing tag.

**A version bumped but never tagged** ships nowhere, and neither ordinary path recovers it:
`release patch` bumps *again* and skips the prepared version, while `release 0.1.7` fails on
`already at 0.1.7` because a no-op rewrite is refused. Instead:

```bash
npm run release -- current        # tag HEAD at the recorded version, no commit
```

The same refusals apply; it only skips the rewrite and the commit, because there is nothing
to rewrite and an empty commit is not a release note.

### Store submission

CI publishes *artifacts*, not store listings, and the three stores cannot go live in step —
Chrome review takes hours to days, AMO minutes to days, and Play adds review plus a
12-tester × 14-day gate on new accounts.

Chrome and AMO uploads are manual. **Play submission is opt-in**: run `release.yml` via
`workflow_dispatch` with `submit_to_play` set, so an extension-only fix never burns a Play
review cycle on a bit-identical APK. That path fails hard if signing secrets are absent
rather than uploading an unsigned artifact.

Secrets for a signed build: `ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`, `ANDROID_STORE_PASSWORD`, plus `PLAY_SERVICE_ACCOUNT_JSON` to upload.
When `ANDROID_KEYSTORE_B64` is unset the Android job degrades to an unsigned debug build and
the release still publishes — an unconfigured keystore must never block an extension release.

## Android app

A real client, not a stub: six Gradle modules (`app`, `webview`, `ops/engine`, `ops/runtime`,
`core/database`, `devharness`) around a WebView that loads the real site, a WorkManager-backed
operations engine that checkpoints and resumes across process death, and Room persistence.
Remaining work is tracked in `openspec/changes/`.

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

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
derives both — the version from `android/version.json`, the notes from the newest entry in
`docs/changelog.json` — and `landing_page` passes them as context, so a release needs no
template edit. `npm run check` already fails when either file is wrong or stale, so the page
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
into it, uploading the two zips as separate artifacts.
