## Context

`0.6.5` is tagged and both Android artifacts are signed with the pinned upload key
(`frontend/publish/dist/eksiengelplus-0.6.5.{aab,apk}`; certificate
`0F:8C:86:D6…`, the value `tools/build-release.sh:31` enforces). The `play` flavor
already exists and forbids self-update (`android/app/build.gradle.kts:119-122`,
`SELF_UPDATE=false`), and the update gate already has a `play` channel
(`openspec/specs/app-update-gate/spec.md`). What is missing is entirely outside
the APK: Play Console listing, App content answers, two assets, and a 12-tester
closed test.

Today those answers live in `android/play/README.md` as prose. Nothing verifies
them, nothing records what was actually submitted, and the only thing tying the
listing to the code is a sentence asking the reader to keep them in step.

## Goals / Non-Goals

**Goals:**

- One authoritative, in-repo source for the listing text, the declaration
  answers and the assets, so a listing can be rebuilt from a checkout.
- Declarations that are derived from code the repo already pins: the manifest's
  `dataSync` service type, the `play` flavor's no-self-update rule, and the
  privacy page's data inventory.
- An explicit, dated record of the closed test, so the 14-day gate cannot be
  misremembered.
- The backend surface a store release exposes (`/`, `/privacy/`, `/policy/`) runs
  its tests on every push instead of never.

**Non-Goals:**

- Console automation. Uploads stay manual; the upload key never leaves the
  maintainer's machine (`CLAUDE.md`, "Release flow").
- Chrome Web Store / AMO process changes.
- Any `frontend/app/` runtime change.

## Decisions

**1. The kit stays in `android/play/`, and grows a `screenshots/` directory.**
Alternative: keep answers only in the Console. Rejected — the Console is
write-once in practice, and a Data safety answer that drifts from the privacy
page is exactly the class of defect that gets an app removed later. Files can be
diffed in review; Console text cannot.

**2. Data safety is restated from the two existing sources, not from the Console.**
The inventory in `android/play/README.md` already cites
`backend/django_EksiEngel/api/templates/privacy/index.html` ("Sunucumuza
gönderilenler") and `openspec/specs/android-persistence/spec.md`. The spec keeps
that citation as a requirement rather than a note, so a privacy-page commit that
adds a field is incomplete until the table moves with it.

**3. The foreground service type is the manifest's, not a description.**
`android/app/src/main/AndroidManifest.xml:20` declares
`FOREGROUND_SERVICE_DATA_SYNC`; the merged manifest of every variant carries
`androidx.work.impl.foreground.SystemForegroundService` with
`android:foregroundServiceType="dataSync"` (from WorkManager, not app source).
The declaration therefore names the library service, which is what the reviewer
sees, and the video shows the notification staying up after the app is
backgrounded.

**4. Screenshots come from a disposable test account, committed as PNG.**
Alternative: emulator with mocked data. Rejected — the screenshots must show the
real site inside the real app; a mocked screenshot is a misrepresentation and
Play compares against the build. `adb exec-out screencap -p` is the capture path
already documented.

**5. Backend tests run against a throwaway Postgres in CI, not SQLite.**
The obvious cheap route — in-memory SQLite — does not survive the migrations:
`history/migrations/0002_trust_and_person_relations.py:159-168` declares a
covering index (`models.Index(..., include=(...))`), which SQLite cannot create,
and `:192` runs a raw `RunSQL` backfill. Skipping migrations
(`MIGRATION_MODULES` disabled) would test a schema that exists nowhere. So the
job runs a `postgres:16` service, `django_EksiEngel/settings_test.py` supplies
the env keys `settings.py:16-99` require, and `DATABASE_HOST`/`DATABASE_PORT`
became env keys (`settings.py:95-99`) so the same module reaches both the CI
service on 5432 and a local container on another port. `requirements-dev.txt`
pins `psycopg2-binary` instead of `psycopg2` because neither CI nor a laptop is
guaranteed to have `pg_config`. Alternative considered: install Postgres on
every machine — heavier than a service container for the same coverage.

**6. The `play` minimum is raised by the existing tool, after rollout.**
`tools/set-min-version.sh:14` already documents `--min-play` as "raise after Play
serves V to everyone". No new tooling. The 7-day grace in the `play` flavor is a
safety net, not the schedule.

**7. Announcements describe what is served.**
`docs/eksisozluk/announcements/` gains an entry only for a version the stores
actually serve, following the 0.5.2 precedent (tagged, never shipped, never
announced).

## Risks / Trade-offs

- **Play rejects the app as a WebView wrapper** → the listing and review notes
  lead with bulk operations, background runs, date filter and sync; the site is
  framed as the surface those act on. Residual risk: rejection is a judgement
  call and can cost a review cycle.
- **"Ekşi" in the name triggers impersonation/IP review** → every description
  opens with the unofficial notice; no Ekşi Sözlük brand asset in icon, feature
  graphic or screenshots.
- **12 testers × 14 days cannot be compressed** → the track must be opened early,
  in parallel with the remaining asset work, or the release sits waiting on a
  clock that only starts when the first tester opts in.
- **Screenshots can leak real nicknames** → test account only, and the spec's
  scenario makes a leak a blocking defect, not a nit.
- **SQLite was never an option, and the first run proved it** → the suite wanted a
  real Postgres; with one it passes 167/167 in ~30 s. Keep the service container,
  and if a test ever needs a Postgres-only feature it must use it, not skip.
- **A wall-clock assertion in `history/tests/test_legacy.py` measured the
  database's latency** → the 10 000-target ingest bound moved from 2 s to 30 s,
  because `refresh_actor_relations` still issues one statement per author and a
  container round trip costs more than a local socket. The bound still catches an
  accidental per-row loop; it no longer fails on host speed.
- **A minimum raised before rollout blocks Play users for 7 days** → the spec
  treats that as a process defect; the tool's own help text is the reminder.

## Migration Plan

No runtime migration. Order: open the closed test first (clock starts), then the
listing and declarations, then production, then raise `--min-play`. Rollback for
a bad listing is a Console edit; for a bad policy it is `--min-play` back down,
which only affects devices that never fetched the higher value.

## Open Questions

- Does the developer account already have an app entry for
  `org.duzgun.eksiengelplus`, or is this a first creation? (Changes step 1 only.)
- Who are the 12 testers, and do they all have Google accounts that can install
  from a closed track in Turkey?
