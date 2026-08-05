# android-persistence

Binds: Android only. Replaces the `chrome.storage.local` surface of
`frontend/app/assets/js/storageHandler.js`.

## ADDED Requirements

### Requirement: Relation lists are rows, and counts are derived

The blocked, muted and followed lists SHALL be stored as rows in a single table
keyed by `(listType, userId)`, and any count SHALL be computed with `COUNT(*)`.

No count SHALL be stored. The extension keeps `mutedUserCount` alongside
`mutedUserList` and the two can disagree; deriving the count makes that class of
bug unrepresentable.

#### Scenario: Count follows content

- **WHEN** rows are inserted or removed for a list type
- **THEN** the reported count changes with them, with no separate update

#### Scenario: Re-scraping is idempotent

- **WHEN** the same user is scraped twice into the same list
- **THEN** one row exists, because `(listType, userId)` is the primary key

### Requirement: Registration dates are cached with a TTL

Registration dates SHALL be cached per nick with a fetch timestamp and a 30-day
TTL, matching the extension's behaviour.

Resolving a date is the most expensive operation in a date-filtered bulk run —
one profile fetch per uncached user — so the cache is a correctness-adjacent
performance requirement, not an optimisation.

#### Scenario: Fresh entry is reused

- **WHEN** a cached date is younger than 30 days
- **THEN** it is returned without a network request

#### Scenario: Stale entry is refetched

- **WHEN** a cached date is older than 30 days
- **THEN** it is refetched and the timestamp updated

### Requirement: Turkish registration dates parse to instants

`TurkishDateParser` SHALL accept the month-name form (`ağustos 2026`, `temmuz 2026`),
ISO-8601, and `DD.MM.YYYY`. A month-name value SHALL resolve to the first of that
month. All day arithmetic SHALL use `java.time` in `Europe/Istanbul`.

Both observed live values were month-name form, so it is the primary case, not a
fallback.

#### Scenario: Month name

- **WHEN** `ağustos 2026` is parsed
- **THEN** the result is 2026-08-01

#### Scenario: Unparseable input

- **WHEN** a value matches no known form
- **THEN** parsing returns absent rather than throwing or guessing a date

### Requirement: Nick slugs are normalised in exactly one place

Normalisation — trim, then replace every space with a hyphen — SHALL exist as a
single function used at every call site.

Live data contains multi-word nicks (`0 derece`, `ben ne diyorum sen ne diyorsun`),
so this is exercised in practice. The extension repeats the rule inline throughout
`scrapingHandler.js`, which is what a reimplementation must not copy.

#### Scenario: Multi-word nick

- **WHEN** `0 derece` is normalised
- **THEN** the result is `0-derece`

### Requirement: Enum integer keys match the backend exactly

`ban_source`, `ban_mode`, `target_type`, `click_source` and `time_specifier`
SHALL serialise to the same integers the extension sends, and `log_level` SHALL
keep the **client** mapping `{DISABLED:1, INFO:2, WARN:3, ERR:4}`.

These are primary keys in a shared database holding rows the extension already
wrote. The server seeds `log_level` differently
(`api/migrations/0007_seed_lookup_data.py:38` reads 1 as DEBUG), and that
divergence SHALL be preserved rather than corrected, or the column changes meaning
across two clients.

#### Scenario: Ban source keys

- **WHEN** any `BanSource` is serialised
- **THEN** it yields the integer from `enums.js` — 1 SINGLE, 2 FAV, 3 FOLLOW, 4 LIST, 5 UNDOBANALL, 6 TITLE, 7 BLOCKED_MUTED_TITLES, 8 MIGRATE_BLOCKED_TO_MUTED, 9 BLOCK_MUTED_USERS, 10 REFRESH_MUTED_LIST, 11 REFRESH_BLOCKED_LIST, 12 DATE_BASED_BULK, 13 UNMUTEALL, 14 REFRESH_FOLLOWED_LIST

#### Scenario: Log level divergence is preserved

- **WHEN** a WARN-level action is reported
- **THEN** `log_level` is 3, per the client mapping, regardless of the server's seed

### Requirement: Schemas are exported and drift fails the build

Room SHALL run with `exportSchema = true`, exported schemas SHALL be committed,
and CI SHALL fail when they are dirty after a build.

An uncommitted schema change means a migration was never authored, which surfaces
only as a crash on a user's device.

#### Scenario: Schema changed without commit

- **WHEN** an entity changes and the exported schema is not committed
- **THEN** the build fails naming the schema directory

### Requirement: Configuration is structured and typed

Config SHALL use a **typed** DataStore — `DataStore<T>` with a custom serializer —
not Preferences, because `dateFilterRules` is a repeated structured value
(`config.js:43-55`) and Preferences would mean hand-parsing JSON out of a string
key. It SHALL carry the eight booleans from `config.js`, the base URL, and the
rule list. Install identity SHALL live in a separate store.

The serializer SHALL be kotlinx-serialization rather than protobuf. Both satisfy
"typed and structured"; kotlinx-serialization is already a dependency for the
three JSON endpoints, needs no `protoc` toolchain in the build, and keeps the
schema declared in Kotlin next to the code that uses it. Protobuf's wire-format
compactness and cross-language story buy nothing for a single-process Android
config file.

The shared API key SHALL NOT be stored in DataStore; it belongs in `BuildConfig`.

#### Scenario: Defaults on first run

- **WHEN** config is read before anything is written
- **THEN** documented defaults are returned rather than an error

### Requirement: Storage has no 5 MB ceiling

The `partial{Muted,Blocked,Followed}Users` chunking in `storageHandler.js` exists
solely because `chrome.storage.local` caps at 5 MB. SQLite has no such limit, so
resumption SHALL be cursor-based — a page index in a sync-state row — rather than
chunked payloads.

#### Scenario: Interrupted scrape resumes by cursor

- **WHEN** a list scrape is interrupted at page N
- **THEN** resumption continues from page N using the stored cursor, with no partial payload retained
