## Why

The Android app can perform every operation the extension can, but two of them —
LIST and UNDOBANALL — need a list the user has no way to produce. `author_list`,
`relation_user` and `registration_date_cache` are all in the schema and all
permanently empty: nothing fills them, nothing shows them, nothing gets data in
or out.

The extension's answer is `authorListPage.html` plus three Export CSV buttons on
the notification page. That interchange format is also how users move between
machines and between the extension and the app, so it has to match byte for
byte, not merely in spirit.

## What Changes

- **Relation list sync** — a WorkManager job pages the blocked, muted and
  followed lists through the existing `ScrapeClient.allRelations` /
  `allFollow`, writing rows into `relation_user`. It resumes from
  `list_sync_state.cursorPage` after an interruption and marks the result
  `isPartial` when it stops early, mirroring the extension's `getPartial*`
  behaviour — a half-scraped list is still worth exporting.
- **Lists screen** — per-list count, staleness and partial-flag, observed from
  the existing `Flow` DAOs. Refresh and stop per list. Reachable from the
  browser shell's overflow menu.
- **CSV export** — via SAF `ACTION_CREATE_DOCUMENT`. Header `Username,RegistrationDate`,
  dates as `YYYY-MM-DD` joined from `registration_date_cache`, blank when
  uncached, filename `eksiengel_<list>_users_<YYYY-MM-DD>.csv`. Identical to
  `notificationHandler.js:178-214`, so a file exported by either client imports
  into the other.
- **Author list import and editing** — SAF `ACTION_OPEN_DOCUMENT` for a CSV, plus
  a paste-a-list text editor for the common case. Parsing mirrors
  `authorListPage.js`: quoted fields, `username` header detection, and both
  `YYYY-MM-DD` and Turkish long-form dates through the already-ported
  `TurkishDateParser`. Parsed dates seed `registration_date_cache` so a
  date-filtered run does not refetch what the file already said.
- **LIST operations read the saved author list** — `ListActionTask` currently
  receives targets from its caller with no defined origin. It reads
  `author_list`.

## Non-goals

- Date filtering of a list before an operation. The import seeds the cache and
  `RelationUserDao.olderThan` already exists, but the filter UI and the
  migration flows belong to `android-migrations-date-filters`.
- Editing the *live* relation lists. The Lists screen is a mirror of what the
  site says; unblocking is what UNDOBANALL is for.
- A settings surface for sync cadence. Sync is manual in this change.
- Any change to `frontend/app/` runtime code. **This change does not touch it.**
  The extension's CSV shape is the contract being matched, not modified.

## Capabilities

### New Capabilities

- `android-lists`: how relation lists are synced and surfaced, how the saved
  author list is built and stored, and the CSV interchange format in both
  directions.

### Modified Capabilities

- `android-operations`: the LIST source's target set is defined — it is the
  saved author list, in insertion order.

## Impact

- **New module** `android/feature/lists/` — screen, view model, sync worker, CSV
  reader and writer.
- **Modified** `android/app/` — a menu entry and a nav destination from
  `BrowserActivity`; `AppModule` wiring.
- **Modified** `android/ops/runtime/` — `OpsModule`'s `LIST` factory resolves
  targets from `AuthorListDao`.
- **Unchanged** `core/database`, `eksi/client`, `eksi/parser` — every DAO, entity
  and endpoint this needs already exists. No Room schema version bump.
- **Unchanged** `frontend/app/`, `backend/`.
- **New permission**: none. SAF requires no storage permission, which is why it
  is used instead of a file path.
