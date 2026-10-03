## Why

Following someone you have blocked or muted does not show them to you: Ekşi
holds block (`r=m`), mute (`r=u`) and follow (`r=b`) as independent relations,
so the follow succeeds and the account stays hidden. Both clients therefore lift
a block or mute before every follow. To know which targets needed it, both read
the account's entire blocked and muted lists first, whatever the run's size.

That cost belongs to the account, not to the run. On Android, "yazarı takip et"
against an account with 5,000 blocks read 200+ pages to follow one person, and
the walk published its count through the collection line, so the operations
screen counted the user's blocks as if they were the run's targets. Reported
against all four follow actions (yazarı, favlayanları, takipçilerini, takip
ettiklerini takip et): "they start to count all the users even for users that do
not have many followers". The extension did the same before its own target
collection, and on FAV and FOLLOW follow runs additionally read the user's
followings list and the blocked lists a second time for analysis that only a
restricting run uses.

## What Changes

- The restriction lookup runs after the targets are known and is budgeted by
  their count: at most `LOOKUP_PAGES_PER_TARGET` (10) relation-list pages per
  pending target, shared by the blocked and muted lists. A list that does not end
  within the budget is not read, and every target is lifted blind on it — a
  removal of a relation that does not exist is accepted by the site.
- Android: the run's target total is published before the lookup, and the lookup
  publishes no collection count.
- Extension: the up-front lookup moves into each follow path (SINGLE, LIST
  `TAKIP_ET`, FAV, FOLLOW, FOLLOWEES) and the bulk author-list `TAKIP_ET`. The
  title-block list is no longer read for a follow. A failed lookup page leaves
  that list unread (blind) instead of returning a partial list.
- Extension: follow runs skip the three restricting-run analysis steps on FAV and
  FOLLOW — protect followed users, only-required-actions, and the date filter.
  The date filter was taking its "block" set as the accounts to follow.

**frontend/app/ runtime code is touched**: `background.js`, `scrapingHandler.js`,
`programController.js`, and the release notes in `changelog.js`. This is a bug
fix to the shipped extension, made in the same change as its Android twin per
the repo's feature-parity rule.

## Capabilities

### New Capabilities
- `follow-restriction-lifting`: how a follow clears a block or mute first, how the
  clients find out which targets need it, and what that lookup may cost.

### Modified Capabilities
(none — no existing spec covers lifting restrictions before a follow;
`android-operations` already states that following is not a restriction for the
date filter, which the extension now matches)

## Non-goals

- Reading block or mute state per target. The profile page exposes block state
  (`.relation-link[data-add-caption="engelle"][data-added]`) but no mute state,
  and `/follower` / `/following` carry only `Id`, `Nick.Value`, `IsBuddy` and
  `IsFollowCurrentUser`.
- Using the Room-synced relation lists as the lookup. They go stale the moment a
  block happens on another device.
- Skipping targets already followed (`IsBuddy`) in follow runs. An account can be
  followed and blocked at once, which is the case lifting exists for.
- Making the Android lookup survive a failed page (see design).

## Impact

- `android/ops/engine/.../Tasks.kt` — `TargetRunner.applyToAll` and
  `restrictionsToLift`; `TargetRunnerTest` gains four cases.
- `frontend/app/assets/js/scrapingHandler.js` — `FollowRestrictions`,
  `FOLLOW_LOOKUP_PAGES_PER_TARGET`, `scrapeFollowRestrictions`.
- `frontend/app/assets/js/background.js`, `programController.js` — call sites.
- `changelog.js` and `ReleaseNotes.kt` — 0.5.3 notes, mirrored.
- No backend, manifest, selector or version change.
