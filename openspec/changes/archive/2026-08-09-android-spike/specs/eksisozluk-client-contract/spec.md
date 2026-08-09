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
- **THEN** the server answers **HTTP 500** carrying an HTML error page (~1.2 KB), not a JSON body

Measured on device: `/relation-list?relationType=m&pageIndex=1` returns 200 with
the two headers set and 500 without them. The header is not merely selecting a
rendering mode — the endpoint is unusable without it.

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

### Requirement: Session establishment is interactive and cannot be automated

The login form at `POST /giris` is protected by **Cloudflare Turnstile**, embedded
directly in the form as `<div class="cf-turnstile" data-sitekey="0x4AAAAAAA53GWVB-tieg9RN">`
alongside `https://challenges.cloudflare.com/turnstile/v0/api.js`. A submission
carrying a valid `__RequestVerificationToken`, correct credentials, and the
matching session cookies but no Turnstile token is rejected with the field error
`doğrulama başarısız` and no session is issued.

Clients SHALL therefore obtain a session only by having the user log in inside a
real browser context that executes JavaScript — the extension's host browser, or
the Android app's WebView. Clients SHALL NOT attempt to script, bypass, or solve
the challenge, and SHALL NOT prompt the user for their Ekşi credentials.

This is why the Android architecture logs in inside the WebView rather than
offering a native login form: a native form cannot satisfy Turnstile.

**Consequence for expiry handling.** Because a session cannot be renewed
headlessly, a client that loses its session mid-operation SHALL NOT retry or
attempt silent re-authentication. It SHALL checkpoint, pause in a distinct
authentication-required state, and surface an action that returns the user to the
in-browser login. Resumption SHALL be driven by observing that a session exists
again, never by re-submitting credentials.

#### Scenario: Scripted login is rejected

- **WHEN** a non-browser HTTP client posts valid credentials and a valid `__RequestVerificationToken` to `/giris` without a Turnstile token
- **THEN** the response is HTTP 200 rendering the login page again with `doğrulama başarısız`, and no authentication cookie is issued

#### Scenario: Interactive login succeeds

- **WHEN** the user completes the login form inside the WebView, solving Turnstile
- **THEN** the session cookies land in the WebView's `CookieManager` jar and the client detects the session via the homepage avatar

#### Scenario: Session lost mid-operation

- **WHEN** an operation's request indicates the session is gone
- **THEN** the operation checkpoints and pauses awaiting authentication, and the user is offered a route into the WebView login rather than any credential prompt

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

#### Scenario: Blocking oneself returns an undocumented code

- **WHEN** `addrelation` is sent with an `id` equal to the authenticated user's own id
- **THEN** the response is HTTP 200 with body `4`, and no relation is created
- **AND** a subsequent `removerelation` returns `{"result":true,"count":0}` — `result:true` despite nothing having been removed, so `result` alone does not prove a relation existed

#### Scenario: An unrecognised numeric code is not success

- **WHEN** a BAN response is a bare number outside `{0, 2}`
- **THEN** the client SHALL treat it as a failure and record the code, because the meaning is unknown. `relationHandler.js:185` already does this, so `4` is currently a hard failure in the shipped extension.

#### Scenario: Unblock succeeds

- **WHEN** `POST /userrelation/removerelation/{id}?r=m` is sent
- **THEN** the response body is a JSON object whose `result` field is `true`
- **AND** the object also carries an undocumented `count` field, observed as `{"result":true,"count":0}`. Clients SHALL ignore unknown fields rather than failing to parse.

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

**Pagination is 1-indexed.** `pageIndex=0` is invalid and the server answers
**HTTP 500 with an empty body**; `pageIndex=1` and omitting the parameter both
return 200. Clients SHALL start at 1.

The extension already does this correctly — every loop is
`let index = 0; while (!isLast) { index++; fetch(index) }`
(`scrapingHandler.js:355-358, 817-821, 490-499, 643-647, 943-954`), the resume
paths increment before their first call, and `scrapeBlockedTitlesFirstPage`
defaults `pageNumber = 1`. This requirement exists so a reimplementation does not
regress it, since the failure is a 500 rather than an empty page and is easy to
misread as a broken endpoint.

Pagination for `/relation-list` terminates on `Relations.IsLast`; for `/follower`
and `/following` it terminates on an empty page.

Reference: `frontend/app/assets/js/scrapingHandler.js:225-277`, `:777-812`, `:827-862`.

#### Scenario: Populated relation list

- **WHEN** `/relation-list?relationType=u&pageIndex=1` is fetched for an account with muted users
- **THEN** the response is 200 with `IsLast=false` and **25** items, each carrying `Id` and `Nick.Value`

Page size is 25. A client SHALL NOT hardcode it as a termination signal — pagination
still ends on `IsLast` — but it is the basis for progress estimation.

#### Scenario: Envelope shape is stable when the list is empty

- **WHEN** `/relation-list?relationType={m|i|u}&pageIndex=1` is fetched for an account with no relations
- **THEN** the response is 200 with `{"Relations":{"IsLast":true,"Items":[]}}` — the envelope is present and parseable rather than null or an error

#### Scenario: Relation list pagination

- **WHEN** pages are fetched with increasing `pageIndex`
- **THEN** iteration stops when `Relations.IsLast` is `true`

#### Scenario: Follower pagination

- **WHEN** pages are fetched with increasing `pageIndex`
- **THEN** iteration stops when the returned array is empty, since no `IsLast` field exists

#### Scenario: Populated follow list

- **WHEN** `/follower?nick={nick}&pageIndex=1` is fetched for an account with followers
- **THEN** each element carries `Id`, `Nick.Value`, `IsBuddy` and `IsFollowCurrentUser`, all populated

Verified on device: `follower` returned 38 items and `following` returned exactly
100, with a first element of `{"Id":7556,"Nick":{"Value":"guru"},"IsBuddy":true,"IsFollowCurrentUser":true}`.

#### Scenario: Page size differs by endpoint family

- **WHEN** `/relation-list` and `/follower`/`/following` are both paginated
- **THEN** `/relation-list` returns at most **25** per page while the follow endpoints return at most **100**

A client SHALL NOT assume one page size across endpoints, and SHALL NOT use either
as a termination signal — `/relation-list` ends on `IsLast`, the follow endpoints
on an empty array. The exact 100 strongly suggests a server-side cap rather than
a coincidence.

#### Scenario: Page index zero is rejected

- **WHEN** any of `/relation-list`, `/follower`, or `/following` is requested with `pageIndex=0`
- **THEN** the server responds HTTP 500 with an empty body, and the client must not treat this as "list unavailable"

#### Scenario: Error responses vary with the AJAX header

- **WHEN** a request that would 500 is sent *without* `x-requested-with: XMLHttpRequest`
- **THEN** the 500 carries a full HTML error page instead of an empty body, further confirming the header selects the response rendering path

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

#### Scenario: Injection targets exist on a logged-in title page

- **WHEN** a title page is fetched with a session
- **THEN** `#in-topic-search-options` matches once, `#title` carries `data-id` and `data-slug`, and `.content` matches once per entry (10 on a full page)

#### Scenario: Injection targets exist on a logged-in entry page

- **WHEN** an entry page is fetched with a session
- **THEN** `.dropdown-menu` matches **4** times and `#user-notifications` once

Because four dropdowns are present, a client SHALL identify the entry menu by its
contents rather than by position — the extension text-matches
`['engelle','modlog','şikayet','mesaj']` (`script.js:315`), which is required, not
defensive.

#### Scenario: `ul.toggles-menu` never matches

- **WHEN** either a title page or an entry page is fetched with a session
- **THEN** `ul.toggles-menu` matches zero elements

It is a dead alternative in the extension's
`.dropdown-menu, ul.toggles-menu, .other .dropdown-menu` selector list. Harmless,
but a reimplementation SHALL NOT rely on it.

#### Scenario: Favouriter fragments return anchor lists

- **WHEN** `/entry/favorileyenler?entryId={id}` is fetched with a session
- **THEN** the response is 200 with a fragment of `<a>` elements whose text is a nick prefixed with `@`, for example `@ben ne diyorum sen ne diyorsun`
- **AND** `/entry/caylakfavorites?entryId={id}` returns 200 with the same shape

Nicks here contain spaces, so the `@`-strip and the space-to-hyphen slug rule are
both load-bearing on this path.

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

#### Scenario: Real multi-word nicks occur in live data

- **WHEN** a populated muted list or favouriter fragment is parsed
- **THEN** nicks such as `0 derece` and `ben ne diyorum sen ne diyorsun` appear, confirming the rule is exercised in practice rather than defensively

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
