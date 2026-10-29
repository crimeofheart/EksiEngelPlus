## Context

The server today receives one `Action` per **run** (`api/models.py:97-141`): who ran it, the
source, counts, and `author_list`, an M2M to every target (`models.py:110`). It does not know
which targets succeeded, never hears about an unblock as such, and has no notion of a queue. The
"most banned" views (`api/views.py:44-58`) count `author_list` memberships for `ban_mode = BAN`.
So a target blocked and unblocked ten times by one person scores 10 and is not currently blocked
at all. `user_stat/` (`views.py:61-65`) runs five aggregate queries per user row
(`serializers.py:23-67`) over every `EksiSozlukUser`, so it gets slower as the table grows.

On the devices, the extension persists its queue under `queueData` and caps completed items at 100
(`storageHandler.js:238-259`). Android keeps `queued_task`, `operation_checkpoint` and
`completed_operation` in Room (`android/core/database/.../Entities.kt:69-136`). Nothing crosses
devices.

The per-target outcome already exists in memory: `RelationHandler.performAction`
(`relationHandler.js:13-97`) resolves a status per kind (`resUser`, `resTitle`, `resMute`,
`resFollow`) and then folds them into a single success flag. Android `RelationClient` mirrors it.

Constraints:
- Postgres in production (`settings.py:94`).
- A bare VPS with gunicorn and no background worker, so no Celery or cron.
- `frontend/app` has zero dependencies.
- The extension holds the `tabs`, `storage` and `notifications` permissions only.

## Goals / Non-Goals

**Goals:**
- One permanent, per-target, append-only history across both clients and the run reports of
  shipped clients.
- One queue view across a person's devices, with cancel and takeover that cannot double-execute.
- Rankings that measure who is blocked *now* by how many distinct people, as well as all-time and
  over a window, at a cost that does not grow with the number of events.
- Admin per-user history in both roles (what they did, what was done to them).
- On by default, one switch to stop, one action to erase.

**Non-Goals:** see the proposal. In short: no Ekşi identity proof, no hashing, no server-initiated
execution, no end-user stats or history screen, and `/api/action/` stays.

## Decisions

### D1. A new Django app `history`, beside `api`

The new tables live in their own app. `api` changes by exactly one nullable column
(`Action.task_id`) and one extra write in `CollectActionDataSerializer.create`
(`serializers.py:254-275`). The migration is additive and `migrate history zero` reverses it.

*Alternative:* extend `Action` with per-target rows (`ActionTarget`). Rejected: `Action` is
per-run diagnostics with a 10 kB log. History needs per-target rows, idempotency and queue state,
and mixing them makes deletion and backfill harder.

### D2. Data model

```
Account        id uuid, kind {DEVICE|LEGACY}, legacy_actor_eksi_id int UNIQUE NULL,
               change_seq bigint, created_at
JoinKey        key_hash UNIQUE, account→, created_at          (one per browser profile, D8)
Device         id, account→, join_key→ NULL, token_hash UNIQUE, client {EXTENSION|ANDROID},
               label, version, created_at, last_seen_at, revoked_at,
               events_day date, events_today int              (daily cap, D12)
PairingCode    code_hash, account→, expires_at, used_at
PairingRequest code→, claimer→ Device, status {PENDING|APPROVED|DENIED}, created_at,
               decided_at, join_key (handed over once, then cleared)
Task           id uuid (client task_id), account→, owner_device→, epoch int,
               actor_eksi_id int, ban_source, ban_mode, params jsonb,
               status smallint, planned/performed/successful int,
               cancel_requested bool, created_at, updated_at, change_seq bigint
Event          id bigserial, event_uuid uuid UNIQUE, account→, device→ null, task→ null,
               action→ null (api.Action, LEGACY only), actor_eksi_id int, target_eksi_id int,
               kind smallint, direction smallint, result smallint, occurred_at, received_at
RelationState  account→, actor_eksi_id, target_eksi_id, kind,
               active, last_changed_at, last_event_id,
               first_added_at, last_added_at, add_count int, legacy_only bool
               UNIQUE (account, actor_eksi_id, target_eksi_id, kind)
DailyTally     day date, kind, direction, result, n bigint   UNIQUE (day, kind, direction, result)
DailyActor     day date, kind, account→, actor_eksi_id       UNIQUE (day, kind, account, actor_eksi_id)
ActorRelation  actor_eksi_id, target_eksi_id, kind, active, last_changed_at,
               first_added_at, last_added_at, add_count, legacy_only, accounts
               UNIQUE (actor_eksi_id, target_eksi_id, kind)   (per person, D13)
AccountCheck   eksi_id PK, nick, status {OK|EXISTS|MISMATCH|MISSING|ERROR}, found_id,
               checked_at                                     (D12)
NickSeen       eksi_id, nick, first_seen_at, last_seen_at    UNIQUE (eksi_id, nick)
AdminAccess    admin_user→, eksi_id, path, at
api.Action     + task_id uuid NULL (indexed)
```

Integers and small enums keep an `Event` row at about 120 bytes of heap. Nicks are not repeated
per event (`NickSeen` holds them), and client and version live on `Device`, not on each event.

Indexes:
- `Event`: `(account, id DESC)`, `(actor_eksi_id, id DESC)`, `(target_eksi_id, id DESC)`.
  `device`, `task` and `action` are declared with `db_index=False`, because nothing filters on
  them and each index costs about 30 bytes per event.
- `RelationState`: a covering `(kind, target_eksi_id, actor_eksi_id) INCLUDE (active,
  legacy_only, add_count, first_added_at)` for every ranking, `(kind, last_added_at)` for the
  window scope, and `(actor_eksi_id, kind)` and `(target_eksi_id, kind)` for the per-user routes.
  The covering index replaced the partial `(kind, target_eksi_id) WHERE active` this design first
  named: at 3 M relations the partial one still fetched every row from the heap and took 6-15 s;
  the covering one answers in about 0.5 s as an index-only scan.
- `Task`: `(account, change_seq)`. `Task.account`, `Event.account` and `RelationState.account`
  carry no single-column index of their own, since a composite index or the unique key already
  leads with the column. `DailyActor` relies on its unique key for `(day, kind)`.

**No `first_active_at`.** The design first kept "when the current active period began". It cannot
be derived identically by the incremental path and the rebuild once events arrive out of order, so
it was dropped for `first_added_at` and `last_added_at`, which both paths compute exactly.

**Keyed by Ekşi integer id, not by FK to `api.EksiSozlukUser`.** That table has no uniqueness on
`eksisozluk_id`. Lookups match on name *and* id (`serializers.py:139-173`, the `TODO` comments),
so a nick change creates a second row. **Carried forward deliberately:** fixing it means
deduplicating production rows that the current dashboard reads. The ledger sidesteps the problem
instead of depending on that table, and joins to it by `eksisozluk_id` only for display.

### D3. One set-based ingest path, used by sync and by run reports

`history/ingest.py` takes a list of normalised events and, in one transaction:

1. `INSERT … ON CONFLICT (event_uuid) DO NOTHING RETURNING id, event_uuid`. Only the returned rows
   feed steps 2–4, so a duplicate push changes nothing downstream.
2. Collapses the new events per relation key in Python: the latest state-changing event by
   `occurred_at`, the min and max `ADD` times, the `ADD` count, and whether all are `LEGACY`. The
   collapse is required because Postgres refuses to let one `ON CONFLICT DO UPDATE` statement touch
   the same row twice.
3. Upserts `RelationState` with one `INSERT … ON CONFLICT DO UPDATE`:
   - `active` and `last_changed_at` change only when
     `excluded.last_changed_at >= relation_state.last_changed_at`;
   - `first_added_at` and `last_added_at` take `LEAST` and `GREATEST`, which skip NULLs;
   - `add_count` is summed;
   - `legacy_only` is ANDed.
4. Upserts `DailyTally` (`n = n + excluded.n`) and inserts `DailyActor` (`DO NOTHING`), with
   Istanbul days computed in SQL.

Rows go into every statement sorted by key, so two concurrent transactions lock rows in the same
order and cannot deadlock. The tally rows, the only hot rows (a few per day), are written last so
their locks are held for the shortest time.

*Alternative:* a `SELECT … FOR UPDATE` per relation row. It is simple, but a 10,000-target run
report would mean 10,000 round trips inside a request. Set-based statements make that one round
trip per table.

*Alternative:* derive state at query time (`DISTINCT ON … ORDER BY occurred_at DESC`). It is always
correct, but it scans the ledger. Kept only as the **rebuild path**:
`manage.py history_rebuild --verify` recomputes `RelationState`, `DailyTally` and `DailyActor` from
`Event` and diffs them. It serves repairs, the post-backfill check, and a property test that
incremental ingest equals rebuild for random event orders.

### D4. Pre-aggregation: no stat reads the event log

| Stat | Reads | Query shape |
| --- | --- | --- |
| `top?scope=current` | `RelationState` | `COUNT(DISTINCT actor) … WHERE kind=? AND active GROUP BY target`, partial index |
| `top?scope=all` | `RelationState` | same, `WHERE first_added_at IS NOT NULL` |
| `top?scope=window` | `RelationState` | same, `WHERE last_added_at >= now() - days`, index `(kind, last_added_at)` |
| `most_banned/` (non-unique) | `RelationState` | `SUM(add_count) GROUP BY target` |
| `timeseries` | `DailyTally`, `DailyActor` | ≤ `days` × handful rows; `COUNT(DISTINCT actor)` per day |
| per-user pages | `Event` via actor/target index, `RelationState` | keyset by id |

Measured on 5 M synthetic events (5,000 actors, 50,000 targets, two years), after `VACUUM`:
`top` current 0.6 s, all-time 0.5 s, 30-day window 0.3 s, 365-day window 0.9 s;
`most_banned_unique/` 0.4 s; 90-day series 0.1 s; any per-user page under 0.1 s. Table sizes:
`Event` 1.6 GB, `RelationState` 1.4 GB, `DailyActor` 0.6 GB. The synthetic data spreads every
actor over every day, so `DailyActor` is a ceiling, and relations come out at nearly one per event,
which is also what real blocking looks like: a person blocks someone once.

`last_added_at` answers the window question exactly. An actor had at least one `ADD` in the
window if and only if their latest `ADD` is in it. Distinctness holds because the table has one
row per actor and target. A per-day counter could not do this: distinct counts do not add across
days, so the same person blocking on day 1 and day 5 would count twice.

So ranking cost scales with the number of distinct relations, which in practice is bounded by
users × targets, never with repeat events. `top` and `timeseries` are also cached for 10 minutes.

*Alternatives considered:*
- **Query-time aggregation over `Event`.** It needs no extra tables, but window and all-time
  rankings scan the whole ledger. Rejected per the decision to pre-aggregate now.
- **A per-target counter table.** It gives O(1) reads but cannot answer "distinct actors in a
  window", and every block of a popular target would contend on one row. Rejected.

All stats live in `history/stats.py`. DRF views, the old routes and the admin dashboard all call
it. Today `views.py` and `admin.py` hold separate copies of each query, and `FAILED_ACTION`
(`models.py:6`) had to be factored out to stop them drifting.

### D5. Sync cursor is a per-account counter under the account row lock

Each task write locks its `Account` row, increments `account.change_seq`, and stamps the task with
it. `pull` returns `Task WHERE account=? AND change_seq > cursor ORDER BY change_seq`.

*Alternative:* a global Postgres sequence or `updated_at`. A sequence value is assigned before
commit, so transaction A (seq 10) can commit after transaction B (seq 11). A device that already
pulled up to 11 then never sees 10. Clocks have the same defect, made worse by skew. The
per-account lock makes commit order and cursor order identical, and the contention is one person's
devices, which is nothing.

### D6. Tasks replicate to devices, events do not

A device needs the live queue, which is small. It does not need a permanent ledger of possibly
100k rows in `chrome.storage.local`, which has a 10 MB quota without `unlimitedStorage`.
`GET /api/sync/history` exists, keyset-paginated over the account's own events, but no client UI
calls it in this release.

### D7. Ownership with an epoch fence

The device that enqueues a task owns it.

**Cancel** is a flag the owner reads on its next pull. While a task runs, the owner pulls every
60 s or every 25 targets, whichever comes first. It then stops through the existing cooperative
stop path.

**Takeover** bumps `epoch` and starts the task over as `QUEUED` with zero counts. Every upsert
carries the sender's epoch, and a lower one -- or one from a device that is not the owner -- is
reported as `conflict` for that task, after which the old owner drops it. This is a fencing token. A server-side lease alone cannot
stop a laptop that wakes from sleep mid-request; rejecting its writes does stop it from recording
and continuing.

A takeover of a `RUNNING` task restarts it with `enableOnlyRequiredActions`, so targets already in
the requested state come back `SKIPPED`. This avoids replicating checkpoints: the two clients store
them differently (`saveOperationState` against `operation_checkpoint`), and unifying that is a
separate project.

Takeover is offered only where the device can run the task: the extension needs the task's
`params.retry` (its own single-target tasks), Android needs `params.android_request` (an Android
task whose request is under 16 KB). The two clients describe a run in different shapes, so a task
moves between extension installs or between Android devices, not across. Cancel works everywhere.

*Alternative:* any device runs any queued task, first come first served. Rejected: two devices on
one Ekşi session would double the request rate against the ~12/min limit.

### D8. Identity: anonymous accounts, device tokens, pairing codes, synced join key

The server cannot verify Ekşi identity: it never sees Ekşi cookies, and must not. So the security
boundary is the **account**, not the nick. A device reads only what its own account wrote. An
attacker claiming someone's nick can pollute rankings, which is already possible through
`/api/action/`, but can never read the victim's history.

- **Tokens:** 256-bit random values, stored as SHA-256. That is enough for high-entropy secrets,
  and a slow hash buys nothing here.
- **Pairing codes:** 40 bits, 10-minute TTL, single use, and code claims throttled per IP.
- **Pairing needs approval.** A claim only creates a pending request; a device of the code's
  account approves or refuses it, and nothing moves before that. The first design merged on
  claim, so anyone who saw the code on a screen or in a screenshot within 10 minutes got the
  whole history. The claimer is warned that its history moves, and polls for the outcome.
- **Synced join key:** `chrome.storage.sync` carries a **join key**, never a device token. A second
  install on the same browser profile links with no code, yet still gets its own device identity,
  which ownership requires (D7). Keys are **one per profile** (`JoinKey`), not one per account. The
  first design kept a single key per account and replaced it on every claim and removal; each
  replacement stranded the copy every other profile held, and that profile's next install quietly
  started an account of its own. Now a merge moves the claimer's keys into the account, so the key
  its profile already holds keeps working; removing a device revokes only its own profile's key,
  and the remover gets a new key only when it shared that one.
- **Device list.** Each device can list the account's devices and remove one, which revokes its
  token and its profile's key. A stray approval is therefore recoverable.
- **`X-API-Key`** (`authentication.py:5-12`) ships inside the client, so it is not a secret.
  **Carried forward** as a cheap filter for drive-by traffic only; nothing in this design relies on
  it.

*Alternative:* bind accounts to the Ekşi id the client claims. Linking would be free, but anyone
could read anyone's block list by claiming their id. Rejected outright.

### D9. Emission points and the `author_list` rule

**Events.**
- **Extension:** `performAction` returns its per-kind statuses next to the folded result
  (`relationHandler.js:85-96`). Its callers (`programController.js:1108`,
  `background.js:750,832`) already know the task, the target and the actor
  (`commHandler.rememberClient`, `commHandler.js:131`), so they build the events.
  `TOO_MANY_REQ` returns early (`relationHandler.js:67-77`) and so emits nothing.
- **Android:** `TargetRunner.performWithRetry` reports each settled relation to the context;
  `RoomOperationContext` holds them until the next checkpoint and writes them in its transaction.
  A cancelled run's checkpoint still writes them before raising the stop, because the relations
  really changed.
- **Android task states** are derived, not reported. Each sync pass reads `queued_task`,
  `operation_checkpoint` and the last week of `completed_operation`, and compares them with
  `history_task`, what the server was last told. A task can leave the queue through the screen, the
  reconciler, the archive or a stop; deriving covers all of them without a hook in each. A task in
  none of the three reads as cancelled, so `OperationReconciler`'s queue drain now takes the run
  and writes its checkpoint in one transaction (`OperationWorker.startNextQueued`).

**Flushing the outbox.** On task end; every 25 events or 60 s while running; and periodically. The
periodic trigger is `chrome.alarms` every 5 min in the extension (the new `alarms` permission
shows no install warning on Chrome or Firefox) and `HistorySyncWorker` on Android.

**Run reports.** The report builders (`background.js:1287-1297`, `ActionReport.kt:64-71`) send
`author_list: []` while `syncHistory` is on and the full list while it is off, and always send
`task_id`. Sending an empty list is already accepted: the muted- and blocked-list refresh reports
do it today (`programController.js:1292,1343`). With history on, a 10,000-target run therefore
stops sending its targets twice, and the server stops storing them twice.

**Carried forward:** the extension has no transactions, so a service-worker kill between the
request and the outbox write loses that one event. Writing the event before the request would
invert the risk into a phantom `OK` event, which is worse for a ledger.

### D10. Run reports feed the ledger

`CollectActionDataSerializer.create` calls `history.ingest` with `LEGACY` events built from the
saved `Action`, in the same transaction:
- one event per target per mapped kind;
- `occurred_at` = `Action.date`;
- `event_uuid` = UUIDv5 of (action pk, target id, kind);
- the account is the actor's `LEGACY` account, found or created by `legacy_actor_eksi_id`.

`manage.py history_backfill_legacy` runs the same function over existing rows in pk order. Because
the ids are deterministic, deploy order does not matter: rows the live path already converted are
ignored by the backfill and the reverse, and the command is resumable.

The `ban_mode` × `ActionConfig` flags → kind mapping is one table in `history/legacy.py`, used by
both paths. A report naming its action in `bulk_action` is read by that name; both clients now
name a list run's action there. A report from the app is read by its `target_type`, which names
the relation it applied; the extension's does not, for audience runs. Anything unmapped is skipped and counted. Task 5.1 lists the production `ban_mode`
values before the table is finalised.

Who still produces `LEGACY` rows after this release: shipped clients until they update, and any
user with `syncHistory` off and `sendData` on. The second group is intended. They chose to send run
reports, and the reports' targets keep counting in rankings, as they do today.

### D11. Old stat routes, new source

`most_banned/`, `most_banned_unique/` and `user_stat/` (`api/urls.py:9-11`) keep their paths and
serializers' field names, but their querysets come from `history/stats.py`:

- `banned_by_unique_count`: `top?scope=all` for kind `BLOCK`;
- `banned_by_count`: `SUM(add_count)` over relation-state rows targeting the id;
- `ban_count` and `ban_unique_count`: the same, in the actor role;
- `action_for_ban_count`: stays an `api.Action` count, because it counts runs, not targets.

`user_stat/` gains page-number pagination and annotates a page in a fixed number of queries.

**Deliberate change of meaning:** counts are now per Ekşi id rather than per `EksiSozlukUser` row,
so ids that had duplicate rows merge. That is the only expected difference after backfill, and the
"same answer after migration" scenario tests for it.

### D12. Bounding what one device can send, and checking who it claims to be

Nothing stops a client from inventing events: the API key ships inside it, and the server cannot
see an Ekşi session. Two cheap limits bound the damage:

- **A daily cap per device**, 20,000 new events per Istanbul day, above the roughly 17,300 that
  Ekşi's action rate allows. Counted on the `Device` row in the push transaction. Events already
  stored are acknowledged regardless, or a resend at the cap would retry forever. The response
  says `capped`, and clients stop pushing for that pass instead of retrying.
- **Task `params` over 20 kB** are replaced by `{"dropped": "too_large"}`; a takeover in the
  extension only follows links on the Ekşi Sözlük host.

And one check against Ekşi itself: `manage.py history_check_accounts`, hourly from
`eksiengel-history-check.timer`, reads `/biri/<nick>` (a made-up nick is a 404) and
`/son-entryleri?nick=` (each entry carries `data-author-id`) for every reporting actor and the top
200 block and mute targets. At most 100 ids a run, 2 s apart, logged out. `MISMATCH` and `MISSING`
ids drop out of every ranking; `ERROR` counts until a retry an hour later, since an Ekşi outage
must not empty the rankings. It cannot prove that the reporter *owns* the nick, only that the nick
is real and matches the id. The admin's per-user page shows the result next to the user's accounts
(kind, age, devices), which is what judging a suspicious account needs.

*Alternative:* verify ownership by asking the client to prove an Ekşi session (a signed page, a
posted token). Rejected: it means handling Ekşi credentials or scraping logged-in pages server
side, both worse than the fake rankings it prevents.

### D13. Current state per person, not per account

`RelationState` is per account, which is right for what a device reads. But one Ekşi id often
reports through several accounts — its run-report (`LEGACY`) account and a device account, or two
devices never paired — and "is 123 blocked by this actor now" was being answered per account: a
block in run reports and a later unblock on a device counted the actor as still blocking.
`ActorRelation` holds one row per `(actor, target, kind)`. It is refreshed from `RelationState`
for exactly the keys a statement touched, in the same transaction (ingest, merge, deletion):
`active` from the row changed last, add times and counts spanning all rows, `legacy_only` only
while all rows are, `accounts` the number of rows. Rankings, per-user relations and the legacy
routes read it; the rebuild recomputes it. The refresh joins on `(actor, kind, target)`, which an
index on `RelationState` covers — with `(actor, kind)` alone a 10 k report took 3.8 s.

## Risks / Trade-offs

- **[Table growth]** About 350 bytes per event including indexes, so 5 M events is about 1.7 GB.
  `RelationState` grows with distinct relations only. → Measure
  `SELECT count(*) FROM api_action_author_list` before the backfill to project the size.
  Partitioning stays out of scope until 50 M rows.
- **[A 10,000-target run report now writes ledger rows inside the request]** → The set-based path
  (D3) makes that one statement per table. The test suite times a 10 k report and fails above 2 s.
- **[Hot tally rows serialise concurrent ingests for a day]** → They are written last and sorted,
  and the locks are held for milliseconds. A 10 k report holds them for its final statement only.
- **[Fake events inflate rankings]** Still possible for a real nick. → Ids that Ekşi says do not
  exist or do not match are left out (D12), one device cannot send more than a day's worth of
  actions, rankings return the distinct-account count, and the admin can revoke an account, which
  removes its events, state and tally contribution.
- **[Clients older than the approval flow]** A 0.6.0 client claiming a code gets `202` with no
  merge and reports success, and a 0.6.0 device cannot approve. → 0.6.0 was never in a store; the
  release that adds approval ships both clients at once.
- **[Admin can read one person's full history]** This is the feature, and the most sensitive
  capability added. → An `AdminAccess` row is written on every per-user read, and the privacy page
  says so.
- **[Store policy]** Decided: Firefox keeps `required: ["websiteActivity"]`
  (`manifest.firefox.json:12-14`), and `personallyIdentifyingInfo` is not declared, because the
  ledger sends the same data class `author_list` has shipped under. → If an AMO reviewer objects,
  Firefox alone makes `syncHistory` default to `false` and asks with `permissions.request`. Chrome
  and Android stay unchanged.
- **[Run-report data has no self-service deletion]** `LEGACY` rows are not tied to a device, as
  `Action` rows are not today. → The privacy page says deletion of run-report data is by request,
  and the admin deletes the legacy account.
- **[CORS]** The new routes send `Authorization`. → Add it to the allowed headers for the extension
  origins. A missing header fails silently into the outbox, so a backend test asserts it.
- **[Clock skew on `occurred_at`]** It only orders state changes for one `(actor, target, kind)`,
  and those almost always come from one device. → Cross-device conflicts within the skew window
  resolve last-writer-wins. That is acceptable for state a person can simply toggle again.
- **[A device clock outside what is possible]** → The server stamps such a timestamp with its own
  arrival time instead of refusing the batch, which would otherwise fail every retry and stop that
  device syncing for good.
- **[Outbox overflow in the extension]** Capped at 20 k events, about 3 MB. → The oldest are dropped
  first and the next push reports a gap count, so history is visibly incomplete rather than
  silently wrong.

## Migration Plan

1. **Backend deploy.**
   - Add the `history` app and `Action.task_id`, run `migrate`, restart `gunicorn-eksiengel`.
   - From this point every incoming run report also writes `LEGACY` rows.
   - The sync routes exist but nothing calls them yet.
2. **Backfill.** Run `manage.py history_backfill_legacy`, then `history_rebuild --verify`. Compare
   `most_banned_unique/` against a response saved before deploy; the only differences should be
   merged duplicate ids.
3. **Client release** (extension and app, one version). Shipped clients keep posting full reports
   and keep counting.
4. **Privacy page** update goes out with step 3. It is a template-only change plus the usual
   restart.

**Rollback:** clients treat 404 and 5xx as "retry later", so reverting the backend only fills
outboxes. `migrate history zero` plus reverting the `task_id` migration restores `api` exactly.
Clients that sent `author_list: []` during the window are the one loss: their targets live only in
the dropped ledger. Take a `pg_dump` of the `history_*` tables before any rollback.

**Done here, not in production:** the deploy and backfill steps above need the production host.
They are the remaining tasks.

## Open Questions

1. **Self-service copy of own data.** With no UI, users cannot reach `GET /api/sync/history`
   themselves, so a KVKK access request is answered by the admin from the per-user pages. A
   "Verilerimi indir" button beside "Sunucudaki verilerimi sil", saving the endpoint's JSON, would
   make it self-service. It is not in this release.
