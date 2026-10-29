## ADDED Requirements

All requirements here bind the **backend** only. No client change.

### Requirement: Stats are admin-only

Every `/api/stats/` route, and the existing `most_banned/`, `most_banned_unique/` and `user_stat/`
routes, SHALL require `IsAdminUser` with session or basic authentication. Device tokens and
`X-API-Key` SHALL NOT grant access. No client SHALL display rankings or anyone else's data.

#### Scenario: Device token
- **WHEN** a request carries a valid device token and no admin session
- **THEN** the server answers 403

### Requirement: No stat scans the event log

Rankings, including the non-unique counts of the existing routes, SHALL be computed from
relation-state rows, and time series from the daily aggregates below. Neither SHALL read the event
table. The event table SHALL be read only through its account, actor-id or target-id indexes, by
per-user pages and `/api/sync/history`. So the cost of a global stat grows with the number of
distinct relations and days, not with the number of events.

#### Scenario: Query plan
- **WHEN** `EXPLAIN` is run for `top` (every scope) and `timeseries` on a database holding 5 M events
- **THEN** no plan node touches the event table

### Requirement: Rankings count distinct actors

`GET /api/stats/top` SHALL rank targets for a `kind` (`BLOCK`, `MUTE`, `TITLE_BLOCK`, `FOLLOW`) by
the number of **distinct actor ids** over the per-person relation rows, leaving out every actor or
target id whose last account check found it missing or mismatched, in one of three scopes:

- `current`: rows with `active = true`;
- `all`: rows with `first_added_at` set (blocked at least once, ever);
- `window`: rows with `last_added_at` within the last `days` days.

It SHALL accept `limit` (default 50, max 500) and `include_legacy` (default `true`; `false`
excludes `legacy_only` rows), and return `target_eksi_id`, the latest nick, the count, and the
number of distinct accounts behind it.

#### Scenario: Unblock lowers the current rank
- **WHEN** 10 actors block id 123 and 4 later unblock
- **THEN** `scope=current` reports 6 and `scope=all` reports 10

#### Scenario: Repeat blocks by one actor
- **WHEN** one actor blocks id 123 five times across five tasks
- **THEN** it contributes 1 to every scope

#### Scenario: Window
- **WHEN** an actor blocked id 123 forty days ago and again five days ago
- **THEN** that actor counts for id 123 in `scope=window&days=30`

### Requirement: Claimed accounts are checked against Ekşi Sözlük

Binds: **backend**.

The server cannot see an Ekşi session, so a nick on an event is a claim. A scheduled job SHALL
check, from logged-out public pages and at most 100 ids an hour with a pause between them, every
reporting actor id and the 200 most-counted block and mute targets: `/biri/<nick>` SHALL exist, and when
the account has entries, `/son-entryleri` SHALL tag them with the claimed id. It SHALL record `OK`,
`EXISTS` (real, id unconfirmed), `MISMATCH`, `MISSING` or `ERROR`; settled answers SHALL be
rechecked after 30 days and errors after an hour. The per-user summary SHALL show the result,
the user's accounts with their kind and age, and their devices.

#### Scenario: Made-up actor
- **WHEN** events claim actor id 999 under a nick Ekşi answers 404 for
- **THEN** after the next check, id 999 contributes to no ranking

#### Scenario: Ekşi unreachable
- **WHEN** the check cannot reach Ekşi
- **THEN** the id is marked `ERROR`, still counts, and is asked again an hour later

### Requirement: Daily aggregates are maintained at ingest

The server SHALL maintain, in the same transaction as each event insert and counting only events
actually inserted (not ignored duplicates):

- a daily tally: event count per `(day, kind, direction, result)`;
- a daily actor set: one row per `(day, kind, account, actor_eksi_id)`.

`day` SHALL be the `Europe/Istanbul` calendar day of `occurred_at`. Account deletion SHALL subtract
the account's contribution from the tally and delete its actor-set rows in the deletion
transaction. A rebuild command SHALL recompute both from the event table and report any
difference.

#### Scenario: Day boundary
- **WHEN** an event occurs at 23:30 UTC
- **THEN** it counts toward the next day, as Istanbul is UTC+3

#### Scenario: Duplicate push
- **WHEN** a batch is pushed twice
- **THEN** the tally counts its events once

#### Scenario: Deletion
- **WHEN** an account with 120 events on 2026-10-01 is deleted
- **THEN** that day's tally drops by exactly 120 and the rebuild command reports no difference

### Requirement: Daily time series

`GET /api/stats/timeseries?kind=&days=` SHALL return, per day, the tally split by direction and
result and the number of distinct active actors, read from the daily aggregates.

#### Scenario: Active actors per day
- **WHEN** two accounts with the same actor id and one other actor are active on one day
- **THEN** that day reports 2 active actors

### Requirement: Existing stat routes answer from the ledger

`most_banned/`, `most_banned_unique/` and `user_stat/` SHALL keep their URLs and response field
names and read from the ledger, legacy included, for kind `BLOCK`:

- `banned_by_unique_count`: distinct actors, as `top?scope=all`;
- `banned_by_count`: the sum of `add_count` over relation-state rows targeting the id;
- `ban_count` / `ban_unique_count`: the same, in the actor role;
- `action_for_ban_count`: still the number of `api.Action` runs in ban mode `BAN`.

`user_stat/` SHALL be paginated and compute its fields in a fixed number of queries per page
rather than per row.

#### Scenario: Same answer after migration
- **WHEN** the backfill has run and no new data has arrived
- **THEN** `most_banned_unique/` returns the same ids and counts as before the change, for every target whose legacy runs map to `BLOCK`, except where one Ekşi id had several `EksiSozlukUser` rows, which now merge into one

#### Scenario: Shape unchanged
- **WHEN** an existing consumer reads `most_banned/`
- **THEN** each item still has exactly `eksisozluk_name`, `eksisozluk_id` and `banned_by_count`

### Requirement: Per-user summary, history and relations

For any Ekşi id, the admin SHALL be able to get:

- `GET /api/stats/users/<eksi_id>/`: nicks seen, first and last activity, clients and device
  count, and per kind the number currently active as actor, the distinct actors currently
  targeting them, and all-time totals by result;
- `GET /api/stats/users/<eksi_id>/events?role=actor|target&kind=&before=&limit=`: the full ledger
  in either role, newest first, keyset-paginated by event id;
- `GET /api/stats/users/<eksi_id>/relations?role=actor|target&kind=&active=`: who they block or
  mute, or who blocks or mutes them, with `first_added_at`, `last_added_at` and
  `last_changed_at`;
- `GET /api/stats/users/<eksi_id>/tasks`: their queued and finished tasks, each linked to its
  `api.Action` run report when one carries the task's `task_id`.

#### Scenario: Who did X block
- **WHEN** the admin requests `relations?role=actor&kind=BLOCK&active=true` for id 42
- **THEN** every target id 42 currently blocks is listed with when it was first and last blocked

#### Scenario: Deep history
- **WHEN** an actor has 40,000 events
- **THEN** every page answers in under one second because pagination is by event id, not offset

### Requirement: The admin dashboard shows the same numbers

The admin index (`django_EksiEngel/admin.py` `index`) SHALL show the current top 20 for `BLOCK`
and `MUTE` and a 90-day series. The `EksiSozlukUser` change page SHALL link to that id's summary,
history and relations. Dashboard and JSON SHALL share one query module so they cannot disagree.

#### Scenario: Same answer twice
- **WHEN** the dashboard and `/api/stats/top?kind=BLOCK&scope=current&limit=20` are read at the same moment
- **THEN** they list the same ids in the same order with the same counts

### Requirement: Admin reads of one person's data are logged

Every request to a `/api/stats/users/<eksi_id>/` route, and every admin page showing one person's
history -- the event and task lists searched or filtered by an Ekşi id, and a single event or
task -- SHALL append a row recording the admin user, the Ekşi id, the route and the time. The log
SHALL be readable in the admin and not editable there.

#### Scenario: Lookup leaves a trace
- **WHEN** an admin opens the history of id 42
- **THEN** an access-log row names that admin, id 42 and the time
