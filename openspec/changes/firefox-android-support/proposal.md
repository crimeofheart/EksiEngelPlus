## Why

Firefox for Android runs the same WebExtension engine as desktop Firefox, but the add-on is
invisible there: since Firefox 113 an extension is only offered on Android when its manifest
carries `browser_specific_settings.gecko_android`, and ours does not (issue #19). Even once it
installs, none of the extension's own pages declare a viewport, so Android lays them out at the
~980px fallback width and the user gets a zoomed-out desktop page on a phone — including the
"Ana İşlemler" queue page that is the extension's main surface. The responsive rules that
`customNotification.css` already ships (1024/768/480 breakpoints) never fire for that reason.

## What Changes

- `manifest.firefox.json` gains `browser_specific_settings.gecko_android` with a
  `strict_min_version`, which is what makes AMO offer and Firefox for Android accept the add-on.
- Every extension page (`popup`, `notification`, `faq`, `welcome`, `authorListPage`,
  `documentation`) gains a `<meta name="viewport">`, so the existing mobile breakpoints apply and
  text renders at readable size.
- The popup stops being a fixed 350px box and sizes to the panel it is given; on Android the
  popup is full-screen, on desktop it keeps today's width.
- The queue/history tables scroll horizontally inside their card instead of forcing the page
  wide, and the row actions ("Tekrarla"/"Git", stat buttons) get touch-sized hit targets.
- `tooltip.css` reveals its bubble on focus and activation as well as hover, so a touch user can
  reach the help text that is hover-only today.
- `npm run check` learns to assert the Firefox manifest still carries `gecko_android` and that its
  `strict_min_version` matches the desktop `gecko` one, so the Android listing cannot silently
  regress the way it silently never existed.
- Docs record how to run and debug the add-on on a phone (`about:debugging` over USB) and that
  the AMO upload is unchanged — one zip serves desktop and Android.

**Touches `frontend/app/` runtime code: yes** — deliberately. This change is about the extension,
not the Android app; it is the one area the usual "Android work leaves frontend/app alone" rule
does not cover. No scraping, queue, or background-operation logic changes: the edits are the
Firefox manifest, the extension's HTML `<head>`s, CSS, and the `check` script.

## Capabilities

### New Capabilities
- `extension-android-compatibility`: what the browser extension must declare and render for it to
  install and be usable on Firefox for Android — manifest declaration, viewport and layout
  behaviour at phone widths, touch-reachable affordances, and the build-time guard that keeps all
  of it from regressing.

### Modified Capabilities
<!-- None. The existing specs cover the Android app and the eksisozluk client contract; neither
     changes its requirements here. -->

## Impact

- `frontend/app/manifest.firefox.json` — new `gecko_android` key. Chrome manifest untouched, so
  the two variants stay "identical but for the browser block".
- `frontend/app/assets/html/*.html` — viewport meta in each `<head>`.
- `frontend/app/assets/css/customPopup.css`, `customNotification.css`, `tooltip.css` — responsive
  and touch rules. No class renames, so no JS churn.
- `frontend/app/scripts/ext.mjs` — one extra manifest assertion in `check`; still zero
  dependencies, still no JDK.
- `frontend/app/assets/js/changelog.js` — a note under the next (undated) version.
- `CLAUDE.md` — Firefox-for-Android load/debug row alongside the existing Chrome/Firefox table.
- No backend, no Android app, no version bump. `npm run package` output shape is unchanged: the
  same Firefox zip is what gets uploaded to AMO for both desktop and Android.
