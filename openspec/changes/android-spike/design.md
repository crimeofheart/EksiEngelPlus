# Design — Android feasibility spike

## Context

The extension's whole interaction with Ekşi Sözlük is four files: `relationHandler.js`
(the one write endpoint), `scrapingHandler.js` (every read), `urlHandler.js` (domain
failover), and `script.js` (DOM injection). None of it has ever run outside a desktop
browser.

The Android design hands `CookieManager`'s jar to OkHttp and parses with Jsoup. Both
substitutions are plausible and neither is proven. Three specific unknowns can each
independently invalidate the architecture:

- If Ekşi rejects an OkHttp request carrying WebView cookies — because of a UA
  mismatch, a missing `Referer`, or bot detection — there is no native engine, only a
  WebView driving injected `fetch`.
- If mobile-UA responses differ structurally, the Android parser is a second selector
  set to maintain rather than a port, roughly doubling the ongoing maintenance cost of
  the riskiest part of the system.
- If the site will not render in a WebView at all, the browsing half of the product
  does not exist.

The spike is scoped to answering these and nothing else. Its most durable output is
not the answers but the **fixture corpus** — the first time this project will have
recorded, byte-for-byte, what the site actually returns.

## Goals / Non-Goals

**Goals**

- A defensible go/no-go on the WebView + native-engine architecture.
- A fixture corpus usable as a test oracle by both clients.
- A measured rate limit, replacing the unverified "12/min" currently living only in a
  user-facing string (`frontend/app/assets/js/notificationHandler.js:60`).
- Seed `eksisozluk-client-contract` from evidence rather than from the extension's
  assumptions.

**Non-Goals**

- Production code, dependency selection, or module layout.
- Engine, persistence, or UI design.
- A device matrix. One modern physical device plus one API 35+ emulator.
- Designing the fallback architecture. If the gate fails, that is a separate change.

## Decisions

### The spike project is disposable and lives outside `android/`

A single-Activity project in a scratch location, deleted at archive time. It must not
land in `android/`, because `unified-release-pipeline` is establishing that tree as
the real Gradle root and spike code carries assumptions that should not survive
contact with production structure.

The temptation to "keep the useful parts" is exactly what turns a spike into a
prototype that ships. The fixtures are the artifact worth keeping.

### Test against a throwaway Ekşi account and a throwaway target

S3 requires performing a real mutation. Use a secondary account as the actor and a
second controlled account as the target, then reverse the action immediately.

Never test mutations against an uninvolved third party's account. The endpoint's
effect is visible to the person being blocked.

### Capture under three user agents, not two

Desktop Chrome (what the extension sends today, the baseline the selectors were
written against), Android Chrome (what the site most likely optimizes its mobile
markup for), and the Android WebView default (what the app will actually send unless
overridden).

Two would leave the interesting case ambiguous: if WebView and Android Chrome differ,
that points at WebView-specific handling; if both differ from desktop identically,
that is ordinary mobile markup and the mitigation is a pinned desktop UA.

### Pin the UA question early because it interacts with cookies

If Ekşi binds a session to its originating user agent, then OkHttp must send the
WebView's exact UA string — and if the *selectors* require a desktop UA while the
*session* requires the WebView UA, those requirements conflict and the architecture
needs rework. This interaction is the single most consequential thing the spike can
discover, so S2 and S3 are run together rather than in sequence.

### Fixtures live at `docs/fixtures/eksisozluk/`, not under `android/`

The corpus serves both clients. Filing it under `android/` would signal it belongs to
the port, and the extension's selectors would keep having no regression tests. One
subdirectory per user agent, one file per endpoint, plus a manifest recording capture
date, the exact UA string, and the account state.

Personally identifying content — nicks, entry text — is unavoidable in captured HTML.
The manifest SHALL note this, and captures SHALL use the throwaway account's own
lists rather than a real user's blocked list.

### Measure the rate limit by driving to 429, once

Perform mutations against controlled targets at a fixed cadence until a 429 arrives.
Record the count, elapsed time, and whether `Retry-After` was present and in what
form. Reverse every mutation afterwards.

This is the only spike step that deliberately trips a server-side protection. Do it
once, at low volume, and record the result so it never needs repeating.

## Risks / Trade-offs

**The spike account gets rate-limited or flagged** → use a throwaway account, keep
volumes minimal, reverse every mutation. Never the maintainer's primary account.

**Fixtures go stale** → they will. The manifest records the capture date, and the
corpus is a regression oracle for "did the site change", not a permanent truth. A
selector test failing against fresh captures *is* the signal.

**S1 passes today and fails after a Cloudflare configuration change** → unmitigable.
It is a standing risk of the whole product, which is why the contract spec exists and
why the eventual client needs selector-health telemetry.

**Captured HTML contains third-party content** → capture using the throwaway account's
own data; note the exposure in the manifest.

**The gate is answered "yes, but"** → most likely outcome. A qualified pass, e.g.
"works with a pinned desktop UA", is a valid result provided the qualification is
written into the contract spec rather than left in someone's memory.

## Migration Plan

Not applicable — nothing ships. At archive time the spike project is deleted; the
report and fixtures remain.

## Open Questions

Resolved by execution, recorded in `docs/android/spike-report.md`:

- **S1** Does eksisozluk.com render and permit login inside Android WebView? Any
  Cloudflare interstitial, bot check, or unsupported-browser page?
- **S2** Does the mobile UA change the HTML enough to break the selectors in
  `eksisozluk-client-contract`? Per-selector match counts under all three UAs.
- **S3** Does OkHttp, carrying `CookieManager` cookies and `x-requested-with`, produce
  a successful `addrelation`? Are `Referer` or `Origin` required? Does a UA mismatch
  between the WebView session and the OkHttp request break it?
- **S4** Is `Retry-After` actually returned on 429, and what is the real limit?
- **S5** Are `DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER` supported at the
  intended WebView floor, on both a physical device and an API 35+ emulator?

Each answer is either evidence-backed or the gate fails on that question. "Probably
fine" is not an answer.
