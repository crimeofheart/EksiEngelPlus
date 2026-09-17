## Why

Every list in the app forgets where it was. Read down gündem, open the fifteenth
title, come back — the list is at the top again. The same happens on a profile's
entry list, on a title being paged through, and on follower and following lists,
which is to say on every page long enough for the position to be worth keeping.

Nothing in the shell keeps it, and neither end of the browser would have. Ekşi
navigates two different ways and each loses the position for its own reason:

- Opening an entry, a profile or a `(bkz:)` is a document load. The renderer's
  own restoration only knows the height the document had when the load finished,
  and Ekşi extends its lists by XHR afterwards, so anything below that first
  screenful is clamped back to the top before the rows it referred to exist.
- Paging within a title is `pushState`. No navigation happened as far as the
  browser is concerned, so there is nothing for it to restore — the same reason
  the injectors already hook the history methods themselves.

## What Changes

- `bridge.js` remembers the scroll position per address in `sessionStorage`,
  debounced on scroll and flushed before any navigation can lose it.
- Going back restores it: on `popstate` for the in-page case, and on a document
  load whose navigation type is `back_forward` for the full-load case.
- Restoration retries while the document is still too short to hold the
  position, so a list extended by XHR lands where it was left rather than at its
  maximum height at load time.
- Restoration is abandoned the moment the user touches the page, and forward
  navigation is left alone.

## Impact

- Affected specs: `android-browsing`
- Affected code: `android/webview/src/main/assets/bridge.js`; `BridgeTestSupport`
  gains `domStorageEnabled`, which the app already sets
- No change to `frontend/app/` runtime code. A desktop browser restores scroll on
  back by itself; the extension needs nothing.
