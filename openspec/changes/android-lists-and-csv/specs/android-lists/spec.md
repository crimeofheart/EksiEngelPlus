## ADDED Requirements

### Requirement: Relation lists are synced from the site's own endpoints

The Android client SHALL populate `relation_user` from the same endpoints the
extension uses, and from no other source.

`BLOCKED` and `MUTED` come from
`GET /relation-list?relationType=<code>&pageIndex=<n>`, where `<code>` is
`TargetType.relationCode` — `m` for blocked, `u` for muted. `pageIndex` is
1-based; page 0 returns HTTP 500. The response is JSON with
`relations.items[].nick`, `relations.items[].id`, and `relations.isLast`.

`FOLLOWED` comes from `GET /following?nick=<ownNick>&pageIndex=<n>`, which
returns a bare JSON array of users and has **no** `isLast` field — an empty array
is the terminator.

Both request families require the `x-requested-with: XMLHttpRequest` header,
without which Ekşi returns a full HTML page instead of JSON.

This binds the Android client only. The extension already implements it in
`notificationHandler.js`.

#### Scenario: Blocked list pages to completion

- **WHEN** a `BLOCKED` sync runs
- **THEN** `/relation-list?relationType=m` is fetched from `pageIndex=1` upward until a response carries `relations.isLast: true`, and one `relation_user` row per item is upserted with `listType = BLOCKED`

#### Scenario: Followed list terminates on an empty array

- **WHEN** a `FOLLOWED` sync reaches a page whose body is `[]`
- **THEN** paging stops and that page contributes no rows, because `/following` carries no `isLast`

#### Scenario: HTML in place of JSON is a session loss

- **WHEN** a relation page responds with HTML rather than JSON
- **THEN** the sync ends as session-expired rather than parsing it as an empty list, so a logged-out user never sees their list silently emptied

### Requirement: A sync resumes from its cursor and never truncates on failure

A sync SHALL record its progress in `list_sync_state` after each page and SHALL
resume from `cursorPage` when restarted.

Rows SHALL be upserted as pages arrive, and the list SHALL NOT be cleared before
a sync begins. A sync that ends before its terminator SHALL set
`isPartial = true`; one that reaches its terminator SHALL set `isPartial = false`
and stamp `lastFullRefreshAt`.

Clearing first would mean an interrupted sync destroys the previous good list —
the extension avoids this by writing partial results to a separate key, and the
row-per-user schema makes the separate key unnecessary.

#### Scenario: Interrupted sync resumes mid-list

- **WHEN** a sync stopped after page 7 is restarted
- **THEN** it requests page 8 first, and pages 1–7 are not refetched

#### Scenario: Partial sync is still usable

- **WHEN** a sync is stopped by the user or by a network failure at page 3 of 40
- **THEN** the rows from pages 1–3 remain queryable and exportable, and the list is flagged partial

#### Scenario: Departed users are pruned only on an uninterrupted full pass

- **WHEN** a sync that started at page 1 reaches its terminator
- **THEN** rows of that `listType` whose `lastSeenAt` predates the sync start are deleted

#### Scenario: A resumed pass prunes nothing

- **WHEN** a sync that resumed from a stored cursor reaches its terminator
- **THEN** no rows are pruned, because the pages before the cursor were stamped by the earlier attempt and pruning would delete exactly what resumption preserved

#### Scenario: Syncing does not forget a known registration date

- **WHEN** a user already carrying a registration date is seen again by a sync
- **THEN** the date and the original `addedAt` survive, and only `lastSeenAt` and the nick are updated — the relation endpoints carry no date, and losing it would send the next date-filtered run back to fetch every profile

### Requirement: Lists are surfaced with count, freshness and partial state

The app SHALL present each of the three lists with a live count, the time of the
last full refresh, and whether the current contents are partial. Each SHALL be
refreshable and stoppable independently.

Every value SHALL be read through the existing `Flow` DAOs
(`RelationUserDao.countOf`, `ListSyncStateDao.observe`) rather than polled or
pushed from the sync job, matching the observation rule already established for
operations.

#### Scenario: Count tracks an in-flight sync

- **WHEN** a sync is writing pages while the Lists screen is open
- **THEN** the displayed count rises as rows land, with no explicit refresh

#### Scenario: Partial state is visible, not silent

- **WHEN** a list's `isPartial` is true
- **THEN** the screen says so, so the user does not read a truncated list as complete

### Requirement: CSV export is byte-compatible with the extension

Exported CSV SHALL carry the header line `Username,RegistrationDate` and one row
per user as `<nick>,<date>`, where `<date>` is `YYYY-MM-DD` taken from
`registration_date_cache` or the empty string when no cached date exists. Rows
SHALL be joined with `\n`.

The suggested filename SHALL be
`eksiengel_<blocked|muted|followed>_users_<YYYY-MM-DD>.csv`, the date being the
export day.

This reproduces `notificationHandler.js:178-214` exactly. The format is the
interchange between the two clients and between machines, so a file written by
either SHALL import into the other.

#### Scenario: Uncached dates export blank, not absent

- **WHEN** a list is exported and half its users have no cached registration date
- **THEN** every user appears, those without a date carrying an empty second field, and the column count stays 2 on every row

#### Scenario: A partial list exports what it has

- **WHEN** an export is requested for a list flagged partial
- **THEN** the rows present are written rather than the export being refused

### Requirement: Export and import go through the Storage Access Framework

File I/O SHALL use `ACTION_CREATE_DOCUMENT` for export and `ACTION_OPEN_DOCUMENT`
for import.

The app SHALL declare no storage permission. SAF grants access to the single URI
the user picked, which is the whole of what this feature needs; a broad storage
permission would be both unnecessary and an obstacle at Play review.

#### Scenario: No storage permission is requested

- **WHEN** the user exports or imports for the first time
- **THEN** the system file picker opens with no runtime permission prompt preceding it

### Requirement: CSV import mirrors the extension's parser

Import SHALL accept both a picked file and pasted text, and SHALL parse them
identically.

The parser SHALL split on `/\r?\n/`, treat the first line as a header only when
its first field lowercases to `username`, and handle `"`-quoted fields
containing commas. Blank lines and blank first fields SHALL be skipped. The first
field is the nick; the second, when present, is a registration date.

Dates SHALL be accepted as `YYYY-MM-DD` and as the Turkish long form already
handled by `TurkishDateParser`. An unparseable date SHALL NOT reject its row —
the nick is imported without a date.

This mirrors `authorListPage.js:112-210`, binding the Android client. The
extension is unchanged.

#### Scenario: Headerless file imports every line

- **WHEN** a file whose first line is `birisi,2010-04-01` is imported
- **THEN** `birisi` is imported, because the first field is not the literal `username`

#### Scenario: Quoted comma survives

- **WHEN** a row reads `"nick, with comma",2011-02-03`
- **THEN** the nick is `nick, with comma` and the date is `2011-02-03`

#### Scenario: Bad date keeps the nick

- **WHEN** a row's date field is `dün`
- **THEN** the nick is imported with no registration date, and the import does not fail

#### Scenario: Imported dates seed the cache

- **WHEN** a row carries a parseable date
- **THEN** a `registration_date_cache` entry is written for that nick, so a later date-filtered run does not refetch the profile

### Requirement: The saved author list is the LIST source and survives replacement

The author list SHALL be stored in `author_list`, unique by nick, in insertion
order. The user SHALL be able to replace it wholesale, append to it, and clear it.

A replace SHALL be atomic: an import that fails partway SHALL leave the previous
list intact rather than a half-written one.

#### Scenario: Duplicate nicks collapse

- **WHEN** an imported file names the same nick twice
- **THEN** one row exists, because `author_list.nick` is uniquely indexed

#### Scenario: Failed import preserves the old list

- **WHEN** a replace-mode import throws partway through the file
- **THEN** the previously saved list is still present and unmodified
