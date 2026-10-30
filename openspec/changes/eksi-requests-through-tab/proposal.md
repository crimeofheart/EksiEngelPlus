## Why

Ekşi Sözlük's Cloudflare now answers the extension's background requests with a challenge (HTTP 403,
`cf-mitigated: challenge`, a "Just a moment..." page) while an ordinary Ekşi tab in the same browser
passes it. From the challenged connection every run ends at the access check, and since 0.6.2 it says
so instead of "Ekşi Sözlük'e erişilemedi" -- but it still cannot do anything. The challenge has held
for hours on end (a server probe saw 403 every 10 minutes for 3 hours), so waiting it out is not a
fix. The Android app is unaffected because its WebView makes its requests from inside the page.

## What Changes

- The extension's requests to Ekşi Sözlük go through one routing function instead of calling `fetch`
  directly from the background.
- That function tries the direct request first. When the answer is a Cloudflare challenge, it sends
  the request through a content script in an open Ekşi tab, where it is made from the page and passes
  like the page's own requests, and keeps using that route for the rest of the run.
- When a run needs the tab route and no Ekşi tab is open, the extension opens one in the background,
  waits for the challenge to clear, and closes it at the end of the run. The account checker never
  opens a tab: it uses one that is already open, or stops as refused.
- When the challenge needs a click (an interactive Turnstile check), the run says so and waits for the
  user to complete it in that tab, instead of failing.
- The content script answers only requests to its own origin, from the extension itself.

`frontend/app/` runtime code **is** touched: `background.js`, `scrapingHandler.js`,
`relationHandler.js`, `urlHandler.js`, `historySync.js`, `script.js`, and a new routing module.

## Capabilities

### New Capabilities

- `eksi-request-routing`: how the extension reaches Ekşi Sözlük when the direct route is challenged --
  detection, the tab route, opening and closing a tab, and what the content script will and will not
  fetch.

### Modified Capabilities

(none)

## Non-goals

- Solving or evading the challenge: no stealth browser, no forged clearance, no header tricks. The
  route through a tab is the user's own browser making the request from the page it already opened.
- Any Android change. The WebView already makes its requests from the page.
- Any server change. The server's own account check keeps stopping at the first refusal.
- Faster runs. Pacing, rate limits and the 429 handling stay as they are.

## Impact

- Extension only, both Chrome and Firefox (desktop and Android, one zip): every Ekşi request site in
  `scrapingHandler.js` and `relationHandler.js`, the access check in `urlHandler.js`, the checker's
  requests in `historySync.js`, and a message handler in the content script `script.js`.
- Permissions: none new. `tabs` and the `*://eksisozluk.com/*` content script are already declared.
- A run under a challenge may open one background Ekşi tab for its duration.
