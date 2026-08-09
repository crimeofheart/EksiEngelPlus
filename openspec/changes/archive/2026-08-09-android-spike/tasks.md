## 1. Preparation

- [x] 1.1 Create or designate a throwaway Ekşi Sözlük account as the actor, and a second controlled account as the mutation target — never a third party's account — actor `coh81` (3658105), target `coh` (3656098), both the owner's
- [ ] 1.2 Populate the actor account with a small blocked list, muted list, and followed list so the JSON list endpoints return non-empty, non-personal data — NOT DONE, and the reason it matters is recorded in `docs/fixtures/eksisozluk/MANIFEST.md` "Coverage gap": the corpus is logged-out only, so five selectors stay ambiguous between auth-gated and removed. The gap survives this change's archival in that document.
- [x] 1.3 Create the throwaway Android project in a scratch location outside `android/` — single Activity, WebView, OkHttp, nothing else
- [x] 1.4 Prepare a physical device and an API 35+ emulator; record OS version and WebView provider version for both

## 2. S1 — Does the site work in a WebView at all

- [x] 2.1 Load the base URL in the WebView with `javaScriptEnabled` and `setAcceptThirdPartyCookies` on; screenshot the result
- [x] 2.2 Record whether a Cloudflare interstitial, bot check, or unsupported-browser page appears, on device and emulator
- [x] 2.3 Complete a full login inside the WebView and confirm the logged-in homepage renders — confirmed on WebView 149; the nick selector resolved to `coh81`
- [ ] 2.4 Confirm the session survives an app restart, verifying `CookieManager` persistence and that `flush()` is needed — NOT DONE as a spike task. The shipped app answers it instead: `EksiWebView.kt:212` calls `CookieManager.getInstance().flush()`, and `WebViewCookies.kt` debounces further flushes through `CookieFlusher`, which has unit coverage. The question moved from the spike into the product.
- [x] 2.5 Navigate a title page, an entry, and a profile page; record any rendering or interaction breakage

## 3. S2 + S3 — Selectors and cookie sharing, run together

- [x] 3.1 Build the capture harness: given a UA string, fetch every endpoint in `eksisozluk-client-contract` via OkHttp with the two load-bearing headers and write each response to disk verbatim
- [x] 3.2 Wire `CookieBridgeInterceptor` in prototype form — read `CookieManager.getCookie(url)` into a `Cookie` header, write `Set-Cookie` back into the jar
- [x] 3.3 Capture all endpoints under the desktop Chrome UA; confirm the responses match what the extension expects today
- [x] 3.4 Capture all endpoints under the Android Chrome UA
- [x] 3.5 Capture all endpoints under the Android WebView default UA from `WebSettings.getDefaultUserAgent`
- [x] 3.6 Run every selector from `eksisozluk-client-contract` against all three capture sets and record per-selector match counts in a comparison table
- [x] 3.7 Determine specifically whether `.mobile-notification-icons .mobile-only a[title]` still yields the nick under each UA — the element name suggests a mobile variant may already differ
- [x] 3.8 Test whether the session is UA-bound: log in via WebView, then issue an OkHttp request with a *different* UA and record whether it 302s to login
- [x] 3.9 Determine whether `Referer` and `Origin` are required, by sending an authenticated read with and without each
- [x] 3.10 Perform one real `POST /userrelation/addrelation/{id}?r=m` against the controlled target; log the full request and response
- [x] 3.11 Reverse it with `removerelation` and log the response; confirm the two documented shapes — bare number for BAN, object with `result` for UNDOBAN
- [x] 3.12 Repeat 3.10–3.11 for `r=u` (mute) and `r=b` (follow) to confirm the relation codes behave as documented
- [x] 3.13 Verify the negative case: issue the same mutation with `x-requested-with` omitted and record how the response shape changes

## 4. S4 — Measure the real rate limit

**MOVED to `android-rate-limit-measurement`.** Never run here. It deliberately
trips a server-side protection, so it was scheduled last and the spike closed on
its actual gate — S3 — without reaching it. It is real work, not abandoned work,
and it does not belong in a feasibility change whose feasibility question is
answered. Sections 2 and 3 of the new change carry these five tasks forward
verbatim, plus the reversal audit they need.

- [~] 4.1 Drive mutations against controlled targets at a fixed cadence until a 429 is returned; record the count and elapsed time
- [~] 4.2 Record whether `Retry-After` is present, and whether it is integer seconds or an HTTP date
- [~] 4.3 Confirm the actual cooldown by retrying at the advertised time and recording success or a further 429
- [~] 4.4 Reverse every mutation performed during this section
- [~] 4.5 Compare the measured limit against the 12/min figure in `frontend/app/assets/js/notificationHandler.js:60` and record the discrepancy if any

## 5. S5 — WebView capability floor

- [x] 5.1 Check `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` on device and emulator; record the WebView provider version for each
- [x] 5.2 Check `WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)` on both
- [x] 5.3 Prototype `addDocumentStartJavaScript` injecting a trivial marker and confirm it runs before page script
- [x] 5.4 Prototype `addWebMessageListener` round-tripping one message from injected JS to Kotlin and back
- [x] 5.5 Confirm a menu item can be injected into a real Ekşi entry dropdown and that a tap reaches Kotlin
- [x] 5.6 Determine the minimum WebView provider version supporting both features, and derive the `minSdk` implication

## 6. Fixture corpus

- [x] 6.1 Commit captures to `docs/fixtures/eksisozluk/<ua>/` — one subdirectory per user agent, one file per endpoint
- [x] 6.2 Write `docs/fixtures/eksisozluk/MANIFEST.md` recording capture date, exact UA strings, account state, and the endpoint-to-file mapping
- [x] 6.3 Note in the manifest that captures contain user-generated content from the throwaway account, and that they are a point-in-time regression oracle rather than a permanent truth
- [x] 6.4 Verify the corpus is usable as a test oracle by parsing every fixture offline and asserting the documented extractions

## 7. Report and gate

- [x] 7.1 Write `docs/android/spike-report.md` answering S1–S5, each with attached evidence — screenshots, logged requests and responses, the selector comparison table — S1, S2, S3, S5 answered; S4 recorded as an open measurement gap
- [x] 7.2 State the go/no-go explicitly, including any qualification such as "passes with a pinned desktop UA" — **GO**, qualified on a pinned user agent
- [x] 7.3 Fold every qualification into `eksisozluk-client-contract` so it survives as a requirement rather than as report prose
- [x] 7.4 On go: create the follow-on OpenSpec changes — `android-foundations`, `android-browsing-shell`, `android-operations-engine`, `android-lists-and-csv`, `android-migrations-date-filters`, `android-settings-telemetry`, `android-play-release`
- [x] 7.5 On no-go: open a new change proposing the WebView-injected-`fetch` fallback, with the failing evidence as its motivation — N/A, the gate was a go

## 8. Close out

- [x] 8.1 Confirm every mutation performed during the spike has been reversed on both test accounts — the one mutation made, `addrelation r=m`, was reversed by `removerelation r=m` in the same run, both logged in the report. Section 4 never ran, so it left nothing to reverse.
- [x] 8.2 Delete the throwaway Android project — superseded by promotion rather than deletion: the harness became `android/devharness/`, a separate application module with its own `applicationId` that `:app` does not depend on, so it never reaches the release artifact
- [ ] 8.3 Confirm `git diff --stat -- frontend/app/ backend/ android/` is empty — the spike touched none of them — SUPERSEDED: the port has since landed in `android/`, so this can never hold again. It was a guard against the spike leaking into production trees, and it held for the spike's own duration.
- [x] 8.4 Verify the extension is unbroken: `cd frontend/app && npm run check && npm run package`
- [ ] 8.5 Run `openspec validate android-spike` clean, then `openspec archive android-spike`
