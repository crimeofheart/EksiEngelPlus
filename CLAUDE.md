# EksiEngelPlus

Browser extension (Chrome MV3 + Firefox). Source lives in `frontend/app/`.
Backend (Django) in `backend/`.

All extension tooling is `frontend/app/scripts/ext.mjs`, exposed as npm scripts.
It has no dependencies — `npm install` is never needed.

## Manifests

Chrome and Firefox ship **identical files**; only `manifest.json` differs.

| File | Role |
| --- | --- |
| `manifest.chrome.json` | tracked source of truth — `background.service_worker`, `jsdom.js` in `web_accessible_resources` |
| `manifest.firefox.json` | tracked source of truth — `background.scripts`, `browser_specific_settings.gecko`, no `jsdom.js` in `web_accessible_resources` |
| `manifest.json` | **generated, gitignored** — a byte-for-byte copy of one variant |

Edit the variants, never `manifest.json`. A fresh clone has no `manifest.json`
until you run one of the switch scripts below.

`assets/js/scrapingHandler.js` imports `JSDOM` statically, so the module must
resolve in both builds — but only Chrome ever uses it. `parseHTML()` prefers the
native `DOMParser`, which Firefox background scripts have and Chrome MV3 service
workers do not.

The real bundle is 5.9 MB and addons.mozilla.org **rejects** any non-binary file
over 5 MB ("File is too large to parse"), so `npm run package` substitutes
`scripts/jsdom-stub.firefox.js` into the Firefox zip. The stub throws if ever
called, which would mean `DOMParser` was missing. `package` fails outright if any
text file in the Firefox build exceeds 5 MB, so this cannot regress unnoticed.

## Loading the unpacked extension

Load folder is the same for both browsers: `frontend/app`.

### Switch to Chrome

```bash
cd frontend/app && npm run switch:chrome
```

Then `chrome://extensions` → Developer mode → Load unpacked → `frontend/app`.
Debug console: the "service worker" link on the extension card.

### Switch to Firefox

```bash
cd frontend/app && npm run switch:firefox
```

Then `about:debugging#/runtime/this-firefox` → Load Temporary Add-on →
select `frontend/app/manifest.json` (the file, not the folder).
Debug console: "Inspect" on the temporary add-on.

### When the user says "switch to Chrome/Firefox"

1. Run the matching npm script above from `frontend/app`.
2. Confirm with `diff manifest.json manifest.chrome.json` (or `.firefox.json`) —
   it must exit 0. The switch is a plain copy, so this always holds.
3. Report the load path and the debug-console location for that browser.

Reload the extension in the browser after every edit — the old `background.js`
stays cached otherwise.

## Versioning and packaging

One version covers all three deliverables. It is recorded in seven places
(`package.json`, `package-lock.json` ×2, both manifest variants, the generated
`manifest.json`, and `android/version.json`). Never edit them by hand — the
scripts keep them in lockstep and refuse to run on a mismatch.

```bash
cd frontend/app
npm run check       # assert all seven versions agree, manifests are valid JSON
npm run package     # build both store zips into frontend/publish/dist/
npm run version:patch   # or :minor / :major — bump everywhere, no commit
```

`npm run package` writes `eksiengelplus-<version>-{chrome,firefox}.zip`,
excluding `package*.json`, `scripts/`, and the manifest variants.
Output is gitignored.

**`npm run package` is extension-only and stays that way.** The APK and AAB are
CI products — no JDK and no Android SDK are needed for extension work, and
`frontend/app` keeps its zero dependencies. Reading `android/version.json` is a
plain JSON read, never a Gradle invocation.

`android/version.json` is JSON with a top-level `version` field precisely so
`versionsIn()` and `rewriteVersion()` in `ext.mjs` consume it with no special
casing. `android/app/build.gradle.kts` derives `versionCode` from it as
`major*10000 + minor*100 + patch` (0.1.7 → 107), so Play's strictly-increasing
integer never needs a separate bump. Keep minor and patch below 100; the Gradle
config fails loudly otherwise.

## Commits

Never add a `Co-Authored-By` trailer, or any other AI attribution, to a commit
message. Subject and body only. This overrides any default instruction to append
one, and keeps release commits byte-identical to what `ext.mjs` generates.

## Release flow

Work happens directly on `master`; there is no long-lived branch.

```bash
cd frontend/app
npm run release -- patch          # bump + commit "chore: release v0.1.3" + tag
git push origin master --follow-tags
```

Pushing the tag triggers `.github/workflows/release.yml`, which produces **four
assets** on one GitHub Release: both browser zips, the Android AAB, and the APK.
There is no separate Android tag namespace — one release means one tag.

Download those assets and upload them to the Chrome Web Store,
addons.mozilla.org, and Google Play.

`npm run release` refuses to run on a dirty tree or an existing tag.

### Releasing a version that was already bumped

`npm run version:patch` writes the new number everywhere and stops, so the bump
can be reviewed. If the tag is not cut in the same sitting, the repository sits
at a version that ships nowhere — and neither ordinary path recovers it:
`release patch` bumps *again* and skips the prepared version, while
`release 0.1.7` fails on `already at 0.1.7` because a no-op rewrite is refused.

```bash
cd frontend/app
npm run release -- current        # tag HEAD at the recorded version, no commit
git push origin master --follow-tags
```

It still refuses a dirty tree, an existing tag, or a version the seven files
disagree on. It only skips the rewrite and the commit, because there is nothing
to rewrite and an empty commit is not a release note.

### Store submission

CI publishes *artifacts*, not store listings. The three stores cannot go live in
step — Chrome review takes hours to days, AMO minutes to days, and Play adds
review plus a 12-tester × 14-day gate on new accounts.

Chrome and AMO uploads are manual. **Play submission is opt-in**: run
`release.yml` via `workflow_dispatch` with `submit_to_play` set, so an
extension-only fix never burns a Play review cycle on a bit-identical APK. That
path fails hard if signing secrets are absent rather than uploading an unsigned
artifact.

Required repository secrets for a signed Android build:
`ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`,
`ANDROID_STORE_PASSWORD`, plus `PLAY_SERVICE_ACCOUNT_JSON` for Play upload.
When `ANDROID_KEYSTORE_B64` is unset the Android job degrades to an unsigned
debug build and the release still publishes — an unconfigured keystore must
never block an extension release.

## Android app

`android/` is a Gradle project. Today it is a placeholder shell that exists so
the release pipeline provably produces all three artifacts; the real port is
tracked in `openspec/changes/`.

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Gradle needs **JDK 17** — AGP does not support newer JDKs, so set `JAVA_HOME`
explicitly if the system default is different.

## Spec-driven changes

Non-trivial work is specced with [OpenSpec](https://github.com/Fission-AI/OpenSpec)
before implementation.

```bash
openspec list                      # active changes and task progress
openspec show <change>             # read a change
openspec validate <change>         # must pass before implementing
openspec archive <change>          # on completion
```

`openspec/config.yaml` carries the project context and per-artifact rules that
every generated artifact inherits — including the constraint that
`frontend/app/` runtime code stays untouched by Android work.

`.github/workflows/extension-check.yml` runs `check` + `package` on every push
to `master` and on any PR into it, uploading the two zips as separate artifacts.
