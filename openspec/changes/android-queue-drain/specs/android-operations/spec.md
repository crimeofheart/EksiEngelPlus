## ADDED Requirements

### Requirement: Queued operations drain without user action

When an operation finishes or is stopped while others are queued, the worker
that ran it SHALL start the next one itself, inside the foreground service it
already holds. It SHALL keep doing so until the queue is empty. It SHALL NOT
leave the queue waiting for the app to be opened, refreshed or restarted.

A run that parks on purpose (paused, session lost, budget exhausted, or failed)
SHALL keep holding the queue as before.

#### Scenario: Queue drains in the background

- **WHEN** three operations are queued and the app is in the background with the screen off
- **THEN** each starts after the previous one finishes, with no user action

#### Scenario: Adopted run is not mistaken for a dead one

- **WHEN** the reconciler runs while the worker is executing a run it took off the queue
- **THEN** that run stays RUNNING rather than being marked INTERRUPTED

#### Scenario: Request arrives as a run finishes

- **WHEN** a request is made at the moment the live run finishes
- **THEN** it is either taken by the finishing worker or started on its own, and is never left on the queue with nothing to drain it

#### Scenario: Unreadable queue entry

- **WHEN** the head of the queue no longer decodes
- **THEN** it is dropped and the entry behind it is started

### Requirement: A budget continuation starts with a fresh budget

A run resumed from `PAUSED_BUDGET` SHALL start with no foreground time spent.
Both ways back to it reset the platform cap: the continuation waits out the
rolling window, and a manual resume brings the app to the foreground. Every other
resumed run SHALL keep the time it had already spent.

The foreground budget SHALL be shared by every run one worker carries, because
the platform cap is on the app's foreground service, not on each run.

#### Scenario: Continuation after the budget ran out

- **WHEN** the continuation scheduled by a `PAUSED_BUDGET` run starts
- **THEN** the run makes progress instead of parking again before its first action

#### Scenario: Queue outlasting the budget

- **WHEN** queued runs together use the whole budget in one foreground session
- **THEN** the run that crosses it parks as `PAUSED_BUDGET` rather than running until the system ends the service

### Requirement: Opening the app restores the foreground allowance

The worker SHALL follow whether any of the app's screens is showing, for as long
as it runs. While one is, the budget SHALL NOT be billed. Bringing the app to the
foreground SHALL reset the consumed budget to zero and allow the warning again,
because the platform resets its own timer at that moment. When the app leaves the
screen, billing SHALL start again from that moment.

#### Scenario: A run watched in the app

- **WHEN** an operation runs for longer than the soft budget with the app on screen
- **THEN** it does not park as `PAUSED_BUDGET`

#### Scenario: Leaving the app after a long background stretch

- **WHEN** a run has used most of its budget in the background, the user opens the app, then leaves it
- **THEN** the run has the full budget again from the moment the app left the screen

#### Scenario: Moving between the app's screens

- **WHEN** the user goes from the browser to İşlem durumu
- **THEN** billing does not start in between
