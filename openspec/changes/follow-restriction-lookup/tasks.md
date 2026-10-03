## 1. Android engine

- [x] 1.1 `TargetRunner.LOOKUP_PAGES_PER_TARGET = 10`; `restrictionsToLift(ctx,
      pending)` walks `relationPage(USER)` then `relationPage(MUTE)` against a
      shared budget, per page `ensureActive()` + `awaitReadPermit()`, no
      `publishCollecting`. Over budget → that list `null`.
- [x] 1.2 `Restrictions.isBlocked` / `isMuted` answer `true` for an unread list;
      the target loop asks them instead of testing set membership.
- [x] 1.3 Publish the run's total before the lookup; pass `pending = targets.size
      - cursor.index`.
- [x] 1.4 `TargetRunnerTest`: blind on a long list (10 reads, `r=m`, `r=u`,
      `r=b`, no collection count, total published first, lifts uncounted);
      exact on short lists; muted inherits the remainder; block runs read no
      lists.
- [x] 1.5 `./gradlew :ops:engine:test :ops:runtime:testDebugUnitTest
      :feature:settings:testDebugUnitTest :app:testDebugUnitTest` passes.

## 2. Extension

- [x] 2.1 `scrapingHandler.js`: `FOLLOW_LOOKUP_PAGES_PER_TARGET`,
      `FollowRestrictions`, `scrapeFollowRestrictions(pending)` — blocked then
      muted, shared budget, no title list, failed page → list unread, stops on
      `earlyStop`.
- [x] 2.2 `background.js`: drop the up-front `scrapeAuthorNamesFromBannedAuthorPage`
      walk; `prepareFollowClear(pending)` before the first action of SINGLE,
      LIST `TAKIP_ET`, FAV, FOLLOW and FOLLOWEES follows.
- [x] 2.3 `background.js`: `runsRestrictionAnalysis` gates protect-followed,
      only-required-actions and the date filter on FAV and FOLLOW.
- [x] 2.4 `programController.js`: `_followClearState(source, pending)` uses the
      budgeted lookup; `_followAfterClearing` asks `isBlocked` / `isMuted`.
- [x] 2.5 Node probe of `scrapeFollowRestrictions` with stubbed imports and fake
      `fetch`: budget stop, blind answers, no title list, slug matching, failed
      page, `earlyStop`.

## 3. Release notes

- [x] 3.1 Undated `0.5.3` entry in `changelog.js`, mirrored word for word in
      `ReleaseNotes.kt`; `npm run changelog` reports `docs/changelog.json` up to
      date (undated versions stay off the site).

## 4. Verification

- [ ] 4.1 On a device with a large blocked list: "yazarı takip et" starts
      following within seconds, the screen shows `1` as the total and never a
      collection count; the author is followed and, if blocked, unblocked.
- [ ] 4.2 Same check in the extension (Chrome and Firefox), plus
      "takipçilerini takip et" against an author with a handful of followers.
- [x] 4.3 `cd frontend/app && npm run check && npm run package`.
