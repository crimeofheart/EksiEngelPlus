## Why

Every queue and its outcome lives only on the device that ran it: the extension keeps the
last 100 completed items in `chrome.storage.local` (`storageHandler.js` `saveCompletedItems`),
the app keeps its own Room rows, and the server sees one `Action` row per *run* with a bare
`author_list` — no per-target outcome, no unblock/unmute, no queue lifecycle. So a user cannot
see a queue started on their laptop from their phone, history is lost on reinstall, and the
admin's "most banned" counts every block ever *attempted* and never subtracts an unblock, which
makes it a measure of noise rather than of who is blocked.

## What Changes

- **Per-target ledger on the backend.** Every block/unblock/mute/unmute/title-ban/follow
  outcome becomes one append-only, idempotent row, kept permanently. A materialised
  current-state table answers "is A blocking B right now" and feeds rankings in one indexed
  read.
- **Queue records on the backend.** Each queued task is stored with its parameters and a
  monotonic status (`QUEUED → RUNNING → DONE/FAILED/CANCELLED`), keyed by a client-generated
  id so retries never duplicate.
- **Cross-device sync.** Installs of the extension (any browser) and the app that belong to the
  same person see one queue and one history. Devices enrol anonymously, link with a short
  pairing code, and Chrome/Firefox profiles that already sync carry the link for free. Only
  the device that owns a task executes it; others view it and may cancel or take it over.
- **Opt-out setting `syncHistory`, on by default** in `config.js` and `Config.kt`, consistent
  with `sendData` (see archived `telemetry-defaults`). Turning it off stops all sending at
  once; a separate "delete my server data" action erases the account server-side.
- **Admin history and stats.** Per-user timeline (what they did, what was done to them),
  current block/mute lists, and global rankings (most blocked/muted now and all-time, by
  distinct actors, over a time window), in the admin dashboard and as admin-only JSON.
- **Run reports keep feeding history.** Every `/api/action/` report with targets becomes `LEGACY`
  ledger rows at ingest, and a one-time backfill does the same for every existing row, so
  "all history" starts at the first recorded action and shipped clients keep counting.
- **No duplicate targets on the wire.** With `syncHistory` on, clients send `author_list` empty
  (the ledger already has every target with its outcome); with it off, in full as today. Run
  reports gain an optional `task_id`, so a run's log and settings join to its per-target rows.
- **Existing stat routes move onto the ledger.** `most_banned/`, `most_banned_unique/` and
  `user_stat/` keep URLs and field names but read the ledger, which also fixes `user_stat/`'s
  five queries per user.
- **Pre-aggregated stats.** Rankings read relation-state rows and time series read daily
  aggregates kept at ingest. No stat scans the event log, so cost does not grow with history.
- **Own-history endpoint, no UI.** `GET /api/sync/history` returns a device's own account's
  events; no client screen uses it in this release.
- `frontend/app/` runtime code **is touched**: an outbox, a sync client and a settings toggle.
  This is deliberate — the feature is user-visible and lands on both clients.

## Capabilities

### New Capabilities

- `history-ledger`: server storage of task records and per-target events — schema, idempotent
  ingest, current-state materialisation, permanence, legacy backfill, and what each client
  must record.
- `history-sync`: device enrolment, pairing, the push/pull protocol, task ownership and
  cancellation across devices, the `syncHistory` setting, and account deletion.
- `history-stats`: admin-only per-user history and global rankings over the ledger.

### Modified Capabilities

- `android-persistence`: adds the history outbox (written in the same transaction as the
  operation checkpoint) and the `syncHistory` config field mirrored from `config.js`.

## Impact

- **Backend** (`backend/django_EksiEngel/`): new app `history` with models, migrations,
  `/api/sync/*` and `/api/stats/*` routes, admin views, a backfill command. Postgres only
  (already the production engine). `/api/action/` accepts exactly what shipped clients send
  today; it only gains an optional field and a ledger write.
- **Extension** (`frontend/app/assets/js/`): `relationHandler.js` emits one event per target;
  new `historySync.js` (outbox + push/pull); `queue.js` assigns task ids and mirrors status;
  settings + FAQ toggle; `config.js` default.
- **Android**: Room outbox entity + migration, `HistorySyncWorker`, emit points in
  `OperationWorker`, settings toggle, `Config.kt` + `ConfigTest`.
- **Privacy surfaces**: `privacy/index.html` and Play Data Safety text. Firefox
  `data_collection_permissions` stays `required: ["websiteActivity"]`. Decided: the ledger sends
  the same data class `author_list` already ships under, so `personallyIdentifyingInfo` is not
  declared. If AMO objects, Firefox alone switches `syncHistory` to opt-in.
- **`api.Action`**: one nullable `task_id` column; `CollectActionDataSerializer` also writes ledger
  rows. Extension `background.js:1287-1297` and Android `ActionReport.kt:64-71` build the
  report's `author_list` and get the `syncHistory` rule.
- **Ops**: one `migrate` on deploy; table growth estimated in design.md.

## Non-goals

- Proving Ekşi identity to the server. The server never sees Ekşi cookies; a device is trusted
  for the account it enrolled into, not for the nick it claims (same trust model as
  `/api/action/` today).
- Hashing third-party nicks. Declined in `telemetry-defaults`; rankings need plaintext.
- Executing a task on two devices at once, or server-initiated execution.
- Showing other users' data to end users. Rankings and per-user lookups are admin-only.
- A user-facing history screen. The own-history endpoint ships without UI.
- Taking a task over across clients. An extension task moves between extension installs and an
  Android task between Android devices; the two describe a run in different shapes. Cancel works
  across both.
- Removing `/api/action/` or the `sendData` diagnostics payload.
