# EksiEngelPlus

Browser extension (Chrome MV3 + Firefox). Source lives in `frontend/app/`.
Backend (Django) in `backend/`.

## Loading the unpacked extension

Load folder is the same for both browsers: `frontend/app`.
Only `frontend/app/manifest.json` differs — it is the live manifest and must be
swapped before loading.

| File | Role |
| --- | --- |
| `manifest.json` | active manifest, read by the browser |
| `manifest.chrome.json` | Chrome MV3 source of truth (`service_worker`, `type: module`, `jsdom.js` in `web_accessible_resources`) |
| `manifest.firefox.json` | Firefox source of truth (`background.scripts`, `browser_specific_settings.gecko`, no `jsdom.js`) |

### Switch to Chrome

```bash
cd frontend/app && npm run restore-chrome
```

Then `chrome://extensions` → Developer mode → Load unpacked → `frontend/app`.
Debug console: the "service worker" link on the extension card.

### Switch to Firefox

```bash
cd frontend/app && npm run load-firefox
```

Then `about:debugging#/runtime/this-firefox` → Load Temporary Add-on →
select `frontend/app/manifest.json` (the file, not the folder).
Debug console: "Inspect" on the temporary add-on.

### When the user says "switch to Chrome/Firefox"

1. Run the matching npm script above from `frontend/app`.
2. Confirm the swap by diffing `manifest.json` against the target variant
   (`diff manifest.json manifest.chrome.json` should exit 0 for Chrome;
   for Firefox the only expected differences are the ones the script strips).
3. Report the load path and the debug-console location for that browser.

Reload the extension in the browser after every edit — the old `background.js`
stays cached otherwise.
