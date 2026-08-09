## Why

Four things the extension gives its users have no counterpart in the app. Three
are surfaces that were simply never ported. The first is a defect.

**The registration-date cache is unbounded and unreachable.**
`RegistrationDateCacheDao` already has `trimExpired()` and `size()`. Neither has
a production caller — the only references in the repository are
`EksiDatabaseTest.kt:95-96`. So the table gains a row per nick on every
date-filtered run and never gives one back, and the 30-day TTL that
`android-persistence` requires only ever governs whether a row is *read*, never
whether it is kept. An author list may hold 10,000 nicks; a user who runs a
date-filtered operation over one, monthly, accumulates rows that are dead the
day after they expire and are never reclaimed. The extension exposes exactly
this — total, valid, expired, and a clear button (`faq.html:382-391`).

**Storage is not inspectable.** `faq.html:395-402` shows usage against the 5 MB
quota with a clear button. The app deliberately has no such ceiling, which makes
inspection *more* useful rather than less: nothing bounds the database, so the
only way a user learns it has grown is that their device tells them.

**Help was not ported.** The extension's popup offers two destinations — "Ana
İşlemler" and "Ayarlar ve Yardım". The settings half was ported in full,
including a date-filter rule editor the extension does not better. The Yardım
half was not ported at all: no FAQ, about or how-to screen exists anywhere in
the app.

**Release notes are not shown.** `background.js:1095-1101` opens `welcome.html`
on INSTALL *and* UPDATE, and `changelog.js` keys notes by version so an upgrading
user is told what changed. The app ships a version in `android/version.json` and
says nothing about it.

## What Changes

- Prune expired registration dates, so the TTL bounds the table's size and not
  only the freshness of a read.
- Add a maintenance section to Settings: cache totals, expired count, and a
  clear; database size, and a clear that refuses to run while an operation is
  in flight.
- Add a help screen carrying the extension's Kullanım Kılavuzu, rewritten for
  this app's navigation rather than transcribed — the extension's guide
  describes a popup and two browser tabs that do not exist here.
- Show the version and its release notes on the first launch after an install or
  an update, once per version.

## Impact

- Affected specs: `android-persistence`, `android-onboarding` (new)
- Affected code: `RegistrationDateCacheDao`, `OperationWorker`,
  `feature:settings`, `app` (first-run routing)
- No change to `frontend/app/` runtime code.

Clearing stored data destroys a user's synced lists and their operation history.
It is confirmed, it names what it will delete, and it is refused outright while
an operation is running — a half-deleted database under a live worker is a
corruption, not an inconvenience.
