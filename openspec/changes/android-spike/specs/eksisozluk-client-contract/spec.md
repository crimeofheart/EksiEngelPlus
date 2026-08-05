# eksisozluk-client-contract

The Ekşi Sözlük HTTP surface. Binds **both** clients: the shipped extension
(`frontend/app/assets/js/`, JSDOM/DOMParser) and the planned Android app (Jsoup).

This surface is undocumented, unversioned, and controlled by a third party. Every
requirement here is an observation that can be invalidated without notice, which is
why the spike captures evidence rather than transcribing the extension source.

## ADDED Requirements

### Requirement: Every request carries the load-bearing headers

Both clients SHALL send exactly these headers on every request to Ekşi Sözlük:

```
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
x-requested-with: XMLHttpRequest
```

`x-requested-with: XMLHttpRequest` is what makes the ASP.NET backend return JSON or
partial-HTML fragments instead of full pages. Removing it changes the response body
shape for `/relation-list`, `/follower`, `/following`, and `/entry/favorileyenler`.

Reference: `frontend/app/assets/js/relationHandler.js:142-145`.

Neither client SHALL send an `Origin` header.

#### Scenario: Header present

- **WHEN** `GET /relation-list?relationType=m&pageIndex=0` is sent with `x-requested-with: XMLHttpRequest`
- **THEN** the response body is JSON matching the relation-list shape

#### Scenario: Header absent

- **WHEN** the same request omits `x-requested-with`
- **THEN** the response is a full HTML page and the JSON parse fails

### Requirement: Authentication is ambient session cookies only

Neither client SHALL implement a login flow, token exchange, CSRF token scrape, or
`Authorization` header. Requests SHALL carry the session cookies the user's browser
or WebView already holds.

The extension relies on the browser cookie jar implicitly. The Android client SHALL
source cookies from `android.webkit.CookieManager`, the same jar the browsing WebView
populates, and SHALL propagate `Set-Cookie` from responses back into that jar so
sliding-expiration renewals are not lost.

#### Scenario: Logged-in request succeeds

- **WHEN** a request is made while a valid session cookie is present in the jar
- **THEN** the response reflects the logged-in user

#### Scenario: Logged-out request

- **WHEN** no session cookie is present
- **THEN** the homepage response contains no nick element and the client treats the session as absent

### Requirement: Login state is determined by the homepage avatar

Both clients SHALL determine login state by fetching `GET /` and reading the `title`
attribute of `.mobile-notification-icons .mobile-only a`. Its presence means logged
in and its value is the current user's nick.

Reference: `frontend/app/assets/js/scrapingHandler.js:73-105`.

#### Scenario: Nick is scraped

- **WHEN** `GET /` is fetched with a valid session
- **THEN** `.mobile-notification-icons .mobile-only a[title]` yields the user's nick

#### Scenario: Selector must be verified under a mobile user agent

- **WHEN** `GET /` is fetched with an Android WebView user agent
- **THEN** the spike SHALL record whether that selector still matches, since the element name suggests a mobile-specific variant may already differ

### Requirement: A single write endpoint performs every mutation

All block, unblock, mute, unmute, title-block, and follow actions SHALL be performed by:

```
POST {base}/userrelation/{addrelation|removerelation}/{id}?r={m|i|u|b}
body: id={id}
```

`addrelation` for BAN, `removerelation` for UNDOBAN. The `r` parameter selects the
relation: `m` user block, `i` title block, `u` mute, `b` follow.

Reference: `frontend/app/assets/js/relationHandler.js:107-127`.

#### Scenario: Block succeeds

- **WHEN** `POST /userrelation/addrelation/{id}?r=m` is sent with body `id={id}`
- **THEN** the response body is a bare JSON number, where `0` and `2` both indicate success (`2` meaning the relation already existed)

#### Scenario: Unblock succeeds

- **WHEN** `POST /userrelation/removerelation/{id}?r=m` is sent
- **THEN** the response body is a JSON object whose `result` field is `true`

#### Scenario: The response shape is polymorphic

- **WHEN** a client parses a mutation response
- **THEN** it SHALL branch on request mode, because BAN returns a bare number and UNDOBAN returns an object — a single parse target cannot cover both

### Requirement: Rate limiting is signalled by 429 with Retry-After

The server SHALL be assumed to limit mutations to approximately 12 per minute. On
exceeding it the server returns HTTP 429.

Clients SHALL read the `Retry-After` response header as integer seconds, add a one
second buffer, and default to 65 seconds when the header is absent or unparseable.
Clients SHALL NOT attempt to parse the HTTP-date form of `Retry-After`.

Reference: `frontend/app/assets/js/relationHandler.js:151-169`.

Clients SHALL pace **below** the observed limit rather than relying on absorbing 429s.

#### Scenario: Rate limit encountered

- **WHEN** the server responds 429
- **THEN** the client waits `Retry-After + 1` seconds, or 65 seconds if the header is missing, before any further mutation

#### Scenario: The real limit is measured, not assumed

- **WHEN** the spike drives mutations until a 429 is returned
- **THEN** it SHALL record the actual observed threshold and whether `Retry-After` was present, because 12/min is currently only a user-facing string (`frontend/app/assets/js/notificationHandler.js:60`) and not a verified figure

### Requirement: JSON list endpoints and their shapes

Three endpoints return JSON. Both clients SHALL model them as follows.

`GET /relation-list?relationType={m|i|u}&pageIndex={n}` — the blocked, title-blocked,
and muted lists:

```json
{"Relations": {"IsLast": false, "Items": [{"Id": 123, "Nick": {"Value": "nick"}}]}}
```

`GET /follower?nick={nick}&pageIndex={n}` and `GET /following?nick={nick}&pageIndex={n}`
— a bare array:

```json
[{"Id": 123, "Nick": {"Value": "nick"}, "IsFollowCurrentUser": true, "IsBuddy": false}]
```

Pagination for `/relation-list` terminates on `Relations.IsLast`; for `/follower` and
`/following` it terminates on an empty page.

Reference: `frontend/app/assets/js/scrapingHandler.js:225-277`, `:777-812`, `:827-862`.

#### Scenario: Relation list pagination

- **WHEN** pages are fetched with increasing `pageIndex`
- **THEN** iteration stops when `Relations.IsLast` is `true`

#### Scenario: Follower pagination

- **WHEN** pages are fetched with increasing `pageIndex`
- **THEN** iteration stops when the returned array is empty, since no `IsLast` field exists

### Requirement: HTML scrape targets

Both clients SHALL extract the following, and the selectors SHALL be recorded in one
place per client so a site change is a single-file diff.

| Endpoint | Extracted | Selector |
| --- | --- | --- |
| `GET /` | own nick | `.mobile-notification-icons .mobile-only a[title]` |
| `GET /biri/{nick}` | author id | `#who` attribute `value` |
| `GET /biri/{nick}` | registration date | `.recorddate`, then six documented fallbacks, then a bounded text scan for `kayıt tarihi` / `katılım tarihi` |
| `GET /entry/{id}` | author + title metadata | `#entry-item-list li` attributes `data-author-id`, `data-author`; `#title` attributes `data-id`, `data-title` |
| `GET /entry/favorileyenler?entryId={id}` | favouriter nicks | every `<a>`, leading `@` stripped |
| `GET /entry/caylakfavorites?entryId={id}` | novice favouriter nicks | same |
| `GET /{slug}--{id}?p={n}` | thread participants | `.content`, then parent attributes `data-author`, `data-author-id` |
| `GET /{slug}--{id}?a=dailynice&p={n}` | last-24h participants | same |

Reference: `frontend/app/assets/js/scrapingHandler.js`.

#### Scenario: Every selector is verified against captured evidence

- **WHEN** the spike captures each endpoint under desktop Chrome, Android Chrome, and Android WebView user agents
- **THEN** each selector's match count is recorded per user agent, and any selector matching under one and not another is flagged as a divergence

#### Scenario: Divergence forces a decision

- **WHEN** any selector yields different results under a mobile user agent
- **THEN** the spike report SHALL state whether the Android client pins a desktop user agent or maintains a separate mobile selector set

### Requirement: Nicks are slug-normalized identically by both clients

Both clients SHALL normalize a nick by trimming it and replacing every space with a
hyphen, before using it in any URL or as a map key.

The extension performs this inline at each call site throughout
`frontend/app/assets/js/scrapingHandler.js`. Any reimplementation SHALL centralize it
in one function.

#### Scenario: Nick with spaces

- **WHEN** the nick `ssg` is normalized
- **THEN** it is unchanged

#### Scenario: Multi-word nick

- **WHEN** the nick `bir iki uc` is normalized
- **THEN** the result is `bir-iki-uc`

### Requirement: The base URL is resolved, not hardcoded

Ekşi Sözlük is periodically blocked in Turkey, so both clients SHALL treat the base
URL as resolvable. When the configured base is unreachable, the client SHALL request
a replacement from `GET https://eksiengelplus.duzgun.org/api/where_is_eksisozluk`,
validate it, and persist it.

Reference: `frontend/app/assets/js/urlHandler.js:32-79`.

The extension's reachability check treats *any* redirect as failure
(`urlHandler.js:21`), which produces false negatives behind captive portals and on
some mobile networks. A reimplementation SHALL instead treat only a redirect to a
different registrable domain as failure.

A second endpoint, `GET /where_is_eksisozluk/`, exists and returns a different domain.
Clients SHALL use only the `/api/` path.

#### Scenario: Base is reachable

- **WHEN** the configured base returns 200 without cross-domain redirect
- **THEN** it is used unchanged and no resolution request is made

#### Scenario: Base is unreachable

- **WHEN** the configured base fails
- **THEN** the resolver endpoint is queried and the returned origin is validated as HTTPS and a bare origin before being persisted
