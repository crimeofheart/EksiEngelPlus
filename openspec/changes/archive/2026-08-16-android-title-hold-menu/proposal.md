## Why

An entry can be shared from the app: `injectShareMenu` (`bridge.js:1141`) puts a
"paylaş" item above Ekşi's per-network options and the host opens the Android
sheet with it (`BrowserActivity.share`). A title cannot be shared at all.

The gap is not an oversight in the injector — there is nowhere to inject. An entry
carries a `.dropdown-menu`; a title carries `.sub-title-menu`, a row of the site's
own anchors that our block submenu already occupies, and on a list page there is
no per-title container of any kind. A gündem page is a hundred title links and
nothing else, so a visible control per title is a control on every line of the
screen.

Holding a link is what Android already means by "act on this", and it costs no
pixels. The WebView's own long-press currently answers that gesture with a link
context menu whose options are the browser's, not the app's.

Copying belongs in the same menu. The two things a user does with a title link
are send it and paste it, and the second has no path in the app today: text
selection inside a WebView on a link is a fight with the browser's own handles,
and the site offers only per-network share destinations.

## What Changes

- Holding a title link opens a sheet with three options in the app's green:
  "başlığı kopyala", "bağlantıyı kopyala", "paylaş".
- Titles are recognised by their address — `/slug--1234567` — not by the page or
  the container they appear in, so the gesture works in gündem, in search
  results, in a profile's entry list, in the sidebar and on the title's own
  header. The pager and the two menu types are excluded: they carry a title's
  address without standing for the title.
- The WebView's native callout is suppressed on those links, so the browser's own
  long-press does not answer the same gesture at the same moment.
- A new `copy` message type on the bridge, handled by `ClipboardManager` in the
  host rather than by `navigator.clipboard` in the page.
- "paylaş" opens the same system sheet by the same path as the entry share.

## Non-goals

- **Changing the entry share.** It stays a menu item; an entry has a menu.
- **`navigator.clipboard` in the page.** It is gated on a permission prompt the
  app would have to answer on a third-party site's behalf, and the
  `execCommand("copy")` fallback needs a live selection, which is exactly what
  the hold suppresses.
- **A confirmation toast on Android 13 and up.** The platform previews every copy
  itself there, and ours would be the same message twice.
- **Holding an entry, an author, or a channel.** Only titles, only where the link
  is the title.

## Impact

- Affected specs: `android-browsing`
- Affected code: `android/webview/src/main/assets/bridge.js`;
  `android/webview/src/main/kotlin/.../BridgeHost.kt`;
  `android/app/src/main/kotlin/.../BrowserActivity.kt`;
  `android/app/src/main/res/values/strings.xml`;
  `android/webview/src/androidTest/kotlin/.../BridgeTitleHoldTest.kt`
- No change to `frontend/app/` runtime code.
