## ADDED Requirements

### Requirement: A downward swipe refreshes the screen it is made on

Every main screen SHALL respond to a pull-down gesture by performing the fetch
that screen's content depends on. The screens already render from live sources —
the WebView from the loaded page, Listeler and İşlem durumu from Room flows — so
the gesture SHALL NOT be understood as "re-read the data". It is how the user
asks for the network work behind it:

| Screen | What the gesture does |
| --- | --- |
| Browser | `WebView.reload()` |
| Listeler | Enqueues `ListSyncWorker` for every list not already syncing |
| İşlem durumu | Runs `OperationReconciler.reconcile()` |

The spinner SHALL be driven by the state of the work rather than by the gesture.
A refresh started from Listeler's per-row menu SHALL raise it, and reopening a
screen mid-sync SHALL show it still turning, because a spinner that tracks the
gesture describes the finger rather than the work.

A refusal SHALL stop the spinner explicitly. Listeler refuses while an operation
is running — both share the session and the HTTP stack, and reads are unpaced,
so a sync would add request pressure to a run deliberately staying under the
server's ceiling — and a refusal produces no state change, so nothing else would
ever take the spinner down.

A reload that never completes SHALL NOT leave the spinner turning. The browser's
is cleared by `onPageFinished`, which fires for error pages too, and by a
timeout for the load that does not come back at all.

The gesture SHALL NOT be added to a screen with no remote fetch behind it: the
author list is a text editor whose swipe would fight its `EditText`, and Ayarlar,
Yardım and sürüm notları are static.

#### Scenario: Pulling down on the browser reloads the page

- **WHEN** the user pulls down on a page that is scrolled to the top
- **THEN** the WebView reloads, and the spinner clears when the page finishes

#### Scenario: Pulling down on Listeler syncs every list

- **WHEN** the user pulls down on Listeler with no operation running
- **THEN** a sync is enqueued for each of the four lists that is not already syncing

#### Scenario: A refresh refused mid-operation does not spin forever

- **WHEN** the user pulls down on Listeler while an operation is running
- **THEN** the refresh is refused with a message, and the spinner stops

#### Scenario: The spinner reflects work the gesture did not start

- **WHEN** a list sync is started from the row menu, or the screen is reopened while one is running
- **THEN** the pull-to-refresh spinner is shown for as long as that sync is active

#### Scenario: Reconciling from İşlem durumu

- **WHEN** the user pulls down on İşlem durumu
- **THEN** checkpoints left `RUNNING` by a dead process become `INTERRUPTED`, and are therefore resumable

### Requirement: A pull-to-refresh container only claims a vertical drag

A `SwipeRefreshLayout` SHALL NOT be used unmodified above a view with its own
horizontal gesture. The stock implementation decides on Y movement alone and
never compares the drag against how far it has travelled sideways, so a
horizontal swipe with any downward drift is taken as a pull the moment the Y
delta clears touch slop.

The container SHALL refuse the gesture once `|dx| > touchSlop && |dx| > |dy|`,
and SHALL stay refused until the finger lifts. Re-deciding per event would hand
the drag over halfway through, which is the behaviour this exists to prevent. A
tie SHALL go to the refresh: a perfectly diagonal drag is not evidence of
sideways intent.

`requestDisallowInterceptTouchEvent` SHALL NOT be relied on for this. The WebView
does call it when a page's touch handler consumes the gesture, but
SwipeRefreshLayout ignores that request for any child whose nested scrolling is
disabled — which a plain `WebView` is — so the child cannot defend itself and the
decision has to be made on the container's side.

The refreshable child SHALL be the direct child of the container. The gesture is
armed by asking that child whether it can still scroll up, and an intermediate
`FrameLayout` never can, so wrapping a whole stack arms the gesture everywhere on
the page.

#### Scenario: A horizontal swipe is not stolen

- **WHEN** the user swipes sideways across a page that is scrolled to the top, with some downward drift
- **THEN** the page's own handler keeps the gesture and no refresh is triggered

#### Scenario: A vertical pull still refreshes

- **WHEN** the user drags straight down from the top of the page
- **THEN** the refresh is triggered on release

#### Scenario: Mid-page drags scroll

- **WHEN** the user drags down while the page is scrolled away from the top
- **THEN** the page scrolls and no refresh is armed

### Requirement: An interrupted drag returns to origin rather than being stranded

`bridge.js` SHALL handle `touchcancel`. A native view that intercepts receives
the rest of the touch stream and all the page gets is that event; without it
there is no `touchend`, `settle()` never runs, and the surface stays translated
at whatever offset the finger reached — a page left sitting part-way off screen
with no way back.

A cancelled drag SHALL always settle back to origin and SHALL NEVER commit. The
gesture did not finish, so no distance counts as a decision.

This SHALL remain in place even though the container above now refuses
horizontal drags. The two thresholds overlap — a drag of roughly equal `dx` and
`dy` satisfies both the container's tie-break and the page's `MIN_X` / `MAX_Y` —
so both can still claim the same gesture, and this is what makes that recoverable
instead of visible.

#### Scenario: A drag taken by a native view is undone

- **WHEN** a horizontal drag in progress is interrupted by an ancestor intercepting the touch
- **THEN** the page animates back to its original position

### Requirement: The swipe cycles a title's pages, and continues into the tabs at its ends

Inside a title the horizontal swipe SHALL turn that title's pages. Outside one,
and on a title with a single page, it SHALL cycle the main tabs as before.

The two SHALL be one mechanism. The drag, the preview layer and the commit SHALL
NOT know which of the two they are moving between; only the ring of destinations
differs.

A title SHALL be identified by its URL — `/--\d+` in the path — and NOT by the
presence of a pager. The topic lists are paginated too, and keying off the pager
would replace the tab cycle on gündem with a page cycle.

Page URLs SHALL be built by setting `p` on the current URL rather than by
appending it, so `?a=dailynice` and the other selectors — which decide *which*
entries are being paginated — survive the page change.

The page count SHALL be taken from the pager's `data-pagecount` when present, and
otherwise from the highest `p=` the pager links to, since the pager always links
the end. The current page SHALL come from `data-currentpage`, falling back to
`?p=` in the URL.

At most the reachable neighbours SHALL be built. A long title has hundreds of
pages and the ring is rebuilt on every drag.

The page ring SHALL NOT wrap. Its ends SHALL instead carry the tabs adjacent to
the one the user came from, so a swipe past the last page continues into the next
tab and a swipe before the first returns to the previous one. Wrapping from the
last page to the first would answer "there is no next page" with a jump to the
start, which is a different thing than the user asked for.

Because a title page carries no active tab marker and no tab keyword in its path,
the tab a title was opened from SHALL be remembered — in `sessionStorage`, scoped
to the WebView's session — and used as the ring's anchor there. Elsewhere an
unresolved tab SHALL still refuse the gesture, because it still means "unrelated
page".

Where no ring can be built in the direction of the drag, the drag SHALL NOT start
at all, rather than starting and having nothing to settle onto.

#### Scenario: Turning a page inside a title

- **WHEN** the user swipes horizontally on page 2 of a 3-page title
- **THEN** the swipe navigates to page 1 or page 3, preserving any other query parameters

#### Scenario: Past the last page, into the next tab

- **WHEN** the user swipes forward on the last page of a title opened from gündem
- **THEN** the swipe navigates to the tab after gündem, and the next swipe there cycles the tabs normally

#### Scenario: A single-page title falls back to the tab cycle

- **WHEN** the user swipes on a title that has only one page, having arrived from a tab
- **THEN** the swipe cycles the main tabs

#### Scenario: The topic lists still cycle tabs

- **WHEN** the user swipes on gündem, which is itself paginated
- **THEN** the swipe cycles to another tab and does not turn gündem's pages

#### Scenario: An unrelated page refuses

- **WHEN** the user swipes on a page that is neither a tab nor a title, with no remembered tab
- **THEN** no drag starts
