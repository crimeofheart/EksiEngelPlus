## 1. Confirm the page route

- [ ] 1.1 Spike: with the connection challenged, in an open Ekşi tab run `fetch('/biri/<nick>')` and `fetch('/son-entryleri?nick=<nick>', {headers: {'x-requested-with': 'XMLHttpRequest'}})` from a content-script context (Chrome: a temporary message handler in `script.js`; Firefox: `content.fetch`). Both must answer 200. If they do not, stop and revise design D3.
- [ ] 1.2 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 2. Routing module and content script

- [ ] 2.1 `assets/js/eksiFetch.js`: `eksiFetch(url, init, { openTab = true })`, the response object (D1), direct-first and sticky tab mode with `resetRoute()` (D2); move `isCloudflareChallenge` there and import it in `urlHandler.js`
- [ ] 2.2 Tab selection, failover to the next tab, opening a background tab, probe-and-wait with the 30-second status line, closing the tab the module opened (D4)
- [ ] 2.3 `script.js`: the `eksiFetch` message handler with the sender and origin checks, `content.fetch` on Firefox (D3)
- [ ] 2.4 Node tests (new `scripts/test-eksi-fetch.mjs`, wired into `npm run check`): unchallenged requests stay direct; a challenge switches to the tab and stays there until `resetRoute()`; a failed tab message fails over; `openTab: false` opens nothing; the content-script handler refuses another origin and another sender
- [ ] 2.5 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 3. Call sites

- [ ] 3.1 `urlHandler.js` access check through `eksiFetch`; a challenge that the tab route clears counts as `EksiAccess.OK`
- [ ] 3.2 `scrapingHandler.js`: `#fetchEksiSozluk` and every direct `fetch` listed in design Context through `eksiFetch`
- [ ] 3.3 `relationHandler.js`: the relation write through `eksiFetch`, keeping the 429 `Retry-After` handling
- [ ] 3.4 `historySync.js`: `eksiGet` through `eksiFetch` with `{ openTab: false }`
- [ ] 3.5 `background.js`: `resetRoute()` at the start of `processHandler`, closing an opened tab at every finish path
- [ ] 3.6 Update the stubs in `scripts/test-relation-actions.mjs` and `scripts/test-history-sync.mjs`
- [ ] 3.7 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`

## 4. Ship

- [ ] 4.1 Release note in the undated `0.6.2` entry of `changelog.js` (extension; `app: []`), regenerate with `npm run changelog`
- [ ] 4.2 Manual, Chrome desktop while challenged: a block run with an Ekşi tab open, then with none open (tab opens and closes); the account checker posts results with a tab open
- [ ] 4.3 Manual, Firefox desktop and Firefox for Android: the same block run
- [ ] 4.4 Re-verify the extension still builds: `cd frontend/app && npm run check && npm run package`
