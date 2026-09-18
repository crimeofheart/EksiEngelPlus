## Context

Firefox for Android (Fenix) runs the same WebExtension engine as desktop Firefox, so the add-on's
JavaScript needs nothing: every API the extension touches is supported there —
`storage.local`, `runtime.*`, `tabs.create/get/sendMessage` (Android 54+),
`extension.getViews` (48+, `notificationHandler.js:13`) and even
`notifications.create` (48+, `programController.js:766`). What is missing is declarative and
presentational:

1. `manifest.firefox.json:8-16` declares `browser_specific_settings.gecko` only. Since Firefox for
   Android 113, `gecko_android` is the key that makes an add-on installable there and makes AMO
   offer the listing to Android users; without it the extension simply does not exist on a phone.
2. None of the six pages under `frontend/app/assets/html/` declares a viewport. Android's fallback
   viewport is ~980px, so each page is laid out desktop-wide and scaled down, and the phone
   breakpoints already written in `customNotification.css:1109,1114,1138` never match.
3. `customPopup.css:2` pins `body { width: 350px }`. The Android popup is a full-screen panel, so
   that is both dead space on a large phone and an overflow on a small one.
4. Touch has no hover: `tooltip.css:27,31` gate the entire bubble on `:hover`, and
   `customNotification.css:464` gives the row actions a 2px vertical padding — a target a finger
   cannot hit.

The content script needs no selector work. Fetching `eksisozluk.com` with a Fenix user agent
returns the same containers the script binds to (`script.js:666` — `.dropdown-menu,
ul.toggles-menu, .other .dropdown-menu`; `script.js:691` — `.profile-buttons`): the site is
responsive, not a separate mobile document.

## Goals / Non-Goals

**Goals:**
- The Firefox zip installs from AMO on Firefox for Android and its pages are readable and operable
  at phone widths.
- One artifact: the same `eksiengelplus-<version>-firefox.zip` serves desktop and Android.
- The `gecko_android` declaration is guarded by `npm run check`, because it is invisible in normal
  desktop use and would otherwise be dropped by the next manifest edit without anyone noticing.

**Non-Goals:**
- Chrome for Android. It does not load extensions; `manifest.chrome.json` is untouched.
- A mobile-specific UI. The existing pages get to work at phone widths; they are not redesigned.
- Any change to scraping, queueing, rate limiting or background operations. This change touches the
  Firefox manifest, page `<head>`s, CSS, `ext.mjs`, and docs.
- The Android app in `android/`. Unrelated deliverable, unaffected.
- A version bump or release. The change lands as an undated changelog entry.

## Decisions

**`gecko_android.strict_min_version` mirrors the desktop `gecko` one (`"140.0"`) rather than the
lowest technically possible (113).** Both builds run the same code, so a lower Android floor would
be a claim the desktop build does not make and nobody tests. `check` asserts the two agree, which
also means a future desktop bump cannot silently leave Android behind. Alternative considered:
omitting `strict_min_version` entirely (allowed — the key may be empty). Rejected: it advertises
the add-on to Fenix 113 builds that the desktop manifest has already ruled out.

**Viewport meta goes into each page's `<head>` as markup, not injected by script.** A `<meta>` the
browser reads during parse cannot race the layout; a script-inserted one can, and two of the pages
(`popup.html`, `welcome.html`) are short-lived enough for the flash to be the whole experience.
Six one-line edits, no shared header machinery — the pages have no template system and introducing
one here would be scope the change does not need.

**The popup keeps `width: 350px` with `max-width: 100vw`, and takes the whole panel below
480px.** Desktop keeps today's exact appearance — the panel is content-sized, so 350px still wins.
`max-width` alone was the first plan, but rendering the popup at 412px showed what that gives on a
phone: Firefox for Android draws the popup as a full-screen panel, so a 350px body sits against
one edge with dead space beside it. The `@media (max-width: 480px)` block therefore sets
`width: auto` and raises the two buttons to a 44px tap height; they are already `width: 100%`
(`customPopup.css:38`), so nothing else moves.

**The tables reuse `.table-wrapper` (`customNotification.css:588`), which already scrolls
horizontally.** The remaining phone problem there is `max-height: 150px`, which on a tall phone
screen shows about two rows; the ≤768px block raises it. No new container, no markup change:
`notification.html:268,289` already wrap both tables.

**Touch targets are widened inside the existing `@media (max-width: 768px)` block**
(`customNotification.css:1114`) rather than globally, so the desktop table keeps its dense 11px
rows. `.task-action-btn` and the `.stats-btn` family get `min-height: 40px; min-width: 40px` and
matching padding there.

**Tooltips gain `:focus-within` and `:active` alongside `:hover`** in `tooltip.css`, and the
tooltip elements stay whatever they are today — no `tabindex` sweep across `faq.html`. Tapping an
element makes it `:active` for the duration of the touch, and any focusable child (the FAQ bubbles
sit on links and buttons) makes `:focus-within` match, which also fixes keyboard access on desktop
as a side effect.

**`cmdCheck` (`ext.mjs:331`) gains a manifest assertion next to its existing "manifest.json matches
a variant" one (`ext.mjs:345-351`).** That is already the place where the script asserts manifest
invariants rather than versions, and it keeps the new rule inside the command CI runs on every push
(`.github/workflows/check.yml`). Reading one more JSON file adds no dependency.

**Carried forward deliberately: `chrome.tabs.query` (`background.js:104`) is documented as a
partial implementation on Firefox for Android — it may return only a subset of matching tabs.** The
call is the second of three fallbacks for finding the notification tab: `tabs.get` on the
remembered id runs first (`background.js:97`), and a miss here only means a second notification tab
is created rather than the existing one being reused (`background.js:113`). Fixing it properly
means tracking the tab id across service-worker restarts, which is a behavioural change to the
operations surface and belongs to its own change, not to an Android-compatibility pass.

## Risks / Trade-offs

- **Content-script UI cannot be verified from here; the mobile-layout evidence is a
  user-agent-spoofed fetch of the logged-out site.** The entry dropdown and the profile relation
  buttons only exist for a logged-in session. → The task list ends with on-device verification of
  exactly those two surfaces, and the spec records the selector contract so a mobile-layout break
  is a spec failure rather than a surprise.
- **AMO may re-review the add-on once it advertises Android compatibility**, which can delay an
  otherwise routine listing update. → The change is landed without a version bump, so the Android
  declaration ships with whatever the next release is rather than forcing a release of its own.
- **Raising `.table-wrapper`'s max-height on phones makes the page taller**, pushing the actions
  grid below the fold on small screens. → Accepted: two visible rows of a queue is worse than a
  scroll, and the card is collapsible (`#queueContent`).
- **`:active` tooltips vanish when the finger lifts.** → Accepted for this pass: it makes the text
  reachable, which it is not today. A tap-to-pin tooltip is a JS change to `faq.js` and is out of
  scope.
