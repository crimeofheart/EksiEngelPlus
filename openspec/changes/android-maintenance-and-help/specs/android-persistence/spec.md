## MODIFIED Requirements

### Requirement: Registration dates are cached with a TTL

Registration dates SHALL be cached per nick with a fetch timestamp and a 30-day
TTL, matching the extension's behaviour.

Resolving a date is the most expensive operation in a date-filtered bulk run —
one profile fetch per uncached user — so the cache is a correctness-adjacent
performance requirement, not an optimisation.

The TTL SHALL bound the table's size and not only the freshness of a read.
Expired rows SHALL be deleted, at the start of every operation run and on demand
from Settings. `trimExpired()` existed with no caller for the cache's whole
life, which made the TTL a read filter wearing the costume of an eviction
policy: every nick ever resolved stayed on disk permanently, and an author list
may hold 10,000 of them.

Pruning at the start of a run rather than on a timer keeps it where the cache is
about to be used and costs one `DELETE` per run. It needs no scheduler, which
means there is no scheduled job to fail silently.

#### Scenario: Fresh entry is reused

- **WHEN** a cached date is younger than 30 days
- **THEN** it is returned without a network request

#### Scenario: Stale entry is refetched

- **WHEN** a cached date is older than 30 days
- **THEN** it is refetched and the timestamp updated

#### Scenario: Expired rows do not survive a run

- **WHEN** an operation starts
- **THEN** every row past its TTL is deleted before the run reads the cache

#### Scenario: Pruning never deletes a usable row

- **WHEN** the prune runs
- **THEN** rows inside the TTL are untouched, so a prune costs no refetch

## ADDED Requirements

### Requirement: Stored data is inspectable and clearable

Settings SHALL report the registration-date cache as a total and an expired
count, and the database as a size on disk, and SHALL offer to clear each.

The extension shows both against a 5 MB quota because `chrome.storage.local`
enforces one. This app has no ceiling, which is the reason to show the numbers
rather than a reason not to: nothing here will ever fail loudly at a limit, so
an unbounded table is invisible until the platform's own storage screen reports
it.

Clearing stored data SHALL be refused while an operation is running, SHALL name
what it deletes before deleting it, and SHALL require confirmation. A worker
holding rows that vanish mid-run corrupts an operation rather than cancelling
one, and the user asked to free space, not to break a run they had forgotten
was going.

Clearing the cache alone SHALL be available unconditionally — it holds only
refetchable data, so the worst it can cost is time.

#### Scenario: Counts are shown

- **WHEN** Settings is opened
- **THEN** the cache total, the expired subset of it, and the database size on disk are displayed

#### Scenario: Clearing the cache

- **WHEN** the user clears the registration-date cache
- **THEN** every row is deleted and the displayed counts return to zero

#### Scenario: Clearing data during a run

- **WHEN** the user asks to clear stored data while an operation is running
- **THEN** the request is refused and the reason is shown, and nothing is deleted

#### Scenario: Clearing data is confirmed first

- **WHEN** the user asks to clear stored data with no operation running
- **THEN** what will be deleted is named, and deletion happens only on an explicit confirmation
