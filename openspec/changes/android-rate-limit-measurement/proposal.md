## Why

The Android action pacer is configured at **12 actions per minute**. That number
has never been measured against the server. It traces to a single user-facing
string in the extension — `frontend/app/assets/js/notificationHandler.js:60` —
where it was written to explain a delay to a user, not to record an observation.
`openspec/specs/android-rate-limiting/spec.md` then cites that string as "the
documented limit", which lends it an authority it never earned.

Three things follow from an unverified figure, and they fail in opposite
directions:

- **If the real limit is lower**, the pacer overshoots and the app eats a 429 and
  a full cooldown on every long run. The penalty logic is correct and would
  absorb it, so the symptom is a run that takes far longer than it should with no
  visible error — the hardest kind to notice.
- **If the real limit is higher**, every user waits longer than the server ever
  asked them to, on every operation, forever.
- **If `Retry-After` is absent on 429**, the spec's "the delay comes from the
  server" scenario has no input and the fallback is whatever the code happens to
  do. The extension's own fallback is a hard-coded 62 seconds
  (`background.js handleCooldown:639`), a guess this change exists to replace.

This was research question **S4** of `android-spike`. It was scheduled last there
because it deliberately trips a server-side protection, and the spike closed on
its actual gate — whether a WebView session can drive `POST /userrelation/*` from
OkHttp — without ever running it. That gate passed and the port shipped. S4 is
now the only substantive question the spike left behind, and it does not belong
in a feasibility change whose feasibility question is answered.

## What Changes

- Measure the real mutation rate limit against a controlled account pair, and
  record the count, the elapsed time, and the exact response.
- Record whether `Retry-After` is present on 429 and, if so, whether it is
  integer seconds or an HTTP date.
- Confirm the true cooldown by retrying at the advertised time.
- Write `docs/android/rate-limit-measurement.md` as the provenance for whatever
  number the pacer ends up carrying.
- Reconcile `android-rate-limiting` with the measurement: the configured rate
  SHALL cite a measurement rather than a UI string, and the 429 fallback SHALL be
  a measured cooldown rather than a constant.
- If the measured limit differs from 12/min, change the pacer's configuration and
  the extension's user-facing string together, so the two never disagree.

## Impact

- Affected specs: `android-rate-limiting`
- Affected code: the action pacer configuration; the 429 fallback delay;
  `frontend/app/assets/js/notificationHandler.js:60` only if the measured figure
  differs from 12
- New doc: `docs/android/rate-limit-measurement.md`

This change mutates real relations on real accounts. Every task that does so is
paired with its reversal, and the target is a second controlled account — never a
third party's.
