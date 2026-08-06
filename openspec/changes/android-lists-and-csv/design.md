## Context

Every piece of the data layer this feature needs already shipped with
`android-foundations`: `RelationUserEntity` and `AuthorListEntity`
(`android/core/database/src/main/kotlin/org/duzgun/eksiengelplus/database/Entities.kt:17`
and `:129`), their DAOs with `Flow` observers
(`.../database/Daos.kt:15`, `:120`), `ListSyncStateDao` (`Daos.kt:51`),
`RegistrationDateCacheDao` (`Daos.kt:62`), and the two paging clients
`ScrapeClient.allRelations` and `ScrapeClient.allFollow`
(`android/eksi/client/src/main/kotlin/org/duzgun/eksiengelplus/eksi/client/ScrapeClient.kt:109`
and `:132`). `TurkishDateParser` is ported and unit-tested.

None of it is reachable. There is one Activity —
`android/app/src/main/kotlin/org/duzgun/eksiengelplus/BrowserActivity.kt` — and it
is the WebView shell. `ListActionTask`
(`android/ops/engine/src/main/kotlin/org/duzgun/eksiengelplus/ops/engine/Tasks.kt:157`)
takes its targets from `ctx`, and nothing ever puts any there.

The reference implementation is the extension: `notificationHandler.js:178-317`
for refresh and export, `authorListPage.js` for import and editing. Both are
frozen — this change reads them, it does not touch `frontend/app/`.

The app is Views + Material 1.12 with `viewBinding = false`
(`android/app/build.gradle.kts`). There is no Compose on the classpath and this
change does not add it.

## Goals / Non-Goals

**Goals:**

- Fill `relation_user` from the site, resumably, without ever destroying a good
  list to write a worse one.
- Read and write the extension's CSV so files move between the two clients
  unchanged.
- Give `ListActionTask` a defined target source.

**Non-Goals:**

- Date-filter UI and the migration sources (`BLOCKED_MUTED_TITLES`,
  `MIGRATE_BLOCKED_TO_MUTED`, `DATE_BASED_BULK`) — `android-migrations-date-filters`.
- Compose. Adding a UI toolkit alongside a half-built Views app is a separate
  argument, and the Lists screen is three counts and a list.
- Automatic or scheduled sync.
- Room schema version 2. Everything needed exists at version 1; only new
  `@Query` methods are added, which Room does not version.

## Decisions

### Sync is its own worker, not an `OperationTask`

The extension models list refresh as `ban_source` 10, 11 and 14 — it is an
"operation" there because the extension has exactly one execution path. Here it
is a plain `CoroutineWorker` in `:feature:lists`, not an `OperationTask`
scheduled through `OpsModule`.

Rationale: a sync performs zero mutations. It is not paced by `ActionPacer`
(the ~12/min ceiling governs `addrelation`/`removerelation`, not `GET
/relation-list`), it does not checkpoint into `operation_checkpoint`, and above
all it must not draw down the Android 15 foreground-service budget that
`ForegroundBudget` rations for multi-day blocking runs. Spending an hour of a
six-hour daily budget on a forty-second read would be a real regression.

Alternative considered: a seventh `OperationTask`. Rejected for the budget
reason. Telemetry parity is preserved separately — the sync still reports
`BanSource.REFRESH_BLOCKED_LIST` / `REFRESH_MUTED_LIST` /
`REFRESH_FOLLOWED_LIST` (`core/model/.../Enums.kt:26-30`), because those pks are
rows in the shared backend and the backend should not be able to tell the two
clients apart.

### Upsert forward, prune only on a complete pass

`RelationUserDao.clear(listType)` exists and is the obvious opening move for a
sync. It is the wrong one: an interrupted sync would leave the user with fewer
rows than they started with.

Instead each page is upserted as it lands — `(listType, userId)` is the primary
key, so re-scraping is idempotent by construction — and `lastSeenAt` is stamped
with the sync's start time. Only when the terminator is reached does a new
`pruneStale(listType, before)` query delete rows whose `lastSeenAt` is older,
removing users who have genuinely left the list. A partial sync prunes nothing.

This is why the schema stores rows rather than the extension's serialised array
plus a separate `partial*` key — the partial case needs no separate storage.

### `isLast` for relations, empty-array for follows

The two endpoints do not agree on how they end and the code must not pretend
they do. `/relation-list` returns `relations.isLast`
(`ScrapeClient.relationPage`, `ScrapeClient.kt:94`); `/following` returns a bare
array with no terminator field, so `allFollow` stops on `isEmpty()`
(`ScrapeClient.kt:132`). Both already handle this. The worker drives them through
per-page callbacks rather than the whole-list convenience methods, because it
needs to persist and advance the cursor between pages.

`allRelations` already takes an `onPage` callback; `allFollow` does not, so the
worker calls `followPage` in its own loop.

### CSV streams out, buffers in

Export writes directly to the SAF `OutputStream` a row at a time. A blocked list
of 20 000 nicks is a ~600 KB string if built in memory the way
`notificationHandler.js:186-194` does; there is no reason to hold it.

Import does the opposite — the whole file is parsed into a list before a single
Room write. That is what makes replace atomic: `@Transaction { clear(); upsertAll() }`
either happens or does not, so a malformed line at row 900 cannot leave the user
with 899 authors and no way back.

### Two format defects are carried forward deliberately

1. **Export does not quote.** `notificationHandler.js:190` emits
   `${username},${dateStr}` with no escaping, so a nick containing a comma would
   produce a broken row. The import side *does* handle quotes
   (`authorListPage.js:112`). Reproducing the asymmetry is the point: the
   requirement is byte-compatibility with files already in users' hands, and
   Ekşi nicks do not contain commas in practice. Fixing it unilaterally would
   produce files the extension misreads.

2. **Dates are UTC-truncated.** The extension does `regDate.split('T')[0]` on an
   ISO string, which is a UTC date. Formatting the cached epoch day in the
   device's local zone would shift some dates by one day relative to an
   extension export of the same list. Export formats in UTC to match.

### `:feature:lists` as a new module

`:app` holds `BrowserActivity` and DI wiring and nothing else; `:webview` is
already a feature-shaped module. A new Android library `:feature:lists` holds the
screen, view model, worker, and the CSV codec, depending on `:core:database`,
`:core:model`, `:eksi:client`. `:app` gains a nav entry and the module.

Putting it in `:app` would work but drags Room and OkHttp usage into the module
that is supposed to be assembly only, and the CSV codec is exactly the kind of
pure logic that deserves JVM unit tests without an emulator.

### The CSV codec is pure and JVM-tested

`CsvCodec` takes and returns strings and lists — no `Uri`, no `ContentResolver`,
no Android types. The SAF plumbing is a thin caller in the view model. Parser
parity with `authorListPage.js` is then a table of unit tests in
`src/test/`, running in CI in seconds like `:core:model`'s and `:eksi:parser`'s.

### LIST resolves targets at start, once

`OpsModule`'s factory for `BanSource.LIST` reads `AuthorListDao` once when the
operation begins and passes the resolved list to `ListActionTask`. It does not
observe the `Flow`. `TargetRunner.applyToAll` checkpoints by index
(`Tasks.kt:28`), so a target set that changed under a resumed run would resume at
the wrong place — a re-read on resume would be an actual data-loss bug, not a
freshness feature.

## Risks / Trade-offs

- **A long sync writes thousands of rows** → upserts are batched per page (the
  site's own page size, ~10–20 rows), and every write is off the main thread in
  the worker. No batching layer of our own.
- **Sync while a blocking operation runs** → both hit the same OkHttp stack and
  the same session. Reads are not paced, so a sync could add request pressure
  during a run. Mitigation: the Lists screen refuses to start a sync for a list
  while an operation is `RUNNING`, which is already observable from
  `OperationCheckpointDao`.
- **Import of a very large file** → parsed fully into memory before writing.
  100 000 nicks is a few MB; acceptable. A file large enough to OOM is a file
  the extension could not have produced.
- **Carried-forward unquoted export** → documented above; a nick with a comma
  round-trips wrong. Accepted for compatibility, and the import side tolerates
  quotes if the format is ever fixed on both clients at once.
- **SAF cancellation** → the user can dismiss the picker. Both paths treat a null
  `Uri` as a no-op, not an error.
- **`registration_date_cache` seeded from a file is unverified** → a hand-edited
  CSV can put a wrong date into the cache and skew a later date filter. The cache
  already has a 30-day TTL (`android-persistence`), so the damage self-heals, and
  the alternative — refetching every profile the file already described — is the
  expense the cache exists to avoid.

## Migration Plan

No data migration. Room stays at schema version 1; the change adds `@Query`
methods only, and `exportSchema` drift detection will confirm that — if the
committed `1.json` changes, something was added that should not have been.

Rollback is removing the module and the nav entry; nothing else depends on it,
and the tables it fills are already tolerated empty by everything shipped today.

## Open Questions

- Whether the `follower` list deserves a fourth `ListType`. `FollowEndpoint`
  supports it and `RelationUserEntity.isFollowCurrentUser` anticipates it, but
  the extension exports only three lists, so this change ships three.
- Whether the Lists screen should offer "block everyone on this list" directly,
  or require an export-then-import round trip through the author list. Shipping
  the round trip; the direct path is a small follow-on if it is asked for.
