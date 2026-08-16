## 1. Recognise a title link, wherever it is

- [x] 1.1 Add `TITLE_PATH` (`/^\/[^\/]+--\d+\/?$/`) and `titleHoldAnchor` to `bridge.js`: the nearest `a[href]`, accepted when its pathname is a title's or when it is the direct child of `#title`
- [x] 1.2 Exclude `.pager`, `.dropdown-menu`, `.sub-title-menu` and our own menu, and say why — the pager's "sonraki" carries the same address and means something else
- [x] 1.3 Suppress the native callout on those links with a document-start stylesheet, mirroring `hideAdSlots`' `documentElement` append

## 2. The hold itself

- [x] 2.1 Install document-level `touchstart`/`touchmove`/`touchend`/`touchcancel` listeners that arm a 500 ms timer on a title link and cancel it past the swipe's 12 px slop
- [x] 2.2 Suppress the click that ends a hold, in the capture phase, so the title does not open behind the menu — narrowly, on title links only
- [x] 2.5 Fire an option from its own `touchend`: a touch sequence stays with the element it began on, so no press aimed at a button can ever be the opening gesture's, and no gate is needed
- [x] 2.6 Drop the hold on `visibilitychange`/`pagehide` and on `appPaused` from the host, since the share chooser can cover the WebView mid-gesture; not on `blur`, which the system clipboard preview fires on every copy
- [x] 2.4 Arm the menu two frames after the lift rather than after a fixed 150 ms: a second tap lands inside a fixed delay once the user knows where the button is, and falls through to the page
- [x] 2.3 Vibrate on open where the permission allows it, guarded, since it is not the confirmation

## 3. The menu

- [x] 3.1 Render a bottom sheet with a backdrop, headed by the title's name, holding three buttons in `#81C14B` — the app's green from `core/ui` `colors.xml`
- [x] 3.2 "başlığı kopyala" sends the name: `data-title` on the header, otherwise the row's text with its `<small>` count dropped
- [x] 3.3 "bağlantıyı kopyala" and "paylaş" send the address with the list's `?a=` sort parameter removed and every other parameter kept
- [x] 3.4 Close the menu before acting, and dismiss on any touch landing outside the card — on the touch, not the click, so dismissal never depends on clicks working
- [x] 3.7 Centre the sheet rather than anchoring it to the bottom, so it does not share that space with the system clipboard preview on Android 13+
- [x] 3.8 Tear the WebView down in `onDestroy`: three were alive at once on a test device, one per Activity instance
- [x] 3.6 Dismiss without removing the backdrop mid-gesture: hide it on the touch, drop the node on the lift (or a 500 ms fallback), since removing a live touch's own target wedges the WebView's gesture handling and the page stops answering touches
- [x] 3.5 Suspend the swipe for any gesture while the menu is open, including one already in progress: its `will-change` makes the slid element the containing block for our fixed backdrop, which unanchors the card from the viewport and reads as a freeze on title pages

## 4. Copy through the host

- [x] 4.1 Add an `onCopy` parameter and a `copy` message type to `BridgeHost`, parsed the way `share` is
- [x] 4.2 Implement `BrowserActivity.copy` over `ClipboardManager`, with the label naming what was copied
- [x] 4.3 Confirm with a toast below Android 13 only, and add the `copied` string

## 5. Hold the behaviour down

- [x] 5.1 Add `TOPIC_LIST_FIXTURE` and `TITLE_PAGE_FIXTURE` to `BridgeTestSupport`, both carrying what the real markup carries: the `<small>` count, the `?a=popular`, and a pager
- [x] 5.2 Add `BridgeTitleHoldTest` covering hold, tap, scroll, pager, the three options and the close — the negative cases first, since an invisible affordance fails by appearing
- [x] 5.3 `./gradlew :app:assembleDebug testDebugUnitTest lintDebug`
- [x] 5.4 `./gradlew :webview:connectedDebugAndroidTest` (needs a device or emulator)

## 6. Verification

- [x] 6.1 Verify on device: hold a gündem row and get the three options, in green
- [x] 6.2 Verify each option — the name pastes without its count, the link pastes without `?a=`, the sheet opens
- [x] 6.3 Verify a tap still opens the title, and that scrolling a long list never opens the menu
- [x] 6.4 Verify the pager, an author link and an entry permalink do nothing on a hold
- [x] 6.5 Verify the browser's own link menu no longer appears on a title
- [x] 6.6 `cd frontend/app && npm run check && npm run package`
- [x] 6.9 Chased a freeze on a test device to a fault in that device's WebView, not this code: measured with DevTools over adb — DOM intact, handlers attached, a programmatic click working, `rAF` firing, and a swipe moving `scrollY` 0 → 567 without one `touchstart` reaching the document. Cleared by updating Android System WebView. Three candidate fixes (suppressing the clipboard preview, restoring WebView focus, pause/resume of the input path) were each measured as ineffective and reverted
- [x] 6.7 Add the release note to `changelog.js` and `ReleaseNotes.kt`, regenerate `docs/changelog.json`, bump to 0.3.0
- [x] 6.8 `openspec validate android-title-hold-menu`, then `openspec archive android-title-hold-menu`
