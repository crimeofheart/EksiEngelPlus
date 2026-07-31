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

The version is recorded in six places (`package.json`, `package-lock.json` ×2,
both manifest variants, and the generated `manifest.json`). Never edit them by
hand — the scripts keep them in lockstep and refuse to run on a mismatch.

```bash
cd frontend/app
npm run check       # assert all six versions agree, manifests are valid JSON
npm run package     # build both store zips into frontend/publish/dist/
npm run version:patch   # or :minor / :major — bump everywhere, no commit
```

`npm run package` writes `eksiengelplus-<version>-{chrome,firefox}.zip`,
excluding `package*.json`, `scripts/`, and the manifest variants.
Output is gitignored.

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

Pushing the tag triggers `.github/workflows/extension-release.yml`, which
rebuilds both zips and attaches them to a GitHub Release. Download those assets
and upload them to the Chrome Web Store and addons.mozilla.org.

`npm run release` refuses to run on a dirty tree or an existing tag.

`.github/workflows/extension-check.yml` runs `check` + `package` on every push
to `master` and on any PR into it, uploading the two zips as separate artifacts.
