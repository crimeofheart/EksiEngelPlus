## Context

Every block/mute action in the extension ultimately calls
`relationHandler.performAction(banMode, id, isTargetUser, isTargetTitle,
isTargetMute, isTargetFollow)` (relationHandler.js:13), which POSTs to
`{origin}/userrelation/{addrelation|removerelation}/{id}?r={m|i|u|b}` — `b` is
already the follow relation code (relationHandler.js:112-120,
`#prepareHTTPRequest`). `isTargetFollow` and `TargetType.FOLLOW` ("4" in
enums.js) have existed since the relation-handler layer was generalized, and are
already exercised by two subsystems:
- `authorListPage.js`'s "Takip Et"/"Takip Etme Bırak" buttons (`banSource:
  LIST`, `action: "TAKIP_ET"`/`"TAKIPTEN_CIKAR"`), handled in
  `programController.js` (`startDateBasedBulkAction`-adjacent switch,
  lines ~397-425) via `_performActionWithRetry(banMode, authorId, false, false,
  false, true)`.
- The date-based bulk action UI's `DateBulkAction.TAKIP_ET` /
  `ENGEL_KALDIR_VE_TAKIP_ET` / `SESSIZDEN_CIKAR_VE_TAKIP_ET`, same switch.

Both of those are confirmed working and out of scope for this change.

What's missing is the two on-page menus, both in `frontend/app/assets/js/script.js`:

- `processEntryMenu` (line 269) injects three buttons into the Ekşi Sözlük entry
  dropdown (works on `/entry/*` and `/sorunsal/*`): "yazarı {engelle|sessize al}"
  (`BanSource.SINGLE`), "favlayanları engelle" (`BanSource.FAV`),
  "takipçilerini {engelle|sessize al}" (`BanSource.FOLLOW`, i.e. the author's
  followers — confusingly named relative to `TargetType.FOLLOW`, which means
  "the relation being applied is 'follow'", not "the audience is followers"; the
  codebase already lives with this naming, this change does not rename it).
- `processRelationButtons` (line 425) injects a block/mute button + undo button
  onto the author's own profile page (`/biri/{user}`), a title-block button, and
  a "takipçilerini {engelle|sessize al}" button.

Both dispatch through `EksiEngel_sendMessage(banSource, banMode, entryUrl,
authorName, authorId, targetType, clickSource, titleName, titleId,
timeSpecifier)` → `background.js`'s message listener (line 455) → `processHandler`
(line 605), which branches on `banSource`:

- `SINGLE` (line 663-669): a single `performWithRetry(banMode, singleAuthorId,
  targetType == USER, targetType == TITLE, targetType == MUTE)` call. **This is
  the actual bug**: it never checks `targetType == FOLLOW`, so even though the
  message-passing layer, `TargetType.FOLLOW`, and `relationHandler` all already
  support follow, this specific branch drops it on the floor — the 6th
  (`isTargetFollow`) argument to `performWithRetry` is simply never passed as
  `true` from here.
- `FAV` (~line 790) and `FOLLOW`/followers (~line 862): both loop over a
  `scrapedRelations` map and call `performWithRetry(banMode, value.authorId,
  (!value.isBannedUser && !config.enableMute), (!value.isBannedTitle &&
  config.enableTitleBan), (!value.isBannedMute && config.enableMute))` — five
  arguments, no follow branch at all, and critically the block-vs-mute choice
  here is driven entirely by the global `config.enableMute` setting, not by
  anything in the message. There is currently no way for a FAV/FOLLOW-sourced
  action to be anything other than "block or mute, whichever the user's global
  setting says."
- No branch exists for "the people this author follows" — `scrapeFollowing`
  (scrapingHandler.js:864) exists and works, but its only current caller is the
  `enableProtectFollowedUsers` analysis path (background.js:718, 804, 876),
  which uses it defensively to *exclude* the extension user's own followed
  accounts from a block run — it has never been the audience of an action
  itself.

Backend: `Action.ban_source` (api/models.py:108) and the client-analytics
`BanSource` FK (client_data_collector/models.py) are both plain
`ForeignKey(BanSource, on_delete=models.PROTECT)` fields on a `ModelSerializer`,
so DRF exposes them as `PrimaryKeyRelatedField` — the extension POSTs the raw
integer pk (`enums.BanSource` values, sent as numeric strings), and that pk must
already exist as a row. `api/migrations/0008_widen_ban_source_and_seed_missing.py`
(and its `client_data_collector` mirror `0007_...`) is exactly the precedent:
a `BAN_SOURCES` list of `(pk, name)` tuples seeded via
`BanSource.objects.get_or_create(pk=pk, defaults={"ban_source": value})`, with a
code comment noting the pk *must* match what `enums.js` sends or "the action POST
fails FK validation and the telemetry is dropped." `TargetType` already has
`FOLLOW` seeded at pk 4 in `0007_seed_lookup_data.py` — no backend change needed
there, only for the new `BanSource`.

## Goals / Non-Goals

**Goals:**
- Every current block/mute UI entry point (entry menu, profile page) gets a
  follow counterpart, reusing the existing relation pipeline — no new HTTP
  client code, no new endpoint.
- Add the one relation that's never had *any* action (author's followees) as a
  follow-only capability, since it has no existing block/mute counterpart to
  parallel.
- No gaps: this design explicitly enumerates every current block/mute call site
  found in `script.js` and maps each to its follow counterpart or explains why
  none applies (title-block has no follow analog — you can't follow a title).

**Non-Goals:**
- No unfollow UI on these menus. The user's ask and the confirmed gap are about
  adding "follow" alongside "block/mute"; `TAKIPTEN_CIKAR` (unfollow) already
  exists in the bulk subsystems for when it's needed. Revisit only if requested.
- No change to `authorListPage.js` or the date-based bulk action UI — both
  already have follow/unfollow, confirmed working, excluded from scope.
- No android/ work — these menus don't exist there in this form.
- No block/mute for "followees" — deliberately follow-only, since adding a
  block/mute-of-followees feature is a separate, larger ask than "add follow
  where block/mute exists."

## Decisions

**Name the new `BanSource` value `FOLLOWEES`, not `FOLLOWING`.** `ClickSource`
already has a value literally spelled `FOLLOWING` (enums.js line 24) meaning "the
click happened while viewing someone's followed-users listing page" — an
unrelated concept (where the click came from, not who the action targets).
Reusing that name for the new BanSource would make `banSource ===
enums.BanSource.FOLLOWEES` and `clickSource === enums.ClickSource.FOLLOWING`
look like they mean the same thing when they don't. Alternative considered:
`AUTHOR_FOLLOWING` — rejected as more verbose for no clarity gain over
`FOLLOWEES`, which is the correct English term for "people a given account
follows."

**Extend `performWithRetry`'s call sites to read `targetType` from the message,
rather than introducing a separate "mode" field.** The message already carries
`targetType` end-to-end (`EksiEngel_sendMessage` → `processHandler`'s
parameter); the SINGLE branch already uses it this way, just incompletely. FAV
and FOLLOW(followers) branches currently *ignore* the message's `targetType` and
derive block-vs-mute purely from `config.enableMute` — this change makes them
consult `targetType` the same way SINGLE does: when `targetType ==
TargetType.FOLLOW`, pass `isTargetFollow: true` and all of
`isTargetUser`/`isTargetTitle`/`isTargetMute: false`; otherwise keep today's
existing block/mute logic unchanged. Alternative considered: give follow its own
`BanMode`-like dimension — rejected, `targetType` already exists for exactly
this "what relation to apply" purpose and SINGLE already sets the precedent.

**New `processHandler` branch for `BanSource.FOLLOWEES` mirrors the existing
`BanSource.FOLLOW` (followers) branch structure** (scrape → loop → per-user
`performWithRetry`), swapping `scrapingHandler.scrapeFollower` for
`scrapingHandler.scrapeFollowing`, and hardcoding `isTargetFollow: true` (no
block/mute variant, per Non-Goals) rather than reading `targetType` — there is
no block/mute predecessor for followees, so nothing to preserve for backward
compatibility there.

**Backend migration mirrors `0008_widen_ban_source_and_seed_missing.py` exactly**:
a new migration in each app appending `(15, "FOLLOWEES")`, using
`get_or_create` (idempotent, safe to run against a fresh or already-migrated
db) and a `noop` reverse function with the same "PROTECTed, don't delete"
rationale as the precedent. No `AlterField` needed this time — the column is
already widened to 30 chars by migration 0008/0007.

**Button placement**: "takip et" buttons are added immediately after their
block/mute sibling in both menus (e.g. right after "yazarı engelle/sessize al"
comes "yazarı takip et"), keeping the existing insertion-point logic
(`lastRelevantItem`/`insertBefore` chains in `processEntryMenu`,
`parentNode.append`/`insertBefore` in `processRelationButtons`) rather than
introducing a new menu section — minimizes visual disruption to the existing
Ekşi Sözlük dropdown/profile chrome.

## Risks / Trade-offs

[Following someone via `/userrelation/addrelation/{id}?r=b` while they are
already followed] → The endpoint's existing response-code handling already
treats `0` and `2` ("already banned"/already-related) as success
(relationHandler.js:187-188) for BAN mode uniformly across relation types; no
new handling needed, this is exercised today by the bulk-follow paths already.

[FAV/FOLLOW(followers) branches currently skip already-blocked/muted users via
`isBannedUser`/`isBannedMute` flags scraped alongside the relation list — those
flags say nothing about follow state] → When `targetType == FOLLOW`, skip that
dedup check entirely and always attempt the follow relation for every scraped
user; the endpoint's already-following idempotency (see above) makes a redundant
attempt harmless, just a wasted request under the rate limit budget. Acceptable
given followers/favers lists are typically far smaller than mass block runs.

[Two backend apps (`api`, `client_data_collector`) each keep their own
`BanSource` table and must be migrated in lockstep or client analytics silently
mismatch `Action` telemetry] → Both migrations ship in the same commit, mirroring
how 0008/0007 already exist as a matched pair; call this out explicitly in tasks
so it isn't missed.

[Rate limit: ~12 actions/minute server-side] → Follow actions go through the
same `performWithRetry`/cooldown-on-429 machinery as block/mute (`handleCooldown`,
background.js:639), so pacing is inherited for free — no new throttling logic.

## Migration Plan

Frontend: additive UI + a bug fix (SINGLE branch) + new enum value + new
`processHandler` branch. No stored-state migration; existing queued/historical
tasks are unaffected since `BanSource.FOLLOWEES` didn't exist for them to use.

Backend: one data migration per app, additive-only (`get_or_create`, no
`AlterField`, no deletion). Deploy via the existing `git pull` +
`systemctl restart gunicorn-eksiengel` flow (CLAUDE.md's Website section) — no
`collectstatic` needed (no static assets touched), `migrate` **is** needed this
time. Rollback: the migrations are safe to leave applied even if the frontend
change is reverted (an unused lookup row is harmless); no destructive down
migration needed beyond what the existing `noop` precedent establishes.

## Open Questions

- Whether "takip ettiklerini takip et" (followees) should also appear on the
  entry menu (where "author" is the entry's author) as well as the profile page
  — default to yes, both, for consistency with how "takipçilerini" (followers)
  already appears in both places.
- Whether to surface a distinct `ClickType`/analytics event for these new
  buttons or rely on the `Action` row's `target_type=FOLLOW`/`ban_source=FOLLOWEES`
  fields for visibility in admin — default to relying on the existing `Action`
  telemetry (no new `ClickType`), since that's exactly the pattern SINGLE/FAV/FOLLOW
  block actions already use; revisit only if admin visibility proves insufficient
  in practice.
