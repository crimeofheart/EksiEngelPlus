## ADDED Requirements

### Requirement: Follow a single author from the entry menu
The extension's entry dropdown menu (injected by `processEntryMenu` on
`/entry/*` and `/sorunsal/*` pages) SHALL offer a "yazarı takip et" action
alongside the existing "yazarı engelle"/"yazarı sessize al" action. Selecting it
SHALL dispatch a `BanSource.SINGLE` action with `targetType ==
enums.TargetType.FOLLOW` for the entry's author, using the same
`EksiEngel_sendMessage` dispatch path as the existing block/mute button. This
requirement binds the extension client only (Chrome and Firefox); it does not
apply to Android, which has no equivalent entry menu.

#### Scenario: Follow the author of an entry
- **WHEN** the user opens an entry's dropdown menu and clicks "yazarı takip et"
- **THEN** the extension sends a `BanSource.SINGLE`, `BanMode.BAN`,
  `TargetType.FOLLOW` action for that entry's author, and `processHandler`
  performs the follow relation via `relationHandler.performAction` with
  `isTargetFollow: true`

### Requirement: Follow a single author from the profile page
The extension SHALL offer a "takip et" action on a user's own profile page
(`/biri/{user}`, injected by `processRelationButtons`) alongside the existing
block/mute button for that profile's author. This requirement binds the
extension client only.

#### Scenario: Follow the author from their own profile page
- **WHEN** the user is on an author's profile page and clicks "takip et"
- **THEN** the extension sends a `BanSource.SINGLE`, `BanMode.BAN`,
  `TargetType.FOLLOW` action for that profile's author

### Requirement: Follow an entry's favers
The entry dropdown menu SHALL offer a "favlayanları takip et" action alongside
the existing "favlayanları engelle" action. Selecting it SHALL dispatch a
`BanSource.FAV` action with `targetType == enums.TargetType.FOLLOW`, causing
`processHandler`'s FAV branch to follow every scraped favering user instead of
blocking or muting them. This requirement binds the extension client only.

#### Scenario: Follow all favers of an entry
- **WHEN** the user opens an entry's dropdown menu and clicks "favlayanları
  takip et"
- **THEN** the extension scrapes the entry's favers and issues a follow
  relation (`isTargetFollow: true`, `isTargetUser`/`isTargetTitle`/`isTargetMute:
  false`) for each one, regardless of the `config.enableMute` setting

### Requirement: Follow an author's followers
Both the entry dropdown menu and the profile page SHALL offer a "takipçilerini
takip et" action alongside their existing "takipçilerini engelle"/"takipçilerini
sessize al" action. Selecting it SHALL dispatch a `BanSource.FOLLOW` action with
`targetType == enums.TargetType.FOLLOW`, causing `processHandler`'s FOLLOW
(followers) branch to follow every scraped follower instead of blocking or
muting them. This requirement binds the extension client only.

#### Scenario: Follow all followers of an author, from the entry menu
- **WHEN** the user opens an entry's dropdown menu and clicks "takipçilerini
  takip et"
- **THEN** the extension scrapes the entry author's followers (`scrapeFollower`)
  and issues a follow relation for each one

#### Scenario: Follow all followers of an author, from the profile page
- **WHEN** the user is on an author's profile page and clicks "takipçilerini
  takip et"
- **THEN** the same follow-all-followers behavior occurs for that profile's
  author

### Requirement: Follow an author's followees
Both the entry dropdown menu and the profile page SHALL offer a "takip
ettiklerini takip et" action, dispatching a new `BanSource.FOLLOWEES` action.
`processHandler` SHALL handle this `BanSource` by scraping the author's
followees via `scrapingHandler.scrapeFollowing` and issuing a follow relation
(`isTargetFollow: true`) for each one. This action is follow-only — no
block/mute equivalent is offered for this audience, since none previously
existed. This requirement binds the extension client only.

#### Scenario: Follow everyone an author follows, from the entry menu
- **WHEN** the user opens an entry's dropdown menu and clicks "takip ettiklerini
  takip et"
- **THEN** the extension scrapes the entry author's followees via
  `scrapeFollowing` and issues a follow relation for each one

#### Scenario: Follow everyone an author follows, from the profile page
- **WHEN** the user is on an author's profile page and clicks "takip ettiklerini
  takip et"
- **THEN** the same follow-all-followees behavior occurs for that profile's
  author

#### Scenario: Backend accepts the new BanSource
- **WHEN** the extension POSTs an `Action` with `ban_source` set to the
  `FOLLOWEES` pk
- **THEN** the backend accepts it without an FK validation error, because the
  `BanSource` lookup table (in both the `api` and `client_data_collector` apps)
  has been seeded with a matching row via data migration

### Requirement: SINGLE dispatch correctly forwards a follow target
`processHandler`'s `BanSource.SINGLE` branch SHALL pass `targetType ==
enums.TargetType.FOLLOW` as the `isTargetFollow` argument to `performWithRetry`,
alongside its existing USER/TITLE/MUTE checks. Prior to this change this branch
only checked USER/TITLE/MUTE, so a FOLLOW-targeted SINGLE dispatch would perform
no relation at all. This requirement binds the extension client only.

#### Scenario: A FOLLOW-targeted SINGLE action performs the follow relation
- **WHEN** `processHandler` receives a `BanSource.SINGLE` message with
  `targetType === enums.TargetType.FOLLOW`
- **THEN** `performWithRetry` is called with `isTargetFollow: true`, and
  `relationHandler.performAction` issues the follow HTTP request
