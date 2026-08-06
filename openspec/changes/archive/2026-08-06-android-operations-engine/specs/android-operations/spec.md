# android-operations

Binds: Android. Ports the lifecycle of `resumableOperation.js`, `queue.js` and the
`background.js` dispatcher.

## ADDED Requirements

### Requirement: Operations run in a foreground service via WorkManager

A long-running operation SHALL execute in a `CoroutineWorker` promoted to a
foreground service of type `dataSync`, enqueued as unique work.

Not a hand-rolled `Service`: WorkManager persists the request in its own database,
so process death, OEM task killers and reboot all re-run the work rather than
losing it. That is the same problem `resumableOperation.js` solves by hand with
`resumableOp_<id>` keys, solved by the platform instead.

#### Scenario: Only one operation at a time

- **WHEN** an operation is requested while another is running
- **THEN** the existing one continues and the new request is queued rather than run concurrently

#### Scenario: Survives process death

- **WHEN** the process is killed mid-operation
- **THEN** the work is re-run and resumes from its last checkpoint

### Requirement: Execution is chunked across days

The worker SHALL track foreground-service time against a soft budget below the
platform cap, checkpoint when it is exhausted, enter `PAUSED_BUDGET`, and schedule
a continuation for when the budget resets.

Android 15 caps a `dataSync` foreground service at roughly 6 hours per rolling 24
and then calls `onTimeout()`. At 12 actions/minute a 10,000-user run needs about
14 hours. These cannot be reconciled, so multi-session execution is structural.

Time SHALL count against the budget only while the foreground service holds the
process. With a visible activity the operation runs unconstrained, so a user who
leaves the app open finishes sooner.

The user SHALL be told the estimate before starting, not discover it mid-run.

#### Scenario: Budget exhausted

- **WHEN** the soft budget is reached
- **THEN** the operation checkpoints, enters `PAUSED_BUDGET`, and a continuation is scheduled

#### Scenario: Platform timeout

- **WHEN** `onTimeout()` fires before the soft budget
- **THEN** the same checkpoint-and-reschedule path runs

#### Scenario: Foreground work is not billed

- **WHEN** the operation runs with the app visible
- **THEN** no budget is consumed

#### Scenario: Estimate shown up front

- **WHEN** a bulk operation is confirmed
- **THEN** the item count and estimated duration are stated, including that it may span days

### Requirement: Pause and stop are cooperative

The state machine SHALL implement IDLE, RUNNING, PAUSING, PAUSED, STOPPING,
STOPPED and COMPLETED, plus `PAUSED_AUTH`, `PAUSED_BUDGET` and `PAUSED_NETWORK`.

Transitions SHALL be requested, not forced: the worker observes them at checkpoints
between units of work, so an in-flight mutation is never abandoned half-done.
Waiting for a pause to be acknowledged SHALL time out after 30 seconds, after
which the operation is force-paused and its checkpoint marked as needing
re-verification on resume.

The state machine SHALL be pure Kotlin with no IO, so every transition is
unit-testable without a device.

#### Scenario: Pause takes effect at a checkpoint

- **WHEN** a pause is requested mid-unit
- **THEN** the current unit completes, the state becomes PAUSED, and no further units start

#### Scenario: Acknowledgement times out

- **WHEN** a pause is not acknowledged within 30 seconds
- **THEN** the operation is force-paused and the checkpoint is flagged for re-verification

#### Scenario: Stop is distinct from pause

- **WHEN** a stop is requested
- **THEN** the operation ends and is not resumable, while a pause remains resumable

### Requirement: Progress and state are written, never pushed to a screen

The engine SHALL persist progress and state to the database and SHALL NOT depend
on any UI surface existing.

This is the single biggest correction to the extension's design. `background.js`
find-or-creates a tab and waits for it to answer
(`ensureNotificationTabExistsAndIsReady`, `:86-164`), then drives part of its
control flow through `chrome.tabs.sendMessage`. Closing that tab strands the
operation. On Android the engine writes to Room and the UI observes; the only
path from UI to engine is a command.

#### Scenario: No UI attached

- **WHEN** an operation runs with no screen open
- **THEN** it proceeds normally and its progress is readable afterwards

#### Scenario: UI observes rather than polls

- **WHEN** a screen is open during an operation
- **THEN** it receives progress by observing stored state

### Requirement: Checkpoints commit atomically with their effects

A checkpoint and the list rows it describes SHALL be written in one transaction.

Writing the cursor separately from the content is the one place a crash corrupts
user-visible state: a cursor ahead of its rows silently skips users, and one
behind re-processes them.

Checkpoint frequency SHALL be every few units, and every unit for destructive
operations.

#### Scenario: Crash between cursor and rows is impossible

- **WHEN** the process dies mid-checkpoint
- **THEN** either both the cursor and the rows are persisted, or neither is

### Requirement: Interrupted operations are reconciled at startup

On startup, any checkpoint in RUNNING whose WorkManager request is neither
enqueued nor running SHALL be marked INTERRUPTED and offered for resume.

Ports the reconciliation at `background.js:23-59`, which the extension performs
manually because it has no equivalent of WorkManager's bookkeeping.

#### Scenario: Stale RUNNING state after a crash

- **WHEN** the app starts and finds a RUNNING checkpoint with no live work
- **THEN** it becomes INTERRUPTED and the user is offered resume

### Requirement: Session loss parks the operation for a human

On session expiry the engine SHALL checkpoint, enter `PAUSED_AUTH`, stop the
foreground service, and surface a route back to the WebView login. It SHALL NOT
retry or attempt re-authentication.

`/giris` is behind Cloudflare Turnstile, so no automated retry can ever succeed —
retrying would burn the remaining budget failing.

Observing that a session exists again SHALL allow the operation to resume.

#### Scenario: Expiry mid-run

- **WHEN** an action reports session expiry
- **THEN** the operation checkpoints, pauses for authentication, and the foreground service stops

#### Scenario: Resume after re-login

- **WHEN** a session is detected while an operation is in `PAUSED_AUTH`
- **THEN** it becomes resumable

### Requirement: Progress is visible and controllable from the notification

A running operation SHALL show a low-importance progress notification carrying
counts, an estimate, and pause and stop actions. Terminal and attention-requiring
outcomes SHALL use a separate high-importance channel.

Notification actions SHALL reach the worker through persisted commands, so they
work when no screen is open.

`POST_NOTIFICATIONS` SHALL be requested at first operation start rather than at
launch, and denial SHALL degrade rather than block: the operation still runs and
in-app progress remains authoritative.

#### Scenario: Pause from the notification with no UI open

- **WHEN** the user taps pause on the notification
- **THEN** the operation pauses at its next checkpoint

#### Scenario: Notifications denied

- **WHEN** the permission is refused
- **THEN** the operation still runs and the user is told background progress will not be visible

### Requirement: The six single-shot sources are supported

The engine SHALL support the sources in `background.js`: SINGLE (1), FAV (2),
FOLLOW (3), LIST (4), UNDOBANALL (5) and TITLE (6).

Each SHALL resolve its target set, then apply the requested relation to each
member through the pacer, checkpointing as it goes. The `ban_source` integers
SHALL match `enums.js`, because they are keys in a shared database.

#### Scenario: Block everyone who favourited an entry

- **WHEN** a FAV operation runs for an entry id
- **THEN** favouriters are resolved from both the standard and novice endpoints, ids are backfilled, and each is blocked through the pacer

#### Scenario: Block a title's participants

- **WHEN** a TITLE operation runs with a time specifier
- **THEN** the matching pages are paginated and each distinct author is blocked once

#### Scenario: Unblock everyone

- **WHEN** an UNDOBANALL operation runs
- **THEN** the blocked list is scraped and each entry is unblocked, checkpointing per unit
