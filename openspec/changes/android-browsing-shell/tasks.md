## 1. Module and screen

- [x] 1.1 Add `:webview` to `settings.gradle.kts` as an Android library depending on `core:network`, `core:datastore` and `ops:runtime`
- [x] 1.2 Implement `EksiWebViewClient` with `shouldOverrideUrlLoading` allowing only configured Ekşi origins and sending everything else out via an intent
- [x] 1.3 Harden settings: JS on, `allowFileAccess=false`, `allowContentAccess=false`, Safe Browsing on, `MIXED_CONTENT_NEVER_ALLOW`, algorithmic darkening
- [x] 1.4 Set the WebView user agent to the same string OkHttp sends, so a session works in both
- [x] 1.5 Add a launcher activity hosting the WebView, with back-navigation handling

## 2. bridge.js

- [x] 2.1 Port the badge hiding from `script.js:107-178` unchanged
- [x] 2.2 Port the `#in-topic-search-options` two-item injection
- [x] 2.3 Port the entry-dropdown injection, keeping the text match against `['engelle','modlog','şikayet','mesaj']` and dropping `ul.toggles-menu`
- [x] 2.4 Port the profile `.profile-buttons` handling, including removing `#button-blocked-link`
- [x] 2.5 Port the `#user-notifications` toast
- [x] 2.6 Replace `waitForElm` with a persistent `IdempotentInjector` — one observer, rAF plus trailing debounce, an injector registry
- [x] 2.7 Hook `pushState`, `replaceState` and `popstate` to force a rescan
- [x] 2.8 Use `data-eksiengel-processed` consistently, fixing the casing bug
- [x] 2.9 Replace `chrome.runtime.getURL(icon)` with a `data:` URI constant
- [x] 2.10 Replace `chrome.runtime.sendMessage` with the versioned envelope over `EksiEngelPlus.postMessage`

## 3. The bridge

- [x] 3.1 Register `bridge.js` via `addDocumentStartJavaScript` with an origin allowlist, falling back to `onPageCommitVisible` behind an idempotence flag
- [x] 3.2 Register `addWebMessageListener` under the name `EksiEngelPlus`, origin-scoped
- [x] 3.3 Define the envelope and map `enqueueAction` onto `OperationRequest`
- [x] 3.4 Serialise config into the preamble so the page reads it synchronously
- [ ] 3.5 Push `configChanged` to open pages and re-register the preamble for subsequent loads
- [x] 3.6 Enqueue the operation via `OperationWorker` and reply so the page can toast

## 4. Session

- [x] 4.1 Implement `SessionMonitor` exposing login state, authoritative via the homepage avatar and using a cookie check only as a fast negative
- [x] 4.2 Re-probe when the WebView navigates to `/`, `/giris` or `/cikis`
- [ ] 4.3 Offer resumption of `PAUSED_AUTH` operations when a session appears

## 5. Verify

- [x] 5.1 Unit-test the envelope mapping onto `OperationRequest`
- [ ] 5.2 Instrumented test: the bridge is present on an allowed origin and absent elsewhere
- [ ] 5.3 Instrumented test: injection is idempotent across repeated observer passes
- [x] 5.4 `./gradlew build` green and instrumented tests green on an emulator
- [x] 5.5 Re-verify the extension: `cd frontend/app && npm run check && npm run package`
- [x] 5.6 Confirm `git diff --stat -- frontend/app/assets/` is empty
- [x] 5.7 `openspec validate android-browsing-shell` clean
