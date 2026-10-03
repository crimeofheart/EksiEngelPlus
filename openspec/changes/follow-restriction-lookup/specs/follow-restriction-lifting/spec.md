## ADDED Requirements

### Requirement: A follow lifts a block or mute first

Ekşi holds block, mute and follow as independent relations, so a follow alone
leaves an existing block or mute in place and the account stays hidden. Before
adding the follow (`POST /userrelation/addrelation/{id}?r=b`), a client SHALL
remove the block (`POST /userrelation/removerelation/{id}?r=m`) and the mute
(`POST /userrelation/removerelation/{id}?r=u`) on that target when either is, or
may be, in place (see the lookup requirement below).

These removals are bookkeeping for the follow. They SHALL NOT be counted in the
run's processed, successful or failed totals, and a failed removal SHALL NOT fail
the target — the follow behind it reports the outcome.

This applies only to adding a follow. Unfollowing (`removerelation ... ?r=b`,
`TAKIPTEN_CIKAR`) SHALL NOT lift anything, and the explicit pair actions
"engeli kaldır ve takip et" / "sessizden çıkar ve takip et" keep their own
behaviour: they undo the one relation they name, unconditionally.

Binds both clients. Android: `TargetRunner.applyToAll` in
`android/ops/engine/.../Tasks.kt`. Extension: `followAfterClearing` in
`background.js` and `_followAfterClearing` in `programController.js`.

#### Scenario: Following a blocked account

- **WHEN** a follow run reaches a target that is on the blocked list
- **THEN** the client sends `removerelation/{id}?r=m`, then
  `addrelation/{id}?r=b`, and the run counts one processed, successful target

#### Scenario: Unfollowing a blocked account

- **WHEN** an unfollow run reaches a target that is on the blocked list
- **THEN** only `removerelation/{id}?r=b` is sent and the block stays

### Requirement: The restriction lookup is budgeted by the run's size

A client SHALL decide which targets need lifting from the account's relation
lists, read through `GET /relation-list?relationType={m|u}&pageIndex={n}` with
`n` starting at 1 (`pageIndex=0` answers 500). Pages hold 25 items under
`Relations.Items[].{Id, Nick.Value}` and the list ends on `Relations.IsLast ==
true`. The title-block list (`relationType=i`) SHALL NOT be read for a follow.

The lookup SHALL happen after the run's targets are known, and SHALL read at most
`10 × pending` pages in total, where `pending` is the number of targets the run
has yet to process. The blocked list (`m`) is read first; the muted list (`u`)
gets whatever budget remains. A list that has not reached `IsLast` when the
budget runs out SHALL be treated as unread, and every target SHALL be lifted on
that relation without consulting it. A removal of a relation that does not exist
is accepted (`{"result": true}`, measured during android-spike), so an unread
list costs requests, not correctness.

A nick on a list matches a target by slug: spaces to `-`, lower case.

Binds both clients. Android: `TargetRunner.LOOKUP_PAGES_PER_TARGET` and
`restrictionsToLift`. Extension: `FOLLOW_LOOKUP_PAGES_PER_TARGET` and
`ScrapingHandler.scrapeFollowRestrictions`, mirrored by name in comments.

#### Scenario: One follow on an account with thousands of blocks

- **WHEN** "yazarı takip et" runs and the blocked list is longer than 10 pages
- **THEN** exactly 10 relation-list pages are read, then
  `removerelation/{id}?r=m`, `removerelation/{id}?r=u` and `addrelation/{id}?r=b`
  are sent

#### Scenario: Lists shorter than the budget

- **WHEN** a follow run's blocked and muted lists both end within the budget
- **THEN** both are read to `IsLast`, and only targets found on them are lifted,
  each on the relation it was found on

#### Scenario: The muted list inherits what the blocked list left

- **WHEN** the blocked list ends on the last page the budget allows
- **THEN** the blocked list is used exactly and the muted list is unread, so every
  target is lifted on mute

#### Scenario: A resumed run

- **WHEN** a follow run resumes at index `k` of `N` targets
- **THEN** the lookup budget is `10 × (N − k)` pages

#### Scenario: A failed lookup page in the extension

- **WHEN** the extension's lookup fails to read a relation-list page
- **THEN** that list is treated as unread rather than as the items read so far

### Requirement: The lookup is not target collection

The lookup reads the user's own lists, not the run's audience, and SHALL NOT be
presented as targets being found.

Android SHALL publish the run's total (`0 / N`) before the lookup starts and
SHALL NOT call `publishCollecting` during it. Each lookup page SHALL still be
preceded by `ensureActive()` and a read permit, so Duraklat and Durdur reach a
run during the lookup and pages stay paced.

The extension SHALL show a status line saying restriction state is being checked
while the lookup runs, and SHALL finish through its stopped-while-collecting path
if "erken durdur" is pressed during it.

Binds both clients, as described per client above.

#### Scenario: Android screen during the lookup

- **WHEN** a follow run of 3 targets is in its lookup on an account with 5,000
  blocks
- **THEN** the operations screen shows `0 / 3`, and no collection count

#### Scenario: Stopping during the lookup

- **WHEN** the user stops a follow run while its lookup is reading pages
- **THEN** no further relation-list page is requested and no follow is sent

### Requirement: Follow runs skip restricting-run analysis

A follow run SHALL NOT apply analysis that exists to decide or protect against a
restriction:

- protecting followed users (`enableProtectFollowedUsers`), which walks the
  user's whole `/following` list;
- only-required-actions (`enableOnlyRequiredActions`) on FAV and FOLLOW, which
  walks the relation lists for flags only a restricting run reads;
- the date filter (`enableDateFilter` / `dateFilterRules`), which selects
  accounts to restrict.

Binds the extension (`runsRestrictionAnalysis` in `background.js`). Android
already conforms: its engine applies no protect-followed or only-required step,
and `activeDateRules` returns no rules for a request that does not add a
restriction (`android-operations`).

#### Scenario: Following an entry's favers with the date filter on

- **WHEN** "favlayanları takip et" runs with a date filter rule enabled
- **THEN** every faver is followed, not only those the rule covers, and no
  registration dates are fetched

#### Scenario: Following an author's followers with protection on

- **WHEN** "takipçilerini takip et" runs with `enableProtectFollowedUsers` and
  `enableOnlyRequiredActions` on
- **THEN** neither the user's `/following` list nor the relation lists are walked
  for analysis; only the budgeted restriction lookup reads relation pages
