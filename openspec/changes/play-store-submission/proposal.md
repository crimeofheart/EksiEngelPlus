## Why

`0.6.5` is tagged, signed and downloadable from the website, but Google Play still
has no listing: the AAB sits in `frontend/publish/dist/` and every remaining step
is a Play Console form, a missing asset, or a human gate (12 testers × 14 days).
Those steps live only in a prose README (`android/play/README.md`), so nothing
tracks whether they are done, and the `play` channel minimum cannot be raised
until the store actually serves the build. The submission also carries three
policy risks — a WebView of a site we do not own, "Ekşi" in the name, and
automation against a third-party service — that have to be answered once, in the
listing and in the review notes, not improvised under review.

## What Changes

- The Play submission kit becomes a tracked, verifiable artifact set:
  `android/play/README.md` (answers), `icon-512.png`, `feature-graphic.png`,
  at least two phone screenshots, and a foreground-service video link.
- Store listing text is fixed to the kit: app name `EksiEngelPlus`, category
  Social, Turkish default with an English translation, and both descriptions
  opening with the "not an official Ekşi Sözlük app" disclaimer.
- The console declarations are recorded with the exact answers already derived
  from the code and the privacy page: **App access**, **Data safety**,
  **Foreground service type `dataSync`**, **Ads: no**, **Target audience 18+**,
  **Content rating (IARC) Social/communication**, **News: no**.
- The first-submission gate is explicit: a closed test track with the 0.6.5 AAB
  and **12 opted-in testers for 14 consecutive days** before production can be
  applied for, and the `play` channel minimum is raised with
  `tools/set-min-version.sh --min-play <version>` only after Play serves that
  version.
- Backend test coverage for the surface a Play release makes public
  (`api/test_release_info.py`, `api/test_update_policy.py`) becomes runnable
  locally and runs in CI, against the Postgres the migrations actually require.

Does this touch `frontend/app/` runtime code? **No.** The extension source is
untouched; only `frontend/app/package.json` version bookkeeping continues to be
driven by the existing tooling, and `npm run check` must stay green.

## Capabilities

### New Capabilities

- `play-store-release`: what the Play listing must contain, which console
  declarations are authoritative, how the first submission is gated on a closed
  test, and when the `play` update channel may be raised.

### Modified Capabilities

- `release-builds`: the `play` AAB built by `tools/build-release.sh` becomes the
  artifact that is uploaded to Play, so the capability gains the requirement that
  a release is not "published" until Play serves it and the `play` channel is
  raised afterwards.

## Impact

- `android/play/` — README kept in step; new `android/play/screenshots/`.
- `android/play/README.md` — video URL and tester instructions added.
- `backend/django_EksiEngel/` — `settings_test.py`, `DATABASE_HOST`/`DATABASE_PORT`
  as env keys, and `backend/requirements-dev.txt`, so `manage.py test` runs
  against a throwaway Postgres and the existing 14 test modules finally execute
  somewhere.
- `.github/workflows/check.yml` — a backend test job.
- `.github/workflows/check.yml` and `release.yml` are otherwise unchanged; CI
  still attaches no artifacts and never sees the upload key.
- No change to `android/app/`, `frontend/app/assets/`, or the backend's runtime
  behaviour. No new dependency in `frontend/app`.

## Non-goals

- Publishing to any store other than Google Play (Chrome Web Store and AMO stay
  manual, as today).
- Automating the Play Console. There is no public API for a first submission's
  App content forms, and the upload key must not leave the maintainer's machine.
- Writing real release notes, screenshots of a personal account, or any asset
  that shows another user's nickname outside a disposable test account.
- Changing the `github` flavor, the sideloaded APK, or the update policy format.
- Solving the WebView/impersonation policy question by changing what the app is:
  the listing leads with the app's own work instead.
