# android-http-stack

Binds: Android only. Implements `eksisozluk-client-contract` over OkHttp.

## ADDED Requirements

### Requirement: Requests are authenticated from the WebView cookie jar

The client SHALL source cookies from `android.webkit.CookieManager` — the same jar
the browsing WebView populates — via an interceptor that reads
`getCookie(url)` into a `Cookie` header and writes every `Set-Cookie` back.

It SHALL NOT implement an OkHttp `CookieJar`. `getCookie()` returns a
pre-assembled header string with no domain, path or expiry attributes, so
round-tripping through `okhttp3.Cookie` is lossy.

`CookieManager.flush()` performs disk I/O and SHALL be debounced rather than
called per request.

Proven on device during `android-spike`: OkHttp carrying this jar authenticated
as the logged-in user and completed a block/unblock round trip.

#### Scenario: Authenticated request

- **WHEN** a session exists in the WebView jar and any endpoint is fetched
- **THEN** the request carries those cookies and the response reflects the logged-in user

#### Scenario: Rotated cookies are preserved

- **WHEN** a response carries `Set-Cookie`
- **THEN** the value is written back into `CookieManager` so sliding expiry is not lost

#### Scenario: WebView unavailable

- **WHEN** `CookieManager.getInstance()` throws `MissingWebViewPackageException`
- **THEN** the failure surfaces as a typed unavailable state rather than a crash

### Requirement: Every request carries the load-bearing headers

The client SHALL send `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`
and `x-requested-with: XMLHttpRequest` on every request, and SHALL send the
WebView's own user agent from `WebSettings.getDefaultUserAgent`.

Omitting `x-requested-with` makes `/relation-list` answer **HTTP 500**, so this is
a correctness requirement, not a preference. The user agent must match because a
mismatch against the session's originating browser risks rejection.

No `Origin` header SHALL be sent.

#### Scenario: Headers present

- **WHEN** `/relation-list?relationType=m&pageIndex=1` is fetched with both headers
- **THEN** the response is 200 with a JSON body

#### Scenario: Headers absent

- **WHEN** the same request omits them
- **THEN** the response is 500 with an HTML error body

### Requirement: Pagination starts at 1

All paginated endpoints SHALL be requested with `pageIndex` starting at **1**.
`pageIndex=0` is invalid and answers HTTP 500 with an empty body.

`/relation-list` terminates on `Relations.IsLast`; `/follower` and `/following`
terminate on an empty array. Observed page size is 25, which MAY inform progress
estimation but SHALL NOT be used as a termination signal.

#### Scenario: First page

- **WHEN** a list scrape begins
- **THEN** the first request uses `pageIndex=1`

#### Scenario: Termination differs by endpoint

- **WHEN** paginating `/relation-list`
- **THEN** iteration stops on `IsLast`, whereas `/follower` and `/following` stop on an empty array

### Requirement: Mutation results are typed, not numeric

`RelationClient` SHALL return a sealed result rather than a raw response, with at
minimum: success, already-in-that-state, rate-limited carrying the retry delay,
session-expired, and failure carrying the observed code.

BAN responses are a bare JSON number: `0` and `2` are success, `4` is returned
when the target is the authenticated user themselves, and any other value SHALL
be a failure of unknown meaning rather than a guess. UNDOBAN responses are an
object with `result`; unknown fields such as `count` SHALL be ignored, and
`result: true` alone SHALL NOT be taken as proof a relation existed.

#### Scenario: Block succeeds

- **WHEN** `addrelation` returns `0`
- **THEN** the result is success

#### Scenario: Self-target

- **WHEN** `addrelation` returns `4`
- **THEN** the result is a distinct self-target failure, not a generic one

#### Scenario: Unknown code

- **WHEN** a BAN response is a number outside `{0, 2, 4}`
- **THEN** the result is a failure recording that code

### Requirement: Rate limiting is surfaced, not absorbed

On HTTP 429 the client SHALL parse `Retry-After` as integer seconds, add a one
second buffer, and default to 65 seconds when absent or unparseable. The HTTP-date
form SHALL NOT be parsed. The delay SHALL be returned to the caller rather than
slept on inside the client, so a future pacer can apply it globally.

#### Scenario: Retry-After present

- **WHEN** the server responds 429 with `Retry-After: 30`
- **THEN** the result carries a 31 second delay

#### Scenario: Retry-After absent

- **WHEN** the server responds 429 with no such header
- **THEN** the result carries a 65 second delay

### Requirement: Session expiry is detected, never silently retried

A shared `SessionExpiry` classifier SHALL treat as session-expired: a redirect to
a path containing `giris`, a 401 or 403, and an HTML body where JSON was expected.

It is a classifier applied in the client layer, **not** an OkHttp interceptor.
Deciding on the body requires reading it, and an OkHttp response body can be
consumed only once — an interceptor that peeked would have to buffer every
response to hand an intact copy downstream, paying that cost on every request to
serve an uncommon case. The clients already hold the body at the point they
classify, so the check is free there. The rule itself is unchanged; only its
placement is.

Because `/giris` is protected by Cloudflare Turnstile, a session cannot be renewed
headlessly. The client SHALL NOT retry, re-authenticate, or prompt for
credentials; it SHALL surface the expiry so the caller can route the user back
into the WebView.

#### Scenario: Redirect to login

- **WHEN** a request 302s to a path containing `giris`
- **THEN** the result is session-expired and no retry occurs

#### Scenario: HTML where JSON was expected

- **WHEN** a JSON endpoint returns an HTML body
- **THEN** the result is session-expired rather than a parse error

### Requirement: The base URL is resolved and validated

The client SHALL treat the base URL as configurable, falling back to
`GET https://eksiengelplus.duzgun.org/api/where_is_eksisozluk` when the configured
base is unreachable. A replacement SHALL be accepted only if it parses, is HTTPS,
and is a bare origin.

Unreachability SHALL mean a non-200 or a redirect to a **different registrable
domain**. The extension treats any redirect as failure (`urlHandler.js:21`), which
produces false negatives behind captive portals; the Android client SHALL NOT
copy that.

The resolver SHALL accept either a plain-text origin or `{"url": ...}` JSON, and a
manual override SHALL be available as an escape hatch.

#### Scenario: Base reachable

- **WHEN** the configured base returns 200 with no cross-domain redirect
- **THEN** it is used unchanged and no resolver request is made

#### Scenario: Same-domain redirect

- **WHEN** the base redirects within the same registrable domain
- **THEN** it is still considered reachable

#### Scenario: Replacement rejected

- **WHEN** the resolver returns a non-HTTPS value or something that is not a bare origin
- **THEN** it is rejected and the existing base is retained
