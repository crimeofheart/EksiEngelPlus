## Why

The app was assembled from modules that each had tests and each passed them.
Then it ran on a real phone for the first time and six defects surfaced in an
afternoon, every one of them in the seams *between* the modules rather than
inside any of them:

- No worker could be constructed at all, so **nothing** WorkManager scheduled
  ever ran. The operations engine had never executed on a device.
- The notification's Duraklat and Durdur broadcast to a receiver declared in no
  manifest, so both did nothing.
- Pausing ended the foreground service and took its notification with it, leaving
  a parked run with no way back to it.
- A LIST run built from a CSV exceeded WorkManager's 10 KB input-data cap and
  force-closed the app.
- Profile buttons keyed off the URL path alone, so the app offered the user the
  chance to block themselves.
- An embedded iframe was routed as though it were an off-site link, stalling the
  profile page for roughly thirty seconds.

None of this was reachable by the tests that existed, because they construct
objects directly and never go through the manifest, the Hilt-in-`Application`
path, or WorkManager's serialisation limits. This change records what was fixed
and turns the findings into requirements so the next assembly gap fails loudly.

## What Changes

- **Workers are constructible.** `EksiEngelPlusApp` implements
  `Configuration.Provider` and supplies `HiltWorkerFactory`; the default
  WorkManager initialiser is removed so that configuration is the one that takes
  effect.
- **The command receiver is declared** in `:ops:runtime`, next to the service it
  belongs with.
- **A paused run is resumable** — from its own notification, which replaces the
  foreground one on the same id, and from the in-app bar, which now offers any
  resumable run rather than only one parked on `PAUSED_AUTH`.
- **An operation request is persisted, not passed.** It goes to
  `operation_checkpoint.requestJson`; only the operation id travels in `Data`.
- **Profile items follow the site's own relation buttons**, which is what keeps
  them off your own profile without the app needing to know who is logged in.
  Injectors may report "not ready" and be retried.
- **Sub-frames are left to the page.** The off-site policy applies to main-frame
  navigation only.
- **A login is noticed without a restart** — while no session is known, any
  navigation triggers a probe and the interval drops.
- **An interrupted sync resumes** rather than being abandoned as complete.
- **The WebView reports** page console output and failing sub-requests, without
  which a half-rendered page and a slow one are indistinguishable.

## Non-goals

- An end-to-end test harness that would have caught these. It is the right
  answer and it is its own change; the note below records the gap.
- Changes to `frontend/app/` runtime code. **This change does not touch it.** The
  unfollow action added to the extension in the same period is a separate,
  deliberate feature commit.
- Reworking the foreground-budget design, which behaved correctly throughout.

## Capabilities

### New Capabilities

- `android-assembly`: the wiring the app must declare for its parts to run at
  all — worker construction, manifest registration, and the limits that apply
  when work crosses a process boundary.

### Modified Capabilities

- `android-browsing`: profile items are gated on the site's relation buttons;
  sub-frames are exempt from off-site routing; session probing widens while
  logged out.
- `android-operations`: a paused run must remain reachable, and an operation
  request must survive without relying on WorkManager input data.

## Impact

- **Modified** `android/app/` — Application, manifest, `BrowserActivity`.
- **Modified** `android/ops/runtime/` — worker enqueue and resume, notifier,
  manifest.
- **Modified** `android/webview/` — URL routing, session monitor, `bridge.js`,
  logging.
- **Modified** `android/feature/lists/` — sync retry on interruption.
- **Unchanged** `frontend/app/`, `backend/`, and the Room schema.
