## Why

Two gaps in the browsing surface, one of them a defect the other made worse.

**Nothing can be refreshed by hand.** Every main screen renders from a live
source — the WebView from the page, Listeler and İşlem durumu from Room flows —
so the screens are never stale for want of a re-read. They are stale for want of
a *fetch*: the page has moved on, the synced lists are hours old, or a checkpoint
was left `RUNNING` by a process that died and nothing has reconciled it since the
last cold start of the browser. Refresh existed for exactly one of these, buried
in a per-row popup menu on Listeler. The gesture every Android user tries first
did nothing anywhere.

**The horizontal swipe stopped at the title.** `bridge.js` cycles bugün / gündem
/ debe / takip with a drag, and `currentTabIndex()` correctly returns -1 on
`/slug--123` — a title is not a tab — so the gesture refused outright there. The
one place a swipe has an obvious meaning inside a title, turning the page, was
the one place it did nothing.

Adding pull-to-refresh broke the swipe that did work. The stock
`SwipeRefreshLayout` decides on Y movement alone and never compares the drag
against how far it travelled sideways, so any horizontal swipe with downward
drift was taken the moment the Y delta cleared touch slop. Interception delivers
`ACTION_CANCEL` to the child; `bridge.js` had no `touchcancel` listener, so
`settle()` never ran and the page stayed translated wherever the finger had
reached. `requestDisallowInterceptTouchEvent` is not a defence — the WebView does
call it when a page's touch handler consumes the gesture, and SwipeRefreshLayout
deliberately ignores that request for any child with nested scrolling disabled,
which a plain WebView is.

## What Changes

- Pull-to-refresh on the three main screens, each wired to the fetch behind it:
  the browser reloads the page, Listeler re-syncs all four lists, İşlem durumu
  reconciles stale checkpoints.
- `VerticalSwipeRefreshLayout` in `core:ui` — claims a drag only once it is
  predominantly vertical, and stays refused for the rest of that gesture.
- `touchcancel` handling in `bridge.js`, settling an interrupted drag back to
  origin rather than leaving it stranded.
- The swipe's destination ring becomes pluggable: a title's pages when there are
  pages to turn, the main tabs otherwise. The page ring is not circular and its
  ends carry the neighbouring tabs, so swiping past the last page continues into
  the next tab instead of stopping at a boundary the user has no reason to know
  about.

## Non-goals

- No new navigation UI. The gesture drives the site's own links and the site's
  own `?p=` pagination; nothing is invented.
- Pull-to-refresh is not added to the author list (a text editor with no remote
  data, where the gesture would fight the `EditText`) or to Ayarlar / Yardım /
  sürüm notları (static content).
- The page ring does not wrap. Page one and the last page are genuinely this
  title's ends, and jumping between them answers a different question.
- No change to how operations are paced, enqueued or reconciled. The İşlem
  durumu gesture calls the existing `OperationReconciler`.

## Impact

- Affected specs: `android-browsing`
- Affected code: `core:ui` (new `VerticalSwipeRefreshLayout`, `onPullToRefresh`,
  shared spinner colour); `BrowserActivity` + `activity_browser.xml`;
  `ListsActivity` / `ListsViewModel` + `activity_lists.xml`;
  `OperationsActivity` + `activity_operations.xml`;
  `webview/src/main/assets/bridge.js`
- New dependency: `androidx.swiperefreshlayout:1.1.0`, exposed as `api` from
  `core:ui` because the screens name the class in their own layouts.
- No change to `frontend/app/` runtime code. `bridge.js` lives in
  `android/webview/src/main/assets/` and ships only in the app.
