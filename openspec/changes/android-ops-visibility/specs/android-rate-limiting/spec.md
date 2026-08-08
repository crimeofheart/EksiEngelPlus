## ADDED Requirements

### Requirement: A wait is interruptible and reports how long is left

The pacer's wait SHALL be delivered through an injected sleep that the caller
may slice, observe, and abandon. Abandoning a wait SHALL NOT consume the permit
it was waiting for: `ActionPacer.acquire()` takes its token only after the sleep
returns, so a run stopped mid-wait leaves the bucket exactly as it found it.

Binds the Android client. The extension has no equivalent: `programController.js:615`
and `background.js:639` each sleep the rejected caller with no way to interrupt
it, which is why a stop there waits out the cooldown.

#### Scenario: Stopping during a cooldown takes effect without waiting it out

- **WHEN** the user presses Durdur while a caller is inside a pacing wait
- **THEN** the wait is abandoned within one poll interval (250ms), and the run
  parks rather than reacting only when the wait expires

#### Scenario: An abandoned wait costs no budget

- **GIVEN** a bucket with no tokens, so the next `acquire()` must wait
- **WHEN** the sleep raises a stop signal and the run ends
- **THEN** the bucket refills on its ordinary schedule and the next run finds the
  token it would have found had the abandoned call never happened

#### Scenario: The remaining wait is published while it counts down

- **WHEN** a caller is waiting for the rate limit, whether for the bucket's own
  turn (~5s at 12/min) or a 429 penalty
- **THEN** the milliseconds remaining are observable once a second by the
  notification and by any screen showing the run

### Requirement: A whole-run ETA is not derived from the rate limit

The UI SHALL NOT present an estimated time to completion computed from the
configured permits per minute. Such an estimate restates the ceiling the run is
already waiting on: it does not move as the run progresses through a wait, and a
static number reads as a stalled app.

Binds the Android client.

#### Scenario: The wait is shown instead of an estimate

- **WHEN** a run is waiting on the rate limit
- **THEN** the notification shows the wait counting down —
  `API limiti bekleniyor 5 sn` — rather than a fixed `~1dk kaldı`
