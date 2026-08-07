## Context

The app reached a real phone for the first time with every module tested and
passing. Fourteen defects surfaced over the following session. They divide into
two groups, and the split is the most useful thing this change records.

**Assembly gaps** — code that works in isolation and is wired to nothing:

- `HiltWorkerFactory` was never installed, so *no* worker could be constructed
  and nothing WorkManager scheduled ever ran.
- `OperationCommandReceiver` was declared in no manifest, so the notification's
  pause and stop actions were dropped silently.
- `OperationReconciler.reconcile()` was written, unit-tested, and called only
  from its own test, so a stale `RUNNING` row was never cleaned up.

Three separate instances of the same shape. Each had passing tests; none of the
tests crossed the boundary where the defect lived.

**Platform contracts** — assumptions that only fail on a device:

- WorkManager's `Data` is capped at 10 KB, and a LIST run carries every nick it
  targets, so a CSV-sized list threw on the caller and closed the app.
- App-link verification means `ACTION_VIEW` for an Ekşi URL opens the official
  client with no chooser.
- Returning `false` from `onTouch` for `ACTION_DOWN` stops `ACTION_MOVE` being
  delivered at all.
- `onPageFinished` waits for every subresource, so third-party ad hosts delay
  the controls this app exists to inject.

## Goals / Non-Goals

**Goals:**

- Make each defect's *class* unrepresentable rather than fixing the instance.
- Record the measurements, so the next person does not re-derive them.

**Non-Goals:**

- An end-to-end harness that exercises the manifest, the Hilt-in-`Application`
  path, and WorkManager limits. It is the right answer to the assembly gaps and
  it is its own change; this one records the need.
- Changes to `frontend/app/` runtime code beyond the deliberate unfollow feature
  committed separately.

## Decisions

### Cancelling deletes the row rather than marking it terminal

`STOPPED` still leaves a checkpoint, and the startup sweep brought it back — so
cancelling appeared to work and the offer returned on the next launch. The row is
deleted outright.

That created a second problem worth stating, because the two are easy to fix
independently and wrong together: a live worker's next `checkpoint()` upserted the
row straight back as `RUNNING`, which is neither terminal nor resumable. The app
then reported an operation in progress that nothing could pause, stop or resume.
A missing row now *means* cancelled, and `checkpoint()` raises `StopSignal`
instead of recreating it.

### Destructive and expensive actions need an explicit target

Resuming a parked run restarts a bulk operation against real accounts. It was
reachable by tapping the notification body — and a swipe that registers as a tap
set one going — and by tapping the in-app bar's message.

Both are now explicit buttons. Dismissal gestures do nothing but dismiss. This is
the same rule the pause/stop notification actions already followed, applied to
the two places that had drifted from it.

### Off-site links go to a browser, never to an app

A plain `ACTION_VIEW` is resolved by app-link verification, so the official
client captures any Ekşi URL. The hand-off now carries a browser-only selector,
asking for something that handles bare `http`, which an app claiming one domain
does not. Handing a link to a browser was always the intent; saying so explicitly
is what makes it true.

### Ad and tracker blocking is host-based and evidence-based

Every blocked host was observed on a real page load. Ekşi's own hosts —
`ekstat.com` serves the site's images — and the font CDNs are untouched, because
blocking fonts trades a load win for a visible rendering change. Blocked requests
return an empty 200 rather than an error, so a script expecting a response fails
fast instead of retrying.

Slots the blocked hosts would have filled are collapsed, since an empty reserved
box is worse than an ad: a hole with no explanation.

### The measurements

| | Before | After |
|---|---|---|
| Cold start to visible content | 23.4 s | 6.0 s |
| `loadUrl` → `onPageFinished` | 18.2 s | — |
| Style resolutions per scan, 600-row list | 1202 | 1 |
| Profile page stall on an embedded frame | ~30 s | none |

## Risks / Trade-offs

- **Ad blocking is a product decision, not a bug fix** → chosen deliberately by
  the user after being shown the three options. It removes revenue from a site
  they use, and it is worth revisiting as a setting once a settings screen
  exists.
- **The blocklist will go stale** → it is narrow and hostname-based, so a new
  network simply loads. That fails in the safe direction.
- **A deleted checkpoint stops a live run mid-way** → intended; that is what
  cancelling means. The already-applied actions stand, as they do for any stop.
- **`ACTION_DOWN` returning true claims gestures on the bar** → the buttons are
  separate views and consume their own touches first, so only the message area
  is affected.

## Open Questions

- Whether the ad blocklist belongs in config, so it can be updated without a
  release.
- Whether parked runs deserve a permanent home on the lists screen rather than a
  bar that appears only above the browser.
