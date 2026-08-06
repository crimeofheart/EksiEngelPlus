# Early foreground-budget warning

## Why

`android-operations-engine` parks an operation when its foreground-service budget
runs out and schedules a continuation for the next day. Correct, but the user
only learns about it after the fact — the run has already stopped.

There is a better option available the whole time and we never offer it: **work
performed while the app is visible costs no budget at all**, because a visible
activity keeps the process alive without a foreground service. `ForegroundBudget`
already stops billing on `releaseForeground()`; nothing tells the user that
opening the app makes the remaining work run for free.

So the fix is not to find more background time. It is to tell the user, before
the budget is gone, that they can finish now by opening the app.

## What Changes

- A warning notification at a configurable fraction of the budget (default 80%),
  stating how much work is left and that opening the app finishes it immediately.
- Tapping the notification opens the app; the operation continues unbilled while
  it stays visible.
- The existing exhaustion notification stays as the fallback for when the warning
  is ignored.
- The warning fires once per slice, not repeatedly.

## Capabilities

### Modified Capabilities

- `android-operations`: the budget requirement gains the warning and the
  foreground-continuation route.

## Impact

`ops/runtime` — `ForegroundBudget`, `OpsNotifier`, `RoomOperationContext`. No
schema change, no new permission.

## Non-goals

- Choosing a foreground-service type without the 6h cap. `mediaPlayback`,
  `location` and `specialUse` all avoid it, and all would mean misrepresenting
  what the service does to both the OS and Play review.
- Assuming user interaction resets the platform's 24-hour counter. It may not,
  and the design must not depend on it — see the open question in design.md.
- Keeping the screen on, or asking the user to.
