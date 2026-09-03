## Why

The extension offers block and mute everywhere it shows a "who is this action's
audience" choice (an author, an entry's favers, an author's followers), but never
offers follow in those same spots — even though the underlying follow relation
(`TargetType.FOLLOW`, the `/userrelation/.../?r=b` endpoint) is already fully
implemented and already used by two other subsystems (the author-list bulk page's
"Takip Et" button, and the date-based bulk action's `TAKIP_ET`). Investigation
confirms the two on-page menus — the entry dropdown and the profile page — are
the only remaining gaps, plus one relation ("kişinin takip ettikleri" / who an
author follows) that has never had *any* action, block or follow, wired to it at
all despite the scraper for it already existing. This change closes all of it in
one pass so there's no partial coverage.

## What Changes

- **Entry dropdown menu** (`processEntryMenu`, `frontend/app/assets/js/script.js:269`):
  add "yazarı takip et" (follow the entry's author), "favlayanları takip et"
  (follow the entry's favers), "takipçilerini takip et" (follow the author's
  followers), and "takip ettiklerini takip et" (follow who the author follows) —
  one new button per existing block/mute button, plus the wholly-new followees
  case.
- **Profile page** (`processRelationButtons`, `frontend/app/assets/js/script.js:425`):
  add a "takip et" button alongside the existing block/mute button for the
  profile's own author, "takipçilerini takip et" alongside the existing
  "takipçilerini engelle/sessize al" button, and "takip ettiklerini takip et" as
  a new button (no block/mute counterpart exists to mirror).
- Fix `processHandler`'s `BanSource.SINGLE` branch (`background.js:665`) to
  actually forward `targetType == enums.TargetType.FOLLOW` into
  `performWithRetry` — today `TargetType.FOLLOW` is defined and fully supported
  by `relationHandler.performAction`, but this call site only checks for
  USER/TITLE/MUTE, so a follow request would silently no-op.
- Extend the `BanSource.FAV` and `BanSource.FOLLOW` (followers) loops in
  `processHandler` (`background.js` ~790, ~862) to branch on `targetType` the
  same way, instead of always deriving block/mute booleans from
  `config.enableMute` — these loops currently have no way to request a follow
  action at all.
- Add a new `BanSource` value for "the people this author follows" (working name
  `FOLLOWEES`, distinct from the existing `ClickSource.FOLLOWING`, which means
  something unrelated — the page the click happened on), a new `processHandler`
  branch that reuses `scrapingHandler.scrapeFollowing` (already implemented,
  currently only used internally to protect the extension user's own followed
  accounts from being blocked — never exposed as an action target), and follow
  as the only mode offered for it (no existing block/mute of followees to keep
  parity with).
- **BREAKING** (backend, additive-only in practice): add a new `BanSource` lookup
  row (pk 15, `"FOLLOWEES"`) via Django data migration in both
  `backend/django_EksiEngel/api/migrations/` and
  `backend/django_EksiEngel/client_data_collector/migrations/` — mirroring the
  existing `0008_widen_ban_source_and_seed_missing.py` pattern in each app —
  since `Action.ban_source` and the client analytics `BanSource` FK are keyed by
  the numeric pk the extension sends, matching `enums.BanSource` values
  one-to-one. No column changes, no data loss; marked BREAKING only because a
  reused extension build against an unmigrated backend would fail that one new
  `ban_source` value's FK lookup (all pre-existing ban sources are unaffected).
- No change needed to the author-list bulk page or date-based bulk action UI —
  both already have follow (and unfollow) wired end-to-end; confirmed during
  investigation, out of scope here.

## Capabilities

### New Capabilities
- `follow-action-parity`: follow-relation actions available at the same UI
  surfaces where block/mute actions exist today (single author, entry favers,
  author followers), plus the new followees audience.

### Modified Capabilities
(none — no existing spec currently covers the entry menu, profile page menu, or
`processHandler`'s per-target dispatch logic)

## Impact

- `frontend/app/assets/js/script.js` — `processEntryMenu`, `processRelationButtons`
  gain new buttons and handlers.
- `frontend/app/assets/js/background.js` — `processHandler`'s SINGLE branch gains
  a follow check; FAV and FOLLOW (followers) branches gain targetType-aware
  dispatch; a new branch handles the followees `BanSource`.
- `frontend/app/assets/js/enums.js` — new `BanSource.FOLLOWEES` value (next
  available numeric string, `"15"`).
- `frontend/app/assets/js/scrapingHandler.js` — `scrapeFollowing` gains a second
  caller (the new followees action path), unchanged itself.
- Backend: one new data migration per app (`api`, `client_data_collector`)
  seeding `BanSource` pk 15. No model/schema change, no code change beyond the
  migration files themselves — `BanSource` is looked up generically by pk
  everywhere it's already used.
- Chrome and Firefox both ship this — no manifest changes, no jsdom impact.
- Frontend runtime code IS touched (script.js, background.js, enums.js,
  scrapingHandler.js callers). Android is NOT touched.
