## 1. Enum and backend seed

- [ ] 1.1 Add `FOLLOWEES: "15"` to `enums.BanSource` in
      `frontend/app/assets/js/enums.js`.
- [ ] 1.2 Add a new Django migration in `backend/django_EksiEngel/api/migrations/`
      (following the `0008_widen_ban_source_and_seed_missing.py` pattern) that
      seeds `BanSource.objects.get_or_create(pk=15, defaults={"ban_source":
      "FOLLOWEES"})`, with a `noop` reverse function.
- [ ] 1.3 Add the mirrored migration in
      `backend/django_EksiEngel/client_data_collector/migrations/` (following
      its `0007_widen_ban_source_and_seed_missing.py`), same pk/value.
- [ ] 1.4 Run both migrations locally against the dev DB and confirm
      `BanSource.objects.get(pk=15).ban_source == "FOLLOWEES"` in both apps.

## 2. Fix SINGLE branch's missing follow forward

- [ ] 2.1 In `background.js`'s `processHandler`, `BanSource.SINGLE` branch
      (~line 665), add `targetType == enums.TargetType.FOLLOW` as the 6th
      argument to the `performWithRetry` call.
- [ ] 2.2 Manually verify: dispatch a `BanSource.SINGLE`/`TargetType.FOLLOW`
      message directly (e.g. via a temporary console call or the new entry-menu
      button from section 3) and confirm the eksisozluk follow relation is
      created (check the author's profile "takipçi" state or the response).

## 3. Entry menu buttons (`processEntryMenu`, script.js:269)

- [ ] 3.1 Add "yazarı takip et" button: dispatches `BanSource.SINGLE`,
      `BanMode.BAN`, `TargetType.FOLLOW` for the entry's author. Insert
      immediately after the existing "yazarı engelle/sessize al" button, reusing
      the existing `insertBefore`/`nextSibling` chain.
- [ ] 3.2 Add "favlayanları takip et" button: dispatches `BanSource.FAV`,
      `BanMode.BAN`, `TargetType.FOLLOW`. Insert after "favlayanları engelle".
- [ ] 3.3 Add "takipçilerini takip et" button: dispatches `BanSource.FOLLOW`,
      `BanMode.BAN`, `TargetType.FOLLOW`. Insert after "takipçilerini
      engelle/sessize al".
- [ ] 3.4 Add "takip ettiklerini takip et" button: dispatches
      `BanSource.FOLLOWEES`, `BanMode.BAN`. Insert after the followers button.

## 4. Profile page buttons (`processRelationButtons`, script.js:425)

- [ ] 4.1 Add a "takip et" button next to the profile's existing block/mute
      button: dispatches `BanSource.SINGLE`, `BanMode.BAN`, `TargetType.FOLLOW`
      for the profile's author.
- [ ] 4.2 Add "takipçilerini takip et" next to the existing "takipçilerini
      engelle/sessize al" button: dispatches `BanSource.FOLLOW`, `BanMode.BAN`,
      `TargetType.FOLLOW`.
- [ ] 4.3 Add "takip ettiklerini takip et": dispatches `BanSource.FOLLOWEES`,
      `BanMode.BAN`.

## 5. processHandler: FAV and FOLLOW (followers) branches gain follow mode

- [ ] 5.1 In the `BanSource.FAV` loop (~background.js:790), branch on
      `targetType == enums.TargetType.FOLLOW`: when true, call
      `performWithRetry(banMode, value.authorId, false, false, false, true)` for
      every scraped user (skip the `isBannedUser`/`isBannedMute` dedup checks,
      per design's Risk note — the endpoint is idempotent for already-following);
      when false, keep the existing block/mute logic unchanged.
- [ ] 5.2 Apply the same targetType branch to the `BanSource.FOLLOW`
      (followers) loop (~background.js:862).
- [ ] 5.3 Manually verify both: run "favlayanları takip et" and "takipçilerini
      takip et" against a low-traffic entry/profile and confirm each scraped
      user is followed (not blocked/muted), independent of the current
      `config.enableMute` setting.

## 6. processHandler: new FOLLOWEES branch

- [ ] 6.1 Add an `else if (banSource === enums.BanSource.FOLLOWEES)` branch in
      `processHandler`, mirroring the structure of the existing
      `BanSource.FOLLOW` (followers) branch: notify scraping start, call
      `scrapingHandler.scrapeFollowing(singleAuthorName)`, loop over the result
      calling `performWithRetry(banMode, value.authorId, false, false, false,
      true)` for each, track `successfulAction`/`performedAction`, and finish
      via the same `notificationHandler` calls the FOLLOW branch uses.
- [ ] 6.2 Manually verify "takip ettiklerini takip et" against an author with a
      small followee list and confirm each followee is followed.

## 7. Verification

- [ ] 7.1 Load unpacked in Chrome (`npm run switch:chrome`), exercise all six
      new buttons (entry menu ×4, profile page ×3, minus the one shared "takip
      et" already covered) end-to-end per CLAUDE.md's load/reload instructions.
- [ ] 7.2 Load temporary add-on in Firefox (`npm run switch:firefox`), repeat
      7.1 — required by the repo's Chrome/Firefox parity expectation even though
      this change touches only shared JS (script.js, background.js, enums.js,
      scrapingHandler.js caller).
- [ ] 7.3 `cd frontend/app && npm run check && npm run package` — confirm the
      build still passes and both zips produce cleanly.
- [ ] 7.4 Confirm the Django backend accepts a `FOLLOWEES` `Action` POST
      end-to-end against a locally migrated dev database (not just the raw
      `BanSource` row check from task 1.4) — run the extension against a local
      backend instance if available, or exercise
      `WriteActionViewSerializer`/`CollectActionDataSerializer` directly with a
      `ban_source: 15` payload.
