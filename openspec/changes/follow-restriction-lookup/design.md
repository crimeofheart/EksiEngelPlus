## Context

Lifting before a follow shipped in both clients as one unbounded pass over the
relation lists:

- Android: `restrictionsToLift` (`Tasks.kt:316` before this change) called
  `scrape.allRelations(USER)` and `allRelations(MUTE)` with
  `ctx.collectPage(found)` as the page hook, from `applyToAll` before the run's
  total was published. `collectPage` publishes through `onCollecting`
  (`OperationWorker.kt:470`), so the screen showed the walk's count as targets
  found.
- Extension: `processHandler` read `scrapeAuthorNamesFromBannedAuthorPage()`
  (`background.js:761` before) for every follow run, before any branch had
  collected targets. That function walks three lists — blocked, title-blocked and
  muted — and swallows errors, returning whatever it had.

Costs that bound the choice:

- Reads: `ReadPacer` spaces them ≥250 ms (`ActionPacer.kt:187`); a relation page
  holds 25 (`ScrapeClient.RELATION_PAGE_SIZE`).
- Actions: 12 per 62 s window (`ActionPacer.DEFAULT_PERMITS_PER_WINDOW`,
  `WINDOW_MS`), ~5 s each amortised once the window is spent.
- A removal of a relation that does not exist answers `{"result": true}`
  (`RelationClient.kt:79`, measured during android-spike), so lifting blind is
  correct, only more expensive.

## Decisions

### Budget the walk by the run, fall back to lifting blind

Two blind lifts cost ~10 s of rate limit per target; a page costs well under a
second. The walk therefore gets `LOOKUP_PAGES_PER_TARGET = 10` pages per pending
target (`Tasks.kt:64`, mirrored as `FOLLOW_LOOKUP_PAGES_PER_TARGET` in
`scrapingHandler.js:46`), shared across both lists: blocked first, muted with
whatever is left. A list that does not reach `IsLast` within the budget is not
read, and the target is lifted on it unconditionally.

Worst case, a run spends its whole budget and then lifts blind anyway — bounded
at roughly twice the cheaper option, never the account-sized walk.

Pending is `targets.size - cursor.index` on Android, so a resumed run budgets for
what is left. The extension's bulk path passes `matchingUsers.length -
resumeIndex` for the same reason.

Alternatives rejected: always lift blind (triples a large run's action count —
a 1,000-follower run goes from ~1.4 h to ~4.3 h of rate limit); per-target state
(not exposed for mute — see proposal non-goals).

### `Restrictions` / `FollowRestrictions`: a null list answers yes

`Tasks.kt:331` and `scrapingHandler.js:55`. Keeps the loop unchanged in shape: it
asks `isBlocked(nick)` / `isMuted(nick)` and acts, whether the answer came from a
list or from not having one.

### The lookup is not collection

Android publishes the run's progress (`0 / N`) before the lookup
(`Tasks.kt:94`) and the walk calls `ensureActive()` and `awaitReadPermit()` per
page but not `publishCollecting`. Publishing progress clears the collection line
(`OperationWorker.kt:472-475`). The extension shows a status line, "Engel ve
sessize alma durumu kontrol ediliyor...", and stops cleanly through
`finishStoppedWhileCollecting` if the user stops during it
(`background.js:773`).

### Extension: follow runs skip restricting-run analysis

`runsRestrictionAnalysis = !isFollowTarget` (`background.js:749`) gates protect
followed users, only-required-actions and the date filter on FAV and FOLLOW.
The first protects accounts from a block; the second reads the blocked lists for
flags only the restricting branch of `performOnScrapedUser` reads; the third
decides whom to block, and Android's `activeDateRules` already skips anything
that does not add a restriction (`android-operations` spec: "Following SHALL NOT
count as a restriction").

## Defects carried forward

- **Android fails the run on a failed lookup page; the extension lifts blind.**
  `ScrapeClient.relationPage` throws on a non-2xx or HTML response, and the
  lookup does not catch it, so a network error there fails the run before any
  follow — the same as a failed page during target collection. Kept because
  catching it would also have to tell `SessionExpiredException` (which must park
  the run) apart from transport errors, and a follow run that cannot read one
  page is unlikely to complete its follows either. The extension could not keep
  its old behaviour: the function it used returned a partial list on error,
  which left blocks in place silently.
- **Unfollow and the explicit "… ve takip et" actions are unchanged.** Unfollow
  never lifts; the explicit pair actions undo the one relation the user named,
  unconditionally, with no lookup.

## Risks

- If Ekşi ever starts rejecting no-op removals, a blind lift would fail. The
  failure is uncounted and the follow behind it still runs and reports itself,
  so the run degrades to the pre-lifting behaviour rather than stopping.
