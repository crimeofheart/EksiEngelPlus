## ADDED Requirements

### Requirement: A date-based bulk run is a source, a criterion and an action

`ban_source` 12 SHALL be composed by the user from the three choices the
extension offers (`notification.html:143-188`), not picked from a fixed list of
combinations.

**Source** — `DateBulkSource` in `enums.js:113-117`:

| choice | what the run reads |
| --- | --- |
| `BLOCKED_USERS` | `/relation-list?relationType=m` |
| `MUTED_USERS` | `/relation-list?relationType=u` |
| `AUTHOR_LIST` | the stored author list, resolved before the run |

**Criterion** — `DateFilterCriteria` in `enums.js:90-95`: NEWER_THAN,
OLDER_THAN, BEFORE_DATE, AFTER_DATE. The first two take a day count; the last
two take a calendar date.

**Action** — `DateBulkAction` in `enums.js:102-111`, each of the eight mapping to
a `BanMode`, a `TargetType`, and optionally a second relation applied only after
the first succeeds:

| action | mode | target | then |
| --- | --- | --- | --- |
| `ENGELLE` | BAN | USER | — |
| `SESSIZE_AL` | BAN | MUTE | — |
| `ENGEL_KALDIR` | UNDOBAN | USER | — |
| `SESSIZDEN_CIKAR` | UNDOBAN | MUTE | — |
| `TAKIP_ET` | BAN | FOLLOW | — |
| `TAKIPTEN_CIKAR` | UNDOBAN | FOLLOW | — |
| `ENGEL_KALDIR_VE_TAKIP_ET` | UNDOBAN | USER | FOLLOW |
| `SESSIZDEN_CIKAR_VE_TAKIP_ET` | UNDOBAN | MUTE | FOLLOW |

The two combined actions SHALL apply the follow only after the undo succeeds,
through the same pairing the author list uses, so a user whose unblock failed is
not followed.

The `ban_source` reported SHALL be 12 for the two relation-list sources. The
author-list source SHALL report `LIST` (4), because it is the author list being
run — which is what every other author-list run reports, and what the shared
backend's rows mean.

#### Scenario: The muted source reads the muted list

- **WHEN** a date-based run is started with source `MUTED_USERS`
- **THEN** it scrapes `relationType=u` and not `relationType=m`

#### Scenario: The blocked source reads the blocked list

- **WHEN** a date-based run is started with source `BLOCKED_USERS`
- **THEN** it scrapes `relationType=m`

#### Scenario: The author list source runs the stored list

- **WHEN** a date-based run is started with source `AUTHOR_LIST`
- **THEN** the nicks are resolved before the run and carried in the request, and no relation list is scraped

#### Scenario: A combined action follows only what it unblocked

- **WHEN** `ENGEL_KALDIR_VE_TAKIP_ET` runs and one user's unblock fails
- **THEN** that user is not followed, and the rest are

#### Scenario: An unsupported combination cannot be expressed

- **WHEN** the chooser is opened
- **THEN** source, criterion and action are picked independently, and every one of the twelve source-action pairs is reachable

### Requirement: The saved date rules narrow only what restricts someone

`dateFilterRules` SHALL gate a run only when that run puts a restriction on
someone who did not have one: blocking, muting, or blocking titles. It SHALL NOT
gate an undo of any of those, a follow, an unfollow, or the blocked-to-muted
migration.

They are protection, and protection has a direction. Applied to every operation
they did the opposite of their purpose: the default ten-year rule spared
decade-old accounts from "tüm engelleri kaldır", so an account the user was
trying to unblock stayed blocked, and silently — a skipped target is not a
failure and appears nowhere in the counts.

This is what the extension does. It filters in three places — `background.js:772`
(FAV), `:836` (FOLLOW) and `:908` (TITLE) — and every one of them is a block.
`UNDOBANALL`, `UNMUTEALL` and the migration reach no filtering code at all.

Following SHALL NOT count as a restriction. It adds a relation but takes nothing
away from the person it names, and a rule about protecting old accounts has
nothing to say about it.

A run the rules do not gate SHALL NOT resolve registration dates. Resolving one
costs a network read per uncached nick, and on a full unblock that is one read
per person for an answer nothing then consults.

#### Scenario: Unblocking everyone unblocks everyone

- **WHEN** an `UNDOBANALL` run executes while the saved rules protect accounts older than ten years
- **THEN** every blocked account is unblocked, including the decade-old ones

#### Scenario: Blocking is still narrowed

- **WHEN** a run blocks, mutes, or blocks titles while the saved rules are enabled
- **THEN** targets failing a rule are skipped

#### Scenario: Following is not narrowed

- **WHEN** a run follows or unfollows while the saved rules are enabled
- **THEN** no target is skipped for its registration date

#### Scenario: A migration is not narrowed

- **WHEN** the blocked-to-muted migration runs while the saved rules are enabled
- **THEN** every blocked account is migrated, because none of them is newly restricted

#### Scenario: An unfiltered run costs no date lookups

- **WHEN** a run the saved rules do not gate executes
- **THEN** no registration date is fetched for any target

### Requirement: A date-based run carries its own criterion

The criterion chosen for a `DATE_BASED_BULK` run SHALL travel in the
`OperationRequest` and SHALL override the saved `dateFilterRules` for that run
alone.

It SHALL NOT be written back to settings. The saved rules are standing
protection — the default one exists to keep decade-old accounts out of every
future run — and a one-off "unblock everyone I blocked before 2020" that edited
them would disarm that protection permanently, invisibly, and for operations
started from a different screen.

It SHALL apply whatever direction the run takes. A criterion is a target
selector the user has just typed, not protection, so the requirement above does
not reach it — and the extension's own default composition is an unmute (muted
users, older than 3650 days, sessizden çıkar; `config.js:58-66`), which a
criterion that worked only on blocking could not express at all.

Every other source SHALL keep using the saved rules, subject to the requirement
above. The override is a property of this one source, not a new general
mechanism.

The rule SHALL be resolved once per run, at the same point the saved rules are
(`OperationWorker.kt:411-426`), so a run that pauses and resumes hours later
applies the criterion it was started with rather than the settings as they now
stand.

A criterion expressed in months or years SHALL be normalised to days before it
is stored in the request. `DateFilterRule.days` is what the predicate compares,
and carrying a unit alongside it would be a second representation of one number.

#### Scenario: The run's criterion beats the saved rules

- **WHEN** a date-based run is started with OLDER_THAN 3650 days while settings hold a NEWER_THAN 3650 rule
- **THEN** the run acts on accounts older than ten years, and settings are unchanged afterwards

#### Scenario: Other operations are unaffected

- **WHEN** any operation other than `DATE_BASED_BULK` runs
- **THEN** it is gated by the saved `dateFilterRules` and by nothing else

#### Scenario: A resumed run keeps its criterion

- **WHEN** a date-based run is paused, the settings rules are edited, and the run is resumed
- **THEN** it continues under the criterion it was started with

#### Scenario: A run with no criterion is refused

- **WHEN** a date-based run is requested with no criterion
- **THEN** it is refused with a message, because a filter that allows everything would act on the whole list

### Requirement: The bulk chooser remembers the last composition

The four choices SHALL be persisted and restored, matching
`createDefaultDateBulkConfig` (`config.js:58-66`). A fresh install SHALL default
to muted users, OLDER_THAN, 3650 days, sessizden çıkar — the extension's values,
so someone arriving from it recognises the dialog.

They SHALL be stored as configuration, not as a rule. Restoring a previous
composition must not add anything to `dateFilterRules`.

#### Scenario: A fresh install shows the extension's defaults

- **WHEN** the chooser is opened for the first time
- **THEN** it is set to muted users, OLDER_THAN, 3650 days, sessizden çıkar

#### Scenario: The previous composition comes back

- **WHEN** a run is started with a composition and the chooser is reopened
- **THEN** the same four choices are selected

#### Scenario: Remembering does not create a rule

- **WHEN** a composition is remembered
- **THEN** `dateFilterRules` is unchanged
