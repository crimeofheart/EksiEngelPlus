## Context

The extension reaches Ekşi Sözlük with `fetch` from its background (a Chrome MV3 service worker, a
Firefox background script). Since late October 2026 Ekşi's Cloudflare answers those requests with a
non-interactive JS challenge (`cType: 'non-interactive'`, 403, `cf-mitigated: challenge`), and the
same requests with `x-requested-with: XMLHttpRequest` get the same answer. A tab in the same browser
passes: it runs the challenge script and holds the clearance. The Android app is unaffected because
its WebView makes every request from inside the page.

The request sites, all calling `fetch` directly today:

- access check: `assets/js/urlHandler.js:39` (`accessOf`), called from `assets/js/background.js:836`
- scrapes: `assets/js/scrapingHandler.js:103` (`#fetchEksiSozluk`) and the direct calls at `:159`,
  `:197`, `:240`, `:286`, `:901`, `:975`, `:1197`, `:1226`, `:1364`
- relation writes: `assets/js/relationHandler.js:159` (`POST /userrelation/{add,remove}relation/<id>`)
- account checker: `assets/js/historySync.js:136` (`eksiGet`, used by `askEksi` at `:154`)

The content script `assets/js/script.js` already runs on `*://eksisozluk.com/*` and listens for
background messages at `script.js:880`.

0.6.2 (`ef6e863`) already tells a challenge apart from an unreachable Ekşi and ends the run with a
message saying so. This change makes the run work instead.

## Goals / Non-Goals

**Goals:**
- Runs, relation writes and the account checker work while the background route is challenged, as
  long as the browser can open Ekşi in a tab.
- No behaviour change at all when nothing is challenged.
- Callers keep parsing exactly what they parse today.

**Non-Goals:**
- Getting past the challenge any other way than the user's own tab making the request from the page.
- Android, the server, pacing.

## Decisions

**D1. One routing module, `assets/js/eksiFetch.js`.** It exports `eksiFetch(url, init)` and returns a
small response object: `{ status, ok, headers: { get(name) }, text() }`, built either from a real
`Response` or from the content script's answer. Every site above calls it instead of `fetch`. A
response-shaped object keeps the call sites' `response.status`, `response.ok`,
`response.headers.get('Retry-After')` and `await response.text()` unchanged.
*Alternative:* patch the global `fetch`. Rejected: it would also reroute the sync server's requests
and hide which requests can take the tab route.

**D2. Direct first, then sticky per run.** `eksiFetch` makes the direct request. If it is a challenge
(`isCloudflareChallenge`, already in `urlHandler.js`), the module switches to tab mode and repeats the
request through a tab. Tab mode lasts until `resetRoute()`, which `processHandler` calls at the start
of each run. A run under a challenge therefore pays one wasted direct request, and a lifted challenge
is noticed at the next run.
*Alternative:* always use a tab when one is open. Rejected: it changes behaviour for everyone who is
not challenged, and ties runs to tab lifetimes for no reason.

**D3. The content script makes the request from the page.** It gets a new message,
`{ action: "eksiFetch", url, method, headers, body }`, and answers
`{ status, headers: { "retry-after", "cf-mitigated", "content-type" }, text }` via `sendResponse`
(returning `true` to keep the channel open). In Chrome a content script's `fetch` is made with the
page's origin, so a same-origin request carries the page's cookies, including the clearance, like the
page's own XHR. Firefox content scripts use `content.fetch` for the same effect, and fall back to
`fetch` where `content` is absent. Before making the request the handler checks
`sender.id === chrome.runtime.id` and `new URL(url).origin === location.origin`, and refuses
otherwise. It is not a general proxy.

**D4. Choosing and opening the tab.** `chrome.tabs.query({ url: "<base>/*" })`, preferring tabs whose
`status` is `complete` and that are not `discarded`. A message that fails (tab closed, navigating, no
receiver) moves on to the next tab, and after the last one to a new tab; it is not counted as a
failed request. With no usable tab, a run opens `<base>/` with `active: false` and waits on
`tabs.onUpdated` for `complete`, then sends a probe request through it. While the probe is still
challenged it retries every 2 seconds. After 30 seconds the run posts a status line asking the user
to complete the check in that tab, and keeps waiting. The module remembers that it opened the tab and
closes it when `processHandler` finishes. The account checker calls `eksiFetch` with
`{ openTab: false }`, so without an open tab it gets the challenge answer back and stops as refused,
as today.

**D5. Bodies are text across the message boundary.** Pages are parsed in the background exactly as
now (`parseHTML` in `scrapingHandler.js:19`). The largest answers are relation-list pages and entry
pages, a few hundred KB, which is far below the extension messaging limit.

## Risks / Trade-offs

- [The page route could also be challenged] → If the content script's request is itself challenged,
  the clearance is gone and the tab has to reload: the module reloads that tab once and re-probes
  under D4's wait. The spike (task 1.1) confirms that page requests pass before anything is built on
  it.
- [A user's own tab navigating mid-run] → A failed message moves on to another tab (D4), so a request
  is never lost to a navigation.
- [A background tab appearing during a run] → It appears only while challenged, only when no Ekşi
  tab is open, it is not focused, and it closes at the end of the run.
- [Firefox for Android tab behaviour] → `tabs.create({ active: false })` and `tabs.query` work there,
  but whether a background tab runs its scripts is checked on a device (task 4.3).
- Carried forward deliberately: the where-is-eksisozluk lookup in `urlHandler.js` still runs only for
  an unreachable Ekşi, never for a challenged one, as 0.6.2 made it.

## Migration Plan

Extension-only, shipped in the next version. No storage or server change, so rollback is shipping the
previous zip.

## Open Questions

- Whether Ekşi ever serves an interactive challenge to a signed-in browser. If it does, D4's 30-second
  status line is what the user sees; nothing else changes.
