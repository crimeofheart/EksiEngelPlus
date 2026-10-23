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
               change_seq bigint, created_at, join_key_hash
Device         id, account→, token_hash UNIQUE, client {EXTENSION|ANDROID}, label,
               version, created_at, last_seen_at, revoked_at
PairingCode    code_hash, account→, expires_at, used_at
Task           id uuid (client task_id), account→, owner_device→, epoch int,
               actor_eksi_id int, ban_source, ban_mode, params jsonb,
               status smallint, planned/performed/successful int,
               cancel_requested bool, created_at, updated_at, change_seq bigint
Event          id bigserial, event_uuid uuid UNIQUE, account→, device→ null, task→ null,
               action→ null (api.Action, LEGACY only), actor_eksi_id int, target_eksi_id int,
               kind smallint, direction smallint, result smallint, occurred_at, received_at
RelationState  account→, actor_eksi_id, target_eksi_id, kind,
               active, first_active_at, last_changed_at, last_event→,
               first_added_at, last_added_at, add_count int, legacy_only bool
               UNIQUE (account, actor_eksi_id, target_eksi_id, kind)
DailyTally     day date, kind, direction, result, n bigint   UNIQUE (day, kind, direction, result)
DailyActor     day date, kind, account→, actor_eksi_id       UNIQUE (day, kind, account, actor_eksi_id)
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
- `RelationState`: partial `(kind, target_eksi_id) WHERE active`, partial
  `(actor_eksi_id, kind) WHERE active`, `(kind, last_added_at)`, and `(target_eksi_id, kind)` for
  the per-user and compatibility routes.
- `Task`: `(account, change_seq)`.

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

**Takeover** bumps `epoch`. Every upsert carries the sender's epoch, and a lower epoch gets 409,
after which the old owner drops the task. This is a fencing token. A server-side lease alone cannot
stop a laptop that wakes from sleep mid-request; rejecting its writes does stop it from recording
and continuing.

A takeover of a `RUNNING` task restarts it with `enableOnlyRequiredActions`, so targets already in
the requested state come back `SKIPPED`. This avoids replicating checkpoints: the two clients store
them differently (`saveOperationState` against `operation_checkpoint`), and unifying that is a
separate project.

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
- **Synced join key:** `chrome.storage.sync` carries an **account join key**, never a device token.
  A second install on the same browser profile links with no code, yet still gets its own device
  identity, which ownership requires (D7).
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
- **Android:** `OperationWorker` writes `history_outbox` rows inside the checkpoint transaction
  (spec `android-persistence`).

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
both paths. Anything unmapped is skipped and counted. Task 5.1 lists the production `ban_mode`
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

## Risks / Trade-offs

- **[Table growth]** About 350 bytes per event including indexes, so 5 M events is about 1.7 GB.
  `RelationState` grows with distinct relations only. → Measure
  `SELECT count(*) FROM api_action_author_list` before the backfill to project the size.
  Partitioning stays out of scope until 50 M rows.
- **[A 10,000-target run report now writes ledger rows inside the request]** → The set-based path
  (D3) makes that one statement per table. The test suite times a 10 k report and fails above 2 s.
- **[Hot tally rows serialise concurrent ingests for a day]** → They are written last and sorted,
  and the locks are held for milliseconds. A 10 k report holds them for its final statement only.
- **[Fake events inflate rankings]** Unchanged from today. → Rankings also return the
  distinct-account count, and the admin can revoke an account, which removes its events, state and
  tally contribution.
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

## Open Questions

1. **Self-service copy of own data.** With no UI, users cannot reach `GET /api/sync/history`
   themselves, so a KVKK access request is answered by the admin from the per-user pages. A
   "Verilerimi indir" button beside "Sunucudaki verilerimi sil", saving the endpoint's JSON, would
   make it self-service. It is not in this release.
