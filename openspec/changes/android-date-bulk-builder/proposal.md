## Why

The extension's date-based bulk section (`notification.html:143-188`) is four
controls that compose:

- **source** — `DateBulkSource`: blocked users, muted users, author list
- **criterion** — `DateFilterCriteria`: NEWER_THAN, OLDER_THAN, BEFORE_DATE,
  AFTER_DATE, with a value in days/months/years or a calendar date
- **action** — `DateBulkAction`, eight of them: engelle, sessize al, engel
  kaldır, sessizden çıkar, takip et, engel kaldır ve takip et, sessizden çıkar
  ve takip et, takipten çıkar

`ListsActivity.askDateBasedAction():219` offers three fixed rows instead:

```
blocked → unblock   (UNDOBAN / USER)
muted   → unmute    (UNDOBAN / MUTE)
blocked → mute      (BAN / MUTE)
```

No author-list source, none of the five follow-flavoured actions, and no
criterion — the run silently reuses whatever `dateFilterRules` the settings
screen happens to hold. `DateBulkSource` and `DateBulkAction` have no Kotlin
counterpart at all; `core/model/Enums.kt` stops at `BanSource`.

Two of those three rows are also wrong, not merely few. `OpsModule.kt:128` maps
the source to a fixed list:

```kotlin
BanSource.DATE_BASED_BULK ->
    RelationListTask(request.source, TargetType.USER, runner, scrape)
```

`RelationListTask`'s second argument is the list it reads (`Tasks.kt:438`, then
`ScrapeClient.kt:122` — `m` blocked, `i` title-blocked, `u` muted). It is pinned
to USER, so **every** date-based run scrapes the blocked list. "muted → unmute"
reads the blocked list and sends `removerelation … r=u` for people who were never
muted. The dialog names a source the engine does not have; the label is the only
place the choice exists.

The date filter itself is sound and stays as it is: `OperationWorker.kt:411-426`
resolves the rules once per run and gates every target through
`DateFilter.allows`. What is missing is a way to say which rule *this* run uses,
distinct from the standing protection rule in settings — in the extension those
are two different controls, and conflating them means a one-off "unblock
everyone I blocked before 2020" edits the rule that protects decade-old accounts
from every future run.

## What Changes

- `DateBulkSource` and `DateBulkAction` gain Kotlin counterparts in `core:model`,
  named and valued as in `enums.js:102-117`.
- `OperationRequest` gains two fields, both defaulted so an in-flight run
  deserialises unchanged:
  - `relationListOf: TargetType?` — which list a `DATE_BASED_BULK` run reads.
  - `dateRule: DateFilterRule?` — the run's own criterion, overriding the saved
    rules for that run only.
- `DateCriteria`, `DateFilterRule` and `DateFilter` move from `core:datastore` to
  `core:model`. They are pure data and a pure predicate — `core/model`'s own
  build file already claims "the date-filter predicates are pure functions" — and
  `ops:engine` is a JVM module that cannot depend on an Android DataStore module
  to carry a rule inside a request.
- `OpsModule` honours `relationListOf`, so the muted source reads the muted list.
- `OperationWorker` prefers `request.dateRule` over the saved rules when present.
- The author-list source resolves nicks up front and runs as `BanSource.LIST`,
  the way every other author-list run already does.
- `askDateBasedAction` is replaced by a dialog with the extension's four
  controls, and the last choice is remembered in `EksiConfig` — the extension's
  `createDefaultDateBulkConfig` (`config.js:58-66`), same defaults: muted users,
  OLDER_THAN, 3650 days, sessizden çıkar.
- `ParityTest` gains a case asserting every `DateBulkSource` and `DateBulkAction`
  in `enums.js` has a Kotlin counterpart, so the next one added there fails the
  build here.

## Non-goals

- Changing what the date filter *means*. `DateFilter.allows` keeps requiring
  every enabled rule to pass and keeps refusing an unresolvable registration
  date, which is the divergence from `utils.js:238` that `ParityTest` already
  records as deliberate.
- Changing the standing `dateFilterRules` in settings, or the rule editor. A
  per-run criterion is an override, not an edit.
- Applying a per-run criterion to any source other than `DATE_BASED_BULK`. Every
  other operation keeps using the saved rules exactly as it does now.
- The extension's months/years unit as a stored unit. It is entered in months or
  years and normalised to days on the way in, because `DateFilterRule.days` is
  what the predicate compares and two representations of one number is how they
  drift apart.

## Impact

- Affected specs: `android-operations`
- Affected code: `core/model` (new `DateBulk.kt`, moved `DateFilter.kt`, new
  serialization plugin); `core/datastore/Config.kt` and `Stores.kt`;
  `ops/engine/OperationContext.kt`; `ops/runtime/di/OpsModule.kt` and
  `OperationWorker.kt`; `feature/lists/ListsActivity.kt`, `ListsViewModel.kt`,
  new `dialog_date_bulk.xml`, strings; `feature/settings/SettingsActivity.kt`
  (import only); `app/ParityTest.kt`
- No change to `frontend/app/` runtime code.
