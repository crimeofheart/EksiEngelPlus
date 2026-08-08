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

- **GIVEN** a spent window, so the next `acquire()` must wait
- **WHEN** the sleep raises a stop signal and the run ends
- **THEN** the window turns over on its ordinary schedule and the next run finds
  the whole allowance it would have found had the abandoned call never happened

#### Scenario: The remaining wait is published while it counts down

- **WHEN** a caller is waiting for the rate limit, whether because the window is
  spent or because a request was rejected
- **THEN** the milliseconds remaining are observable once a second by the
  notification and by any screen showing the run

### Requirement: Mutations are paced as a fixed window, and every wait is a whole one

The limit SHALL be enforced as 12 actions per 61-second window, spent as fast as
they go and then waited out in full — not as a leaky bucket returning one permit
every 5s.

The wait SHALL be a whole window measured from the moment the allowance runs
out, not from when the window opened. Measuring from the open subtracts however
long the twelve took to send, so the first cooldown is 61s and the next 56s, and
it assumes our clock agrees with the server about when the window began — the
assumption `Retry-After` already disproved.

`Retry-After` SHALL be ignored. The header describes the remainder of a window
whose start the client cannot see, so honouring it produced cooldowns of 23 and
24 seconds that placed the next burst back inside the same window and tripped
the limit again. A rejection SHALL cost one whole fresh window, and the window
SHALL be treated as beginning when that penalty ends.

61 seconds rather than 60: landing exactly on the boundary races the server's own
bookkeeping. The extension pads for the same reason (`background.js:639` waits
62s).

Binds the Android client. The extension does not pace proactively at all.

#### Scenario: A spent window waits out the whole minute

- **GIVEN** twelve actions already performed in the current window
- **WHEN** a thirteenth is requested
- **THEN** the caller waits the full 61 seconds, not a fraction of it

#### Scenario: Every cooldown is the same length

- **GIVEN** twelve actions that each took real time to send
- **WHEN** the allowance runs out a second and a third time
- **THEN** each wait is 61 seconds, not 61 then 56

#### Scenario: A rejection costs a full window whatever the header says

- **WHEN** the server rejects a request with `Retry-After: 23`
- **THEN** the cooldown is 61 seconds, and the next window is counted from its
  end

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
