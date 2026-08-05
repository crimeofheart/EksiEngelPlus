# Android feasibility spike — report

**Status: IN PROGRESS.** S2 partially answered. S1, S3, S4, S5 are blocked on a
device and a logged-in throwaway account, and the gate stays closed until they
are answered. No production Android code before then.

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

**Not answered.** Needs a device.

What is established: `eksisozluk.com` returns HTTP 200 with no redirect to a
plain `curl` under all three user agents, with no Cloudflare interstitial and no
unsupported-browser page. That is a necessary but not sufficient signal — it does
not exercise JS challenges, and it says nothing about completing a login inside a
WebView or about session persistence across an app restart.

---

## S3 — Does OkHttp + CookieManager cookies produce a successful mutation?

**Not answered.** Needs a device and two throwaway accounts. This is the question
that decides the architecture: if it fails, the native engine is replaced by
WebView-injected `fetch`, which is slower and far more fragile.

---

## S4 — Is `Retry-After` returned on 429, and what is the real limit?

**Not answered.** Requires authenticated mutations. The 12/min figure currently
lives only in a user-facing string (`notificationHandler.js:60`) and has never
been verified.

---

## S5 — WebView capability floor

**Not answered.** Needs a device and an API 35+ emulator to check
`DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER` support and derive the
resulting `minSdk`.

---

## Gate

**Closed.** S1 and S3 are the go/no-go pair and both are outstanding.

The S2 evidence so far is favourable — the scrape surface survives the mobile UA
unchanged, and WebView matching Android Chrome removes a whole class of expected
trouble. Nothing found so far argues against the architecture. But the
load-bearing questions are exactly the ones that need a real session.

### Next

1. Provision a throwaway actor account and a controlled target account.
2. Re-run `capture.sh` with a session cookie to close the coverage gap in
   `docs/fixtures/eksisozluk/MANIFEST.md` and resolve the five ambiguous
   selectors.
3. Build the throwaway Android project and answer S1, S3, S5 on device.
4. Answer S4 last, since it deliberately trips a server-side protection.
