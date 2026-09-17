## 1. Keeping the position

- [x] 1.1 Record the scroll position per address in `sessionStorage`, debounced on `scroll` and flushed on `pagehide` and on hide
- [x] 1.2 Hold the address the position belongs to, so a save debounced across a navigation is not filed under the page being arrived at
- [x] 1.3 Cap the store, dropping the least recently written addresses, so a long paging session cannot grow it without bound
- [x] 1.4 Degrade silently when storage is unavailable — losing a position must never throw out of a scroll handler

## 2. Giving it back

- [x] 2.1 Restore on `popstate`, which is the only thing that can fix Ekşi's in-page pagination
- [x] 2.2 Restore on a document load whose navigation type is `back_forward`, and on no other load
- [x] 2.3 Retry while the document is shorter than the remembered position, bounded by a deadline, so XHR-extended lists still land
- [x] 2.4 Set `history.scrollRestoration = "manual"`, so the renderer's own attempt cannot pull the page back to the top
- [x] 2.5 Abandon restoration on the first touch, wheel or key — the user has decided where they want to be
- [x] 2.6 Skip restoration when the address carries a fragment, which is the site aiming at an anchor of its own
- [x] 2.7 Reset to the top when an in-page back lands on an address with no remembered position — `manual` restoration took the renderer's reset away with it

## 3. Verification

- [x] 3.1 `./gradlew :webview:compileDebugAndroidTestKotlin :app:assembleDebug testDebugUnitTest`
- [x] 3.2 Instrumented test: back after `pushState` returns to the remembered position; forward does not; a page never scrolled is left at the top
- [x] 3.3 Run the instrumented suite on an emulator: `:webview` 41/41, whole project 96/96
- [ ] 3.4 Verify on device: gündem, a title being paged, a profile's entry list and a following list all come back to where they were left
- [ ] 3.5 Verify on device that a fresh tap into a long list still starts at the top
- [ ] 3.6 Run `openspec validate android-scroll-memory` clean, then `openspec archive android-scroll-memory`
