# Android feasibility spike — report

**Status: GATE PASSED.** S1, S2, S3 and S5 answered. Only S4 remains, held back
by design: measuring the real rate limit means deliberately tripping a
server-side protection on a live account, so it needs explicit sign-off.

The architecture is confirmed. Production Android work can begin.

Change: `openspec/changes/android-spike/`.

---

## S2 — Does the mobile user agent break the selectors?

**ANSWERED: no. Every contract selector resolves, logged out and logged in.**

The logged-in half was closed on a device against a populated account (`coh`):

| Target | Result |
| --- | --- |
| `.mobile-notification-icons .mobile-only a[title]` | matches, yields the nick — the login check works |
| `#who` | `3656098` |
| `.recorddate` | `temmuz 2026` |
| `/relation-list` (muted) | `IsLast=false`, **25** items, `Id` + `Nick.Value` present |
| `/relation-list` (blocked, titles) | 200, empty for this account |
| `#in-topic-search-options` | 1 |
| `#title[data-id]`, `[data-slug]` | `1808524`, `yaran-facebook-durum-guncellemeleri` |
| `.content` | 10 per page |
| `.dropdown-menu` | **4** per page |
| `ul.toggles-menu` | **0 — dead selector** |
| `#user-notifications` | 1 |
| `/entry/favorileyenler` | 200, 24 `<a>`, `@`-prefixed nicks |
| `/entry/caylakfavorites` | 200 |

Three things worth carrying forward:

1. **Four dropdown menus per page.** The extension's text-matching heuristic
   (`script.js:315`, `['engelle','modlog','şikayet','mesaj']`) is *required* to
   identify the entry menu, not a defensive extra. Position alone cannot.
2. **`ul.toggles-menu` never matches.** A dead alternative in the extension's
   selector list — harmless, but not to be relied on.
3. **Live nicks contain spaces** — `0 derece`, `ben ne diyorum sen ne diyorsun` —
   so the `@`-strip and space-to-hyphen rules are exercised in practice.

Remaining, and minor: `/follower` and `/following` element shapes are still
unseen because both test accounts have empty lists, and
`.relation-link[data-add-caption]` plus `#button-blocked-link` need a *foreign*
profile — they never render on one's own page.

### The logged-out pass (original method)

Method: `docs/fixtures/eksisozluk/capture.sh` fetched four public pages under
three user agents with the extension's exact headers, and every selector in
`eksisozluk-client-contract` was evaluated with a real CSS engine (bs4 + lxml),
not substring matching. Corpus and full method: `docs/fixtures/eksisozluk/`.

### Finding 1 — WebView and Android Chrome are byte-identical

Every page, every selector, same md5. There is **one** mobile variant to reason
about, not two, and the WebView needs no UA override to see what Android Chrome
sees.

### Finding 2 — the HTML does differ by UA, but not where it matters

| Page | desktop | mobile |
| --- | --- | --- |
| home | 105,654 B | 82,797 B |
| title | 92,438 B | 80,000 B |
| profile | 75,313 B | 65,436 B |
| entry | 77,567 B | 63,494 B |

Mobile responses are 13–18 % smaller. Despite that, every contract selector that
resolves logged out yields **identical counts** across all three agents:

| Selector | desktop | mobile | |
| --- | --- | --- | --- |
| `.recorddate` | 1 | 1 | ok |
| `.profile-buttons` | 1 | 1 | ok |
| `[data-nick]` | 1 | 1 | ok |
| `#entry-item-list li[data-author-id]` | 1 | 1 | ok |
| `#entry-item-list li[data-author]` | 1 | 1 | ok |
| `#title` | 1 | 1 | ok |
| `.dropdown-menu` | 1 | 1 | ok |
| `#user-notifications` | 1 | 1 | ok |
| `.content` (title page) | 10 | 10 | ok |
| `#entry-item-list li[data-author-id]` (title page) | 10 | 10 | ok |

`.recorddate` returned `"ağustos 2007"` — the Turkish month-name form that
`utils.parseTurkishDate` already handles, confirming that path end to end.

### Finding 3 — one divergence, and it is outside the contract

`ul.topic-list a[href]` yields 50 on desktop and **0** on mobile. This is the
homepage topic list: navigation chrome, not a scrape target, and not referenced
by the contract. It does not affect the engine, and browsing is served by the
WebView rendering the page itself rather than by parsing it.

Worth recording anyway: it proves the mobile variant is a genuinely different
template, so "mobile is the same" must never be assumed for a selector that has
not been checked.

### Finding 4 — `#who` does not exist logged out, at all

`scrapeAuthorIdFromAuthorProfilePage` (`scrapingHandler.js:1047-1049`) reads
`#who[value]` for the author id. On a logged-out profile the string `who` appears
**zero** times in the HTML, under every UA, and the numeric author id does not
appear anywhere in the document either.

This is auth-gating, not UA divergence — the extension only ever runs logged in,
so it is consistent with the extension working today. But it means the author-id
scrape is unverifiable without a session, and it must be re-checked in the
logged-in phase before the Android parser is written against it.

### Still ambiguous

Absent under every UA while logged out, so auth-gating and removal cannot be
told apart yet:
`.mobile-notification-icons .mobile-only a[title]` (the login check and nick),
`#who`, `.relation-link[data-add-caption]`, `ul.toggles-menu`,
`#in-topic-search-options`.

Note the first of these is the login check itself, so it is the single most
important selector in the contract and is currently unverified.

---

## S1 — Does the site work in a WebView at all?

**Answered for browsing and the login surface; the login itself needs a human.**

### The site renders correctly in an Android WebView

Verified on an Android 15 emulator: `eksisozluk.com` loads and renders fully —
gündem, entry list, authors, timestamps, navigation. No Cloudflare interstitial,
no unsupported-browser page, no degraded fallback. `/giris` renders the real
login form (`e-mail adresi`, `şifre`) with the Turnstile widget below it.

The browsing half of the product therefore works as designed: a WebView pointed
at the site is a usable Ekşi Sözlük client.

### Browsing is unchallenged

`eksisozluk.com` returns HTTP 200 with no redirect to a plain `curl` under all
three user agents. No Cloudflare interstitial, no unsupported-browser page, no JS
challenge on read paths. Public content is freely fetchable by a non-browser
client.

### Login is gated by Cloudflare Turnstile

`GET /giris` renders the login form with a Turnstile widget embedded directly in
it:

```html
<div class="cf-turnstile" data-sitekey="0x4AAAAAAA53GWVB-tieg9RN">
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js">
```

It is present on first load, not as a reaction to a failed attempt.
`https://www.google.com/recaptcha/api.js?hl=tr` is also loaded on the page.

A scripted `POST /giris` carrying a freshly scraped `__RequestVerificationToken`,
correct credentials, and the matching session cookies is rejected with the field
error **`doğrulama başarısız`** ("verification failed"), returns HTTP 200
re-rendering the login page, and issues no authentication cookie — the session
cookies present before the POST are dropped from the jar.

### Why this is good news, not bad

1. **It validates the chosen architecture.** The design already logs in *inside
   the WebView*, which is a real browser and renders Turnstile normally for the
   user to solve. Turnstile is fully compatible with that.
2. **It would have killed the alternatives.** A fully-native client with its own
   login form — the option considered and rejected at planning time — cannot
   satisfy Turnstile and would have been dead on arrival. This is direct evidence
   for the WebView shell over a native client.
3. **It costs nothing at runtime.** Login is a one-time interactive event; every
   subsequent request rides the resulting cookie jar.

### What it changes

Session establishment is **interactive-only and cannot be automated or
refreshed headlessly**. This makes the `PAUSED_AUTH` state from the plan
mandatory rather than a nicety: when a session expires mid-operation the engine
cannot silently re-authenticate, so it must checkpoint and bounce the user into
the WebView. Written into `eksisozluk-client-contract` as a requirement, together
with an explicit prohibition on prompting for Ekşi credentials or attempting to
solve the challenge.

### What it blocks

Every remaining spike question. There is no way to obtain a session from this
environment, so the auth-gated captures, S3, and S4 all now require a device (or
a session cookie exported from a browser where a human solved Turnstile).

Attempting to defeat Turnstile is out of scope and will not be attempted — it is
the site's deliberate anti-automation control.

---

## S3 — Does OkHttp + CookieManager cookies produce a successful mutation?

**Plumbing proven; the authenticated half is still open.**

The spike harness implements the exact `CookieBridgeInterceptor` from the design
— reading `CookieManager.getCookie(url)` into a `Cookie` header and writing
`Set-Cookie` back — and runs it against a live site from the emulator:

```
jar cookie names: iq,ASP.NET_SessionId,app-suggestion,_ga_0SCWQ0JSDM,_ga
GET / -> 200
SELECTOR .mobile-notification-icons .mobile-only a[title] -> NO MATCH
```

Established:

- `CookieManager` captures the cookies the WebView received, `ASP.NET_SessionId`
  among them.
- OkHttp, carrying those cookies plus the WebView's own user agent and
  `x-requested-with: XMLHttpRequest`, reaches the site and gets **HTTP 200**. The
  handoff mechanism works and is not rejected as a non-browser client.
- The login selector correctly reports no match while logged out, so the check
  discriminates rather than always passing.

**ANSWERED ON A REAL DEVICE (WebView 149). S3 PASSES.**

```
SELECTOR .mobile-notification-icons .mobile-only a[title] -> coh81
>> COOKIE BRIDGE WORKS: OkHttp is authenticated as 'coh81'

actor=coh81 id=3658105 | target=coh id=3656098
POST addrelation    r=m -> 200  body: 0                        SUCCESS
POST removerelation r=m -> 200  body: {"result":true,"count":0} SUCCESS
```

An authenticated session established interactively in the WebView survives the
handoff into OkHttp, and a full block → unblock round trip against a distinct
target succeeds. **The native engine is viable and the WebView + Kotlin
architecture is confirmed.** No fallback to WebView-injected `fetch` is needed.

Also observed: `addrelation` against one's *own* id returns `4` and creates
nothing, while `removerelation` still answers `result:true, count:0`. Both are
now recorded in the contract.

---

## S4 — Is `Retry-After` returned on 429, and what is the real limit?

**Not answered**, and now the only substantive question left. The harness can
drive it, but it deliberately trips a server-side protection so it is scheduled
last. The 12/min figure lives only in a user-facing string
(`notificationHandler.js:60`) and has never been verified.

---

## Pagination is 1-indexed — and the extension already gets it right

The first device run sent `pageIndex=0` and every JSON list endpoint answered
**HTTP 500 with an empty body**, which looked like the endpoints were broken. A
header-variant probe isolated the real cause:

```
pageIndex=0   -> 500  (empty body)
pageIndex=1   -> 200
no pageIndex  -> 200
```

It was never the headers. `pageIndex=0` is simply invalid.

The shipped extension is **correct**: every loop is
`let index = 0; while (!isLast) { index++; fetch(index) }`
(`scrapingHandler.js:355-358, 817-821`), the three resume paths increment before
their first call (`490→499`, `643→647`, `943→954`), `programController.js:891`
increments before `:917`, and `scrapeBlockedTitlesFirstPage` defaults
`pageNumber = 1`. The 500 was a defect in the spike harness, not in the product.

Recorded as a contract requirement anyway, because the failure mode is a 500
rather than an empty page and a reimplementation could easily read it as a dead
endpoint.

One supporting detail: the same failing request sent *without*
`x-requested-with` returns a 1,206-byte HTML error page instead of an empty body,
further confirming the header selects the response rendering path.

---

## S5 — WebView capability floor

**ANSWERED: both features supported.**

Measured on an Android 15 (API 35) emulator running the spike harness:

```
provider: com.google.android.webview 124.0.6367.219
DOCUMENT_START_SCRIPT : true
WEB_MESSAGE_LISTENER  : true
```

Both APIs the JS-bridge design depends on are available, and on a WebView build
(124) that is already well behind current. The design can use
`addDocumentStartJavaScript` for injection and `addWebMessageListener` for the
JS→Kotlin channel, rather than falling back to `onPageFinished` and the
origin-unscoped `addJavascriptInterface`.

No `minSdk` increase is implied — `minSdk 26` stands.

---

## Gate

**Closed**, and everything still open now requires a device or a
human-established session. Nothing further can be answered from a headless
environment.

Evidence so far is favourable on every count:

- The scrape surface survives the mobile user agent unchanged.
- WebView and Android Chrome are byte-identical, removing an expected class of
  trouble.
- Read paths are unchallenged for non-browser clients, which is the precondition
  for the native engine.
- Turnstile on login is compatible with the WebView shell and would have killed
  a native-client design.

Nothing found argues against the architecture. The one genuinely load-bearing
question — whether an established session can be *used* by a non-browser client
for `POST /userrelation/*` — remains open, and it is the go/no-go.

### Next, in order

1. Export a session cookie from a browser where a human has solved Turnstile,
   **or** move to the device phase. Either unblocks the auth-gated captures.
2. Re-run `capture.sh` with that session to close the coverage gap in
   `docs/fixtures/eksisozluk/MANIFEST.md` and resolve the five ambiguous
   selectors, `.mobile-notification-icons .mobile-only a[title]` above all.
3. Answer S3 with a controlled target account — never a third party's.
4. Build the throwaway Android project and answer S1's WebView half and S5.
5. Answer S4 last, since it deliberately trips a server-side protection.
