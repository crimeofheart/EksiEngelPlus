# Android feasibility spike — report

**Status: IN PROGRESS.** S5 answered. S1 answered except the interactive login.
S2 answered for public pages. S3 proven at the plumbing level. S4 outstanding.

Everything still open requires a human-completed login, because Cloudflare
Turnstile guards `/giris`. A spike APK exists for that purpose.

Change: `openspec/changes/android-spike/`.

---

## S2 — Does the mobile user agent break the selectors?

**Partially answered: no, for every selector reachable while logged out.**

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

Not yet established, and it remains the go/no-go: whether an **authenticated**
session survives the handoff, and whether `POST /userrelation/*` succeeds through
it. That needs a login, which needs Turnstile, which needs a human.

The mutation round trip is implemented in the harness behind an explicit target
field and performs block → verify → immediate unblock.

---

## S4 — Is `Retry-After` returned on 429, and what is the real limit?

**Not answered.** Requires authenticated mutations. The 12/min figure currently
lives only in a user-facing string (`notificationHandler.js:60`) and has never
been verified.

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
