## ADDED Requirements

### Requirement: Every final per-target outcome is one ledger event

Binds: **extension** and **Android** (emission), **backend** (storage).

Each client SHALL emit exactly one event per relation kind per target when that target reaches a
final outcome. Relation kinds are `BLOCK` (user), `TITLE_BLOCK` (user's titles), `MUTE` and
`FOLLOW`; the direction is `ADD` (ban mode `BAN`) or `REMOVE` (ban mode `UNDOBAN`). The result is
`OK`, `FAIL` or `SKIPPED` (already in the requested state, so no request was sent). The server also
stores `LEGACY` events, which no client emits (see "Run reports feed the ledger").

A rate-limited attempt (`ResultTypeHttpReq.TOO_MANY_REQ` in the extension, the equivalent in
Android `RelationClient`) is not final and SHALL NOT emit an event; only the attempt that settles
the target does.

An event SHALL carry: `event_id` (UUIDv4, generated on the device), `task_id`, `kind`,
`direction`, `result`, `target_eksi_id`, `target_nick` (when known), `actor_eksi_id`,
`actor_nick` and `occurred_at` (device clock, UTC ms). The client and version are the device's,
recorded once at enrolment. Nicks a run resolved MAY instead travel in the push's `nicks` list;
the extension sends them that way, from the run's id-to-nick map.

The extension records in `RelationHandler.performAction`, so every relation it applies is an
event -- including the unblock or unmute a follow does first. Android records in
`TargetRunner.performWithRetry`, where `RelationResult.AlreadyInState` is `SKIPPED`. Neither
records a rate limit that outlasted its retries, and Android records nothing for a target whose
session expired, since that target is attempted again once the user is back.

#### Scenario: One request type per target
- **WHEN** a task blocks and mutes user id 123 and both requests succeed
- **THEN** two events are emitted, `(BLOCK, ADD, OK)` and `(MUTE, ADD, OK)`, both with `target_eksi_id = 123`

#### Scenario: Rate limit then success
- **WHEN** a block on id 123 returns `TOO_MANY_REQ`, is retried and then succeeds
- **THEN** exactly one event `(BLOCK, ADD, OK)` is emitted for id 123

#### Scenario: Mixed outcome
- **WHEN** block succeeds and title-block fails for the same target
- **THEN** `(BLOCK, ADD, OK)` and `(TITLE_BLOCK, ADD, FAIL)` are emitted, and the target counts as failed in the run's `successful_action` exactly as today

#### Scenario: Setting off
- **WHEN** `syncHistory` is `false`
- **THEN** no event is written anywhere on the device, not even to the outbox, and the run's targets reach the server only through the `sendData` run report, as today

### Requirement: Ingest is idempotent and append-only

Binds: **backend**.

The server SHALL store each event once, keyed by `event_id`. A repeated `event_id` SHALL be
accepted and ignored, not rejected and not stored twice. Stored events SHALL NOT be updated by
any API; they are removed only by account deletion (see `history-sync`) or by the admin.
`received_at` SHALL be set by the server.

#### Scenario: Retry after lost response
- **WHEN** a device pushes a batch, the response is lost, and the device pushes the same batch again
- **THEN** the event count is unchanged and both responses report every event as accepted

#### Scenario: Partial batch
- **WHEN** a batch holds 3 new events and 2 already-stored ones
- **THEN** the 3 are stored, the 2 are ignored, and the response lists all 5 `event_id`s as accepted

### Requirement: Current relation state is materialised from events

Binds: **backend**.

The server SHALL maintain one relation-state row per `(account, actor_eksi_id, target_eksi_id,
kind)` holding `active`, `last_changed_at`, `first_added_at`, `last_added_at`, `add_count`,
`legacy_only`, and the id of the event that last changed `active`. It SHALL be updated in the same
database transaction as the event insert.

`active` follows these rules:

- `OK` or `LEGACY` + `ADD` sets `active = true`; `OK` or `LEGACY` + `REMOVE` sets `active = false`.
- `SKIPPED` + `ADD` sets `active = true`; `SKIPPED` + `REMOVE` sets `active = false` (the device
  observed the state, so it is known).
- `FAIL` SHALL NOT change state.
- An event whose `occurred_at` is older than the row's `last_changed_at` SHALL NOT change `active`,
  so a device that was offline cannot roll back a newer change.

`first_added_at` / `last_added_at` SHALL be the earliest / latest `occurred_at` of any `ADD` event
with result `OK`, `SKIPPED` or `LEGACY`, regardless of arrival order, and `add_count` the number of
such events. `legacy_only` SHALL be `true`
while every event that touched the row is `LEGACY`, and `false` from the first non-`LEGACY` one.

#### Scenario: Block then unblock
- **WHEN** events `(BLOCK, ADD, OK)` at t1 and `(BLOCK, REMOVE, OK)` at t2 > t1 arrive
- **THEN** the row is `active = false`, `last_added_at = t1`, and the target no longer counts as currently blocked by that actor

#### Scenario: Out-of-order delivery
- **WHEN** `(BLOCK, REMOVE, OK)` at t2 arrives before `(BLOCK, ADD, OK)` at t1 < t2
- **THEN** the row ends `active = false` and `last_added_at = t1`

#### Scenario: Failure does not flip
- **WHEN** an actor with an active block receives `(BLOCK, REMOVE, FAIL)`
- **THEN** the row stays `active = true`

### Requirement: Targets are identified by Ekşi numeric id

Binds: **backend**.

Ledger and relation-state rows SHALL key actors and targets by Ekşi numeric user id, not by a
foreign key to `api.EksiSozlukUser`. Every `(id, nick)` pair seen on ingest SHALL be recorded once
with its first and last seen times, so nick history survives without repeating the nick on every
event. Display SHALL use the most recently seen nick for the id.

#### Scenario: Nick change
- **WHEN** id 123 is blocked as `eski-nick` and later as `yeni-nick`
- **THEN** rankings show one row for id 123, labelled `yeni-nick`, counting both actors

### Requirement: Queued tasks are stored with a monotonic status

Binds: **extension** and **Android** (assigning ids, reporting status), **backend** (storage).

Each client SHALL assign a UUIDv4 `task_id` when a task is enqueued and send the task with its
source (`ban_source`), `ban_mode`, parameters (the same fields `/api/action/` already carries:
`click_source`, `fav_entry`, `fav_title`, `fav_author`, `time_specifier`, `date_criteria`,
`bulk_action`, `source_list`, and the target list when the task has one), `planned`, `performed`,
`successful`, `status`, `created_at` and `updated_at`.

Status SHALL only move forward along `QUEUED → RUNNING → {DONE, FAILED, CANCELLED, STOPPED}`.
The server SHALL merge an upsert by keeping the further-along status and the larger of each
counter; a terminal status SHALL never change.

#### Scenario: Stale upsert
- **WHEN** a task is `DONE` on the server and a delayed upsert says `RUNNING`
- **THEN** the task stays `DONE`

#### Scenario: Counters only grow
- **WHEN** the server holds `performed = 40` and an upsert says `performed = 35`
- **THEN** `performed` stays 40

### Requirement: History is permanent while the account exists

Binds: **backend**.

Events, tasks and relation state SHALL NOT be pruned by age. They SHALL be removed only when
their account is deleted.

#### Scenario: Old data still queryable
- **WHEN** an event is three years old and its account still exists
- **THEN** it is returned by the history and stats queries

### Requirement: Run reports carry the task id and send targets only when history is off

Binds: **extension** (`background.js` run report, `commHandler.js` `Action`) and **Android**
(`ActionReport.kt`), **backend** (`api.Action`).

The `/api/action/` run report SHALL gain an optional `task_id` (UUID), stored on `api.Action`
nullable, so each run's log and settings snapshot joins to its task and events. Reports without it
(shipped clients) SHALL be accepted unchanged.

While `syncHistory` is `true`, a client SHALL send `author_list` as `[]` and keep `author_list_size`
as the planned target count — the ledger already carries every target with its outcome. While
`syncHistory` is `false` and `sendData` is `true`, it SHALL send `author_list` in full, exactly as
today.

#### Scenario: History on
- **WHEN** a 300-target block run finishes with `syncHistory = true` and `sendData = true`
- **THEN** the run report has `author_list = []`, `author_list_size = 300` and the run's `task_id`

#### Scenario: History off
- **WHEN** the same run finishes with `syncHistory = false` and `sendData = true`
- **THEN** the run report carries all 300 targets in `author_list`, as today

#### Scenario: Shipped client
- **WHEN** a client from before this change posts a report with no `task_id`
- **THEN** the server stores it as today with `task_id = null`

### Requirement: Run reports feed the ledger

Binds: **backend**.

Every `api.Action` with a non-empty `author_list` SHALL produce `LEGACY` events, one per target and
mapped kind, under one synthetic legacy account per actor id. This covers both rows created before
this change (a one-time backfill command) and every new report (in the same transaction as the
`/api/action/` insert). Event ids SHALL be UUIDv5 of action id, target id and kind, so the backfill
and the live path can overlap and re-runs add nothing.

The `ban_mode` × `ActionConfig` flag → kind mapping SHALL be one explicit table shared by both
paths. Combinations with no mapping SHALL be skipped and counted, never guessed.

#### Scenario: Re-run
- **WHEN** the backfill runs twice
- **THEN** the second run reports 0 events created

#### Scenario: Undo-ban in legacy data
- **WHEN** a legacy `BAN` of id 123 is followed by a legacy `UNDOBAN` of id 123 by the same actor
- **THEN** the relation-state row for that pair is `active = false`

#### Scenario: Old client keeps feeding rankings
- **WHEN** a shipped client posts a report blocking ids 1–50
- **THEN** 50 `LEGACY` `BLOCK` events exist before the request returns, and the 50 targets count in the rankings

#### Scenario: No double count
- **WHEN** a run with `syncHistory = true` posts its events through sync and its report with `author_list = []`
- **THEN** each target is counted once, from the sync events only
