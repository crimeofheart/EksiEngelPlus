## ADDED Requirements

### Requirement: A run is live from the moment it begins

The worker SHALL mark its checkpoint `RUNNING` before it performs any network
work, without disturbing the cursor. State MUST NOT wait on the first checkpoint
write, which happens only after the first action lands.

Binds the Android client.

#### Scenario: A run opening with a cooldown is not reported as not-started

- **GIVEN** a run whose first action waits on the rate limit
- **WHEN** İşlem durumu is opened during that wait
- **THEN** the run appears under `süren ve bekleyen`, not under `sıradakiler` as
  `başlamadı`

#### Scenario: A run in its opening wait blocks the queue behind it

- **GIVEN** a run that has begun but not yet checkpointed
- **WHEN** a second request arrives
- **THEN** the live count includes the run, and the second request is queued
  rather than started alongside it

#### Scenario: Marking a run started preserves where it was

- **WHEN** a resumed run is marked `RUNNING`
- **THEN** its cursor, processed counts and `startedAt` are unchanged, so the
  resume continues from the recorded position

### Requirement: Signals are honoured from anywhere in the run

Pause, stop and budget signals SHALL park the run wherever it stands, including
when raised from inside a rate-limit wait — which happens below the target loop,
in the retry path. A signal MUST NOT escape the runner: doing so reports the
user's own Durdur back to them as `İşlem başarısız`.

Saving the parked state SHALL tolerate the checkpoint itself signalling. The
context raises a stop when the row is gone, which is how a cancel reaches a live
run, and a signal raised while handling a signal would become the same spurious
failure.

Binds the Android client.

#### Scenario: Stopping inside a wait parks rather than fails

- **WHEN** a stop signal is raised from inside the permit wait
- **THEN** the runner returns `STOPPED` and checkpoints the index it had reached,
  and no failure is reported to the user

#### Scenario: Cancelling a live run is not a failure

- **GIVEN** a run whose checkpoint row has been deleted by a cancel
- **WHEN** the runner tries to park and the checkpoint signals in turn
- **THEN** the run ends as `STOPPED`

### Requirement: The queue drains whenever nothing is live

The queue SHALL be drained on any transition to an idle system, not only when a
run reaches a terminal state. A queue that outlives the process — the app
killed, or reinstalled — has nothing left to trigger it, so startup
reconciliation MUST check.

The drain SHALL start its run directly rather than re-entering the enqueue path,
which consults the live check and could return the run to the queue it came from.

Binds the Android client.

#### Scenario: A queue survives a reinstall and still runs

- **GIVEN** queued operations and no live run, after the process has restarted
- **WHEN** the app starts
- **THEN** the next queued operation begins, rather than waiting for a run that
  will never finish

### Requirement: Cancellation is never reported as failure

Code that reports a failure to the user SHALL NOT catch `CancellationException`.
`runCatching` catches `Throwable`, so leaving a screen mid-operation surfaced the
coroutine machinery's own bookkeeping as `işlem başarısız: Job was cancelled`.
Swallowing it also leaves a scope that never finishes unwinding.

Binds the Android client.

#### Scenario: Leaving a screen mid-import reports nothing

- **WHEN** a screen is closed while an import is running
- **THEN** the cancellation propagates and no failure message is shown

### Requirement: Every announcement of a run offers a way to it

Wherever the app tells the user a run has started or finished, it SHALL offer a
route to İşlem durumu. The screen the user is standing on shows no trace of a run
that was just handed off.

The route SHALL be an implicit intent private to the app, not a class reference:
`:feature:lists` depends on `:ops:runtime`, so naming the activity from the
notifier would invert that dependency. Because nothing checks the action string
and the intent-filter agree at compile time, and a mismatch is invisible at
runtime, an assembly test SHALL assert the action resolves.

Binds the Android client.

#### Scenario: The progress notification leads to the detail

- **WHEN** a run is in progress
- **THEN** the notification carries a `Göster` action, and tapping its body opens
  İşlem durumu

#### Scenario: The completion notification leads to the detail

- **WHEN** a run finishes
- **THEN** tapping `İşlem tamamlandı` opens İşlem durumu and dismisses the
  notification

#### Scenario: Queuing a run offers a way to see it

- **WHEN** a run is queued from a list screen
- **THEN** the message shown carries a `göster` action

### Requirement: A run states its size before it acts

The number of targets SHALL be published before the first action, not after it.
Otherwise a run waiting out its first cooldown reads `0 / 0 · API limiti
bekleniyor` — indistinguishable from a run against nobody, which is how a
genuine 37-follower run came to look like a minute spent on an empty one.

A run with no targets SHALL complete without consulting the pacer and without
writing a checkpoint. Nothing to do costs nothing.

Binds the Android client.

#### Scenario: A run waiting for its first permit says what it will do

- **GIVEN** a run over 37 followers whose first action waits on the rate limit
- **WHEN** İşlem durumu is read during that wait
- **THEN** the row reads `0 / 37`, not `0 / 0`

#### Scenario: A run against nobody costs nothing

- **WHEN** an operation resolves zero targets — an entry with no favouriters, an
  author with no followers
- **THEN** it completes immediately, taking no permit and waiting no cooldown

### Requirement: The screen and the notification agree

İşlem durumu SHALL show the same rate-limit countdown the notification shows, for
the run it belongs to. A run in a wait MUST NOT read as stalled on one surface
while visibly ticking on the other.

The wait SHALL be held in memory, not persisted: it is worth exactly as long as
the worker it describes, and one restored after a process death would count down
a pause nothing is observing. It SHALL be cleared when the wait ends by any
route, including a signal.

Binds the Android client.

#### Scenario: The running row counts down

- **WHEN** a run is waiting on the rate limit
- **THEN** its row reads `… · API limiti bekleniyor 4 sn`, updating each second

#### Scenario: Stopping mid-wait leaves nothing counting down

- **WHEN** the user stops a run while it is waiting
- **THEN** the countdown is cleared rather than left ticking against a run that
  has ended
