# android-operations Specification

## Purpose
TBD - created by archiving change android-operations-engine. Update Purpose after archive.
## Requirements
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
and then calls `onTimeout()`. At twelve actions/minute a 10,000-user run needs
about 14 hours. These cannot be reconciled, so multi-session execution is
structural.

Time SHALL count against the budget only while the foreground service holds the
process. With a visible activity the operation runs unconstrained, so a user who
leaves the app open finishes sooner.

**The client SHALL warn before the budget is spent, not only after.** At a
configurable fraction of the budget — default 80% — it SHALL post an actionable
notification stating the remaining work and offering to continue immediately by
opening the app. Tapping SHALL bring the app forward, at which point the
operation continues without consuming budget.

The warning SHALL fire at most once per slice. Repeating it would train the user
to dismiss the one notification that matters.

The client SHALL NOT select a foreground-service type that avoids the cap.
`mediaPlayback`, `location` and `specialUse` are all uncapped and all would
misrepresent the service to the platform and to Play review.

The user SHALL be told the estimate before starting, not discover it mid-run.

#### Scenario: Budget exhausted

- **WHEN** the soft budget is reached
- **THEN** the operation checkpoints, enters `PAUSED_BUDGET`, and a continuation is scheduled

#### Scenario: Warning before exhaustion

- **WHEN** consumed budget first crosses the warning threshold
- **THEN** a high-importance notification offers to continue now by opening the app, stating how much work remains

#### Scenario: The warning does not repeat

- **WHEN** the threshold has already been crossed in this slice
- **THEN** no further warning is posted, however many checkpoints follow

#### Scenario: Opening the app continues the work for free

- **WHEN** the user brings the app to the foreground mid-operation
- **THEN** billing stops and the operation continues without consuming budget

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

### Requirement: A date-based bulk run is a source, a criterion and an action

`ban_source` 12 SHALL be composed by the user from the three choices the
extension offers (`notification.html:143-188`), not picked from a fixed list of
combinations.

**Source** — `DateBulkSource` in `enums.js:113-117`:

| choice | what the run reads |
| --- | --- |
| `BLOCKED_USERS` | `/relation-list?relationType=m` |
| `MUTED_USERS` | `/relation-list?relationType=u` |
| `AUTHOR_LIST` | the stored author list, resolved before the run |

**Criterion** — `DateFilterCriteria` in `enums.js:90-95`: NEWER_THAN,
OLDER_THAN, BEFORE_DATE, AFTER_DATE. The first two take a day count; the last
two take a calendar date.

**Action** — `DateBulkAction` in `enums.js:102-111`, each of the eight mapping to
a `BanMode`, a `TargetType`, and optionally a second relation applied only after
the first succeeds:

| action | mode | target | then |
| --- | --- | --- | --- |
| `ENGELLE` | BAN | USER | — |
| `SESSIZE_AL` | BAN | MUTE | — |
| `ENGEL_KALDIR` | UNDOBAN | USER | — |
| `SESSIZDEN_CIKAR` | UNDOBAN | MUTE | — |
| `TAKIP_ET` | BAN | FOLLOW | — |
| `TAKIPTEN_CIKAR` | UNDOBAN | FOLLOW | — |
| `ENGEL_KALDIR_VE_TAKIP_ET` | UNDOBAN | USER | FOLLOW |
| `SESSIZDEN_CIKAR_VE_TAKIP_ET` | UNDOBAN | MUTE | FOLLOW |

The two combined actions SHALL apply the follow only after the undo succeeds,
through the same pairing the author list uses, so a user whose unblock failed is
not followed.

The `ban_source` reported SHALL be 12 for the two relation-list sources. The
author-list source SHALL report `LIST` (4), because it is the author list being
run — which is what every other author-list run reports, and what the shared
backend's rows mean.

#### Scenario: The muted source reads the muted list

- **WHEN** a date-based run is started with source `MUTED_USERS`
- **THEN** it scrapes `relationType=u` and not `relationType=m`

#### Scenario: The blocked source reads the blocked list

- **WHEN** a date-based run is started with source `BLOCKED_USERS`
- **THEN** it scrapes `relationType=m`

#### Scenario: The author list source runs the stored list

- **WHEN** a date-based run is started with source `AUTHOR_LIST`
- **THEN** the nicks are resolved before the run and carried in the request, and no relation list is scraped

#### Scenario: A combined action follows only what it unblocked

- **WHEN** `ENGEL_KALDIR_VE_TAKIP_ET` runs and one user's unblock fails
- **THEN** that user is not followed, and the rest are

#### Scenario: An unsupported combination cannot be expressed

- **WHEN** the chooser is opened
- **THEN** source, criterion and action are picked independently, and every one of the twelve source-action pairs is reachable

### Requirement: The saved date rules narrow only what restricts someone

`dateFilterRules` SHALL gate a run only when that run puts a restriction on
someone who did not have one: blocking, muting, or blocking titles. It SHALL NOT
gate an undo of any of those, a follow, an unfollow, or the blocked-to-muted
migration.

They are protection, and protection has a direction. Applied to every operation
they did the opposite of their purpose: the default ten-year rule spared
decade-old accounts from "tüm engelleri kaldır", so an account the user was
trying to unblock stayed blocked, and silently — a skipped target is not a
failure and appears nowhere in the counts.

This is what the extension does. It filters in three places — `background.js:772`
(FAV), `:836` (FOLLOW) and `:908` (TITLE) — and every one of them is a block.
`UNDOBANALL`, `UNMUTEALL` and the migration reach no filtering code at all.

Following SHALL NOT count as a restriction. It adds a relation but takes nothing
away from the person it names, and a rule about protecting old accounts has
nothing to say about it.

A run the rules do not gate SHALL NOT resolve registration dates. Resolving one
costs a network read per uncached nick, and on a full unblock that is one read
per person for an answer nothing then consults.

#### Scenario: Unblocking everyone unblocks everyone

- **WHEN** an `UNDOBANALL` run executes while the saved rules protect accounts older than ten years
- **THEN** every blocked account is unblocked, including the decade-old ones

#### Scenario: Blocking is still narrowed

- **WHEN** a run blocks, mutes, or blocks titles while the saved rules are enabled
- **THEN** targets failing a rule are skipped

#### Scenario: Following is not narrowed

- **WHEN** a run follows or unfollows while the saved rules are enabled
- **THEN** no target is skipped for its registration date

#### Scenario: A migration is not narrowed

- **WHEN** the blocked-to-muted migration runs while the saved rules are enabled
- **THEN** every blocked account is migrated, because none of them is newly restricted

#### Scenario: An unfiltered run costs no date lookups

- **WHEN** a run the saved rules do not gate executes
- **THEN** no registration date is fetched for any target

### Requirement: A date-based run carries its own criterion

The criterion chosen for a `DATE_BASED_BULK` run SHALL travel in the
`OperationRequest` and SHALL override the saved `dateFilterRules` for that run
alone.

It SHALL NOT be written back to settings. The saved rules are standing
protection — the default one exists to keep decade-old accounts out of every
future run — and a one-off "unblock everyone I blocked before 2020" that edited
them would disarm that protection permanently, invisibly, and for operations
started from a different screen.

It SHALL apply whatever direction the run takes. A criterion is a target
selector the user has just typed, not protection, so the requirement above does
not reach it — and the extension's own default composition is an unmute (muted
users, older than 3650 days, sessizden çıkar; `config.js:58-66`), which a
criterion that worked only on blocking could not express at all.

Every other source SHALL keep using the saved rules, subject to the requirement
above. The override is a property of this one source, not a new general
mechanism.

The rule SHALL be resolved once per run, at the same point the saved rules are
(`OperationWorker.kt:411-426`), so a run that pauses and resumes hours later
applies the criterion it was started with rather than the settings as they now
stand.

A criterion expressed in months or years SHALL be normalised to days before it
is stored in the request. `DateFilterRule.days` is what the predicate compares,
and carrying a unit alongside it would be a second representation of one number.

#### Scenario: The run's criterion beats the saved rules

- **WHEN** a date-based run is started with OLDER_THAN 3650 days while settings hold a NEWER_THAN 3650 rule
- **THEN** the run acts on accounts older than ten years, and settings are unchanged afterwards

#### Scenario: Other operations are unaffected

- **WHEN** any operation other than `DATE_BASED_BULK` runs
- **THEN** it is gated by the saved `dateFilterRules` and by nothing else

#### Scenario: A resumed run keeps its criterion

- **WHEN** a date-based run is paused, the settings rules are edited, and the run is resumed
- **THEN** it continues under the criterion it was started with

#### Scenario: A run with no criterion is refused

- **WHEN** a date-based run is requested with no criterion
- **THEN** it is refused with a message, because a filter that allows everything would act on the whole list

### Requirement: The bulk chooser remembers the last composition

The four choices SHALL be persisted and restored, matching
`createDefaultDateBulkConfig` (`config.js:58-66`). A fresh install SHALL default
to muted users, OLDER_THAN, 3650 days, sessizden çıkar — the extension's values,
so someone arriving from it recognises the dialog.

They SHALL be stored as configuration, not as a rule. Restoring a previous
composition must not add anything to `dateFilterRules`.

#### Scenario: A fresh install shows the extension's defaults

- **WHEN** the chooser is opened for the first time
- **THEN** it is set to muted users, OLDER_THAN, 3650 days, sessizden çıkar

#### Scenario: The previous composition comes back

- **WHEN** a run is started with a composition and the chooser is reopened
- **THEN** the same four choices are selected

#### Scenario: Remembering does not create a rule

- **WHEN** a composition is remembered
- **THEN** `dateFilterRules` is unchanged

