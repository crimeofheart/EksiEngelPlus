## 1. Pull-to-refresh

- [x] 1.1 Add `androidx.swiperefreshlayout:1.1.0` to the version catalog; expose it as `api` from `core:ui` so the screens can name it in their own layouts
- [x] 1.2 Add `onPullToRefresh()` and the shared spinner colour to `core:ui`, so the tint and the "the handler does not stop the spinner" rule live in one place
- [x] 1.3 Browser: wrap the `WebView` — not the `FrameLayout` — and reload on pull; clear on `onPageFinished`, with the cover's timeout as the guard for a load that never returns
- [x] 1.4 Listeler: `refreshAll()` on the view model — refuses while an operation runs, skips lists already syncing, returns whether anything started
- [x] 1.5 Listeler: drive the spinner from `state.rows.any { it.sync.isActive }` in `render()`, and stop it explicitly on a refusal
- [x] 1.6 İşlem durumu: inject `OperationReconciler` and run `reconcile()` on pull
- [x] 1.7 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 2. Keep the horizontal swipe

- [x] 2.1 `VerticalSwipeRefreshLayout` in `core:ui`: refuse once `|dx| > touchSlop && |dx| > |dy|`, stay refused until the finger lifts, let `super` see every `ACTION_DOWN`
- [x] 2.2 Document why `requestDisallowInterceptTouchEvent` cannot be the fix — SwipeRefreshLayout ignores it for a child with nested scrolling disabled, which a plain WebView is
- [x] 2.3 Swap all three layouts to the subclass
- [x] 2.4 Add the `touchcancel` listener to `bridge.js`, settling back to origin and never committing
- [x] 2.5 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 3. One ring, two kinds of destination

- [x] 3.1 Extract `tabRing()` and a shared `ringNeighbour(ring, dir)`; move `SWIPE` to `items`/`at`/`wrap` so it is a ring like any other
- [x] 3.2 `titlePageRing()`: gate on the `/--\d+/` URL rather than on the presence of a pager, so gündem keeps cycling tabs
- [x] 3.3 Read the page count from `data-pagecount`, falling back to the highest `p=` the pager links; read the current page from `data-currentpage`, falling back to `?p=`
- [x] 3.4 Build page URLs with `URL.searchParams.set("p", …)` so `?a=dailynice` and friends survive
- [x] 3.5 Build only the reachable neighbours, never the whole run of pages
- [x] 3.6 `beginDrag(dir)` takes the direction, so a ring with no neighbour that way refuses before the drag starts
- [x] 3.7 Remember the current tab in `sessionStorage` from `warmNeighbours()`, and anchor a title's ring on it
- [x] 3.8 Carry the neighbouring tabs at the page ring's ends, so the last page continues into the next tab
- [x] 3.9 Verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 4. Verification

- [x] 4.1 `node --check bridge.js`; `./gradlew :app:assembleDebug :app:test :webview:test :feature:settings:test`
- [ ] 4.2 Verify on device: pull-to-refresh on all three screens, including the refusal while an operation runs
- [ ] 4.3 Verify on device: a horizontal swipe with downward drift is not stolen, and a mid-page downward drag scrolls rather than refreshing
- [ ] 4.4 Verify on device that Ekşi's mobile layout actually uses `.pager` — if it names the container differently, `titlePageRing()` never builds and titles silently fall back to the tab cycle
- [ ] 4.5 Verify on device: last page → next tab, first page → previous tab, single-page title → tab cycle
- [ ] 4.6 Add a regression test for `ringNeighbour` and the two ring builders. `bridge.js` has no test harness today, which is why the axis and cancel defects were only findable by hand
- [ ] 4.7 Run `openspec validate android-refresh-and-swipe-navigation --strict` clean, then `openspec archive android-refresh-and-swipe-navigation`
