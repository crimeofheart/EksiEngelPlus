## 1. Manifest declaration and its guard

- [x] 1.1 Add `browser_specific_settings.gecko_android` with `"strict_min_version": "142.0"` to
      `frontend/app/manifest.firefox.json`, leaving the existing `gecko` block (id,
      `strict_min_version`, `data_collection_permissions`) untouched. 142 rather than the desktop
      140 because `data_collection_permissions` only reached Firefox for Android in 142.
- [x] 1.2 In `cmdCheck` (`frontend/app/scripts/ext.mjs:331`), next to the existing
      "manifest.json matches a variant" assertion, parse `manifest.firefox.json` and fail when
      `browser_specific_settings.gecko_android` is missing or when its `strict_min_version`
      is below `browser_specific_settings.gecko.strict_min_version` (above is allowed, and is the
      shipping state).
- [x] 1.3 Prove both failure modes by hand: delete the key → `npm run check` exits non-zero naming
      it; set a mismatched version → exits non-zero naming both values; restore → passes.
- [x] 1.4 `npm run switch:firefox` then `diff manifest.json manifest.firefox.json` exits 0, and
      `npm run switch:chrome` then `diff manifest.json manifest.chrome.json` exits 0 — the
      generated manifest is still a plain copy of a variant.
- [x] 1.5 `cd frontend/app && npm run check && npm run package`.
- [x] 1.6 `web-ext lint` the packaged Firefox zip: no errors, and no
      `KEY_FIREFOX_ANDROID_UNSUPPORTED_BY_MIN_VERSION` warning at the declared floor.

## 2. Pages lay out at device width

- [x] 2.1 Add `<meta name="viewport" content="width=device-width, initial-scale=1">` to the
      `<head>` of all six pages in `frontend/app/assets/html/`: `popup.html`, `notification.html`,
      `faq.html`, `welcome.html`, `authorListPage.html`, `documentation.html`.
- [x] 2.2 Confirm in desktop Firefox at full window width that no page shifted: the viewport meta
      is a no-op above the breakpoints, so any visual change means a pre-existing rule was
      relying on the fallback viewport.
- [x] 2.3 With Firefox's Responsive Design Mode at 412×915, confirm `notification.html` enters its
      `@media (max-width: 480px)` layout — compact header, single-column stats cards.
- [x] 2.4 `cd frontend/app && npm run check && npm run package`.

## 3. Popup fits its panel

- [x] 3.1 In `frontend/app/assets/css/customPopup.css:2`, keep `width: 350px` and add
      `max-width: 100vw` (the rule already sets `box-sizing: border-box`).
- [x] 3.2 Verify in desktop Firefox and Chrome that the popup is still 350px wide and the two
      buttons are unchanged.
- [x] 3.3 Verify at 320px in Responsive Design Mode that the popup has no horizontal scrollbar and
      neither button is clipped.
- [x] 3.4 `cd frontend/app && npm run check && npm run package`.

## 4. Tables and touch targets

- [x] 4.1 In `customNotification.css`'s `@media (max-width: 768px)` block (line 1114), raise
      `.table-wrapper`'s `max-height` from the global 150px (line 588) to a phone-appropriate
      value so more than two rows are visible.
- [x] 4.2 In the same block, give `.task-action-btn` and the `.stats-btn` family
      `min-height: 40px; min-width: 40px` with padding to match, so "Tekrarla"/"Git" are tappable.
- [x] 4.3 At 412px width with a populated queue, confirm the document does not scroll horizontally
      and the table itself scrolls sideways inside its card.
- [x] 4.4 Confirm at desktop width that the table rows are still the dense 11px layout — the new
      rules live only inside the ≤768px block.
- [x] 4.5 Stack the status card's control row below 768px: `.control-buttons-center` back into
      flow, counters on their own line, each control finger-sized. Found on device — the absolute
      centring drew the buttons on top of the counters.
- [x] 4.6 `cd frontend/app && npm run check && npm run package`.

## 5. Tooltips reachable without hover

- [x] 5.1 In `frontend/app/assets/css/tooltip.css:27,31`, extend the `.tooltip:hover::after` /
      `::before` selectors with `:focus-within` and `:active`.
- [ ] 5.2 On `faq.html`, confirm a tooltip appears on tap (Responsive Design Mode with touch
      simulation on), on keyboard focus, and still on hover.
- [x] 5.3 `cd frontend/app && npm run check && npm run package`.

## 6. Docs and release note

- [x] 6.1 Add a Firefox-for-Android row to the load/console table in `CLAUDE.md`, recording that
      the add-on is installed on a phone from AMO or debugged over USB via desktop
      `about:debugging` → "This Firefox" → the connected device, and that `frontend/app` is still
      the folder for a temporary add-on.
- [x] 6.2 Add an extension entry under the next (undated) version in
      `frontend/app/assets/js/changelog.js` saying the add-on now runs on Firefox for Android, then
      `npm run changelog` so `docs/changelog.json` is regenerated and `npm run check` stays green.
- [x] 6.3 `cd frontend/app && npm run check && npm run package`.

## 7. On-device verification

- [ ] 7.1 Install the Firefox zip on a phone (AMO test listing or `about:debugging` over USB) and
      confirm the add-on appears in the Fenix extensions menu and its popup opens.
- [ ] 7.2 On a logged-in `eksisozluk.com` entry list, confirm the injected entry-menu items
      ("engelle"/"sessize al"/"takip et") appear in the dropdown and dispatch an operation.
- [ ] 7.3 On a logged-in profile page (`/biri/<nick>`), confirm the injected relation buttons
      appear alongside the site's own and dispatch an operation.
- [ ] 7.4 Run one small operation end to end and confirm the "Ana İşlemler" page opens, the queue
      and history tables are readable, and "Tekrarla"/"Git" are tappable on a failed row.
- [ ] 7.5 `cd frontend/app && npm run check && npm run package`, then
      `openspec validate firefox-android-support` clean and `openspec archive
      firefox-android-support`.
