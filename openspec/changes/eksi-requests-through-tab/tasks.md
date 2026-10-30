## 1. Confirm the page route

- [x] 1.1 Spike (page context: `fetch('/biri/loosey')` answered 200 while the background got 403): with the connection challenged, in an open Ekşi tab run `fetch('/biri/<nick>')` and `fetch('/son-entryleri?nick=<nick>', {headers: {'x-requested-with': 'XMLHttpRequest'}})` from a content-script context (Chrome: a temporary message handler in `script.js`; Firefox: `content.fetch`). Both must answer 200. If they do not, stop and revise design D3.
- [x] 1.2 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 2. Routing module and content script

- [x] 2.1 `assets/js/eksiFetch.js`: `eksiFetch(url, init, { openTab = true })`, the response object (D1), direct-first and sticky tab mode with `resetRoute()` (D2); move `isCloudflareChallenge` there and import it in `urlHandler.js`
- [x] 2.2 Tab selection, failover to the next tab, opening a background tab, probe-and-wait with the 30-second status line, closing the tab the module opened (D4)
- [x] 2.3 `script.js`: the `eksiFetch` message handler with the sender and origin checks, `content.fetch` on Firefox (D3)
- [x] 2.4 Node tests (new `scripts/test-eksi-fetch.mjs`, wired into `npm run check`): unchallenged requests stay direct; a challenge switches to the tab and stays there until `resetRoute()`; a failed tab message fails over; `openTab: false` opens nothing; the content-script handler refuses another origin and another sender
- [x] 2.5 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 3. Call sites

- [x] 3.1 `urlHandler.js` access check through `eksiFetch`; a challenge that the tab route clears counts as `EksiAccess.OK`
- [x] 3.2 `scrapingHandler.js`: `#fetchEksiSozluk` and every direct `fetch` listed in design Context through `eksiFetch`
- [x] 3.3 `relationHandler.js`: the relation write through `eksiFetch`, keeping the 429 `Retry-After` handling
- [x] 3.4 `historySync.js`: `eksiGet` through `eksiFetch` with `{ openTab: false }`
- [x] 3.5 `background.js`: the status line for a check waiting on a click (no per-run reset or close: D2 and D4 are time-based)
- [x] 3.6 Update the stubs in `scripts/test-relation-actions.mjs` and `scripts/test-history-sync.mjs`
- [x] 3.7 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 4. Ship

- [x] 4.1 Release note in the undated `0.6.2` entry of `changelog.js` (extension; `app: []`), regenerate with `npm run changelog`
- [x] 4.2 Manual, Chrome desktop while challenged: a block run with an Ekşi tab open, then with none open (tab opens and closes); the account checker posts results with a tab open
- [ ] 4.3 Manual, Firefox desktop and Firefox for Android: the same block run
- [x] 4.4 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`
