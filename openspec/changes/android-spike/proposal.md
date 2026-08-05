# Android feasibility spike

## Why

An Android port of EksiEngelPlus rests on three assumptions that have never been
tested, and each one, if false, invalidates the whole architecture rather than a
corner of it:

1. **Ambient session cookies transfer.** The extension has no auth code whatsoever —
   no tokens, no CSRF, no login flow. It free-rides on the browser's cookie jar
   (`frontend/app/assets/js/relationHandler.js:129-202`). The Android design assumes
   `WebView`'s `CookieManager` jar can be handed to OkHttp and that Ekşi accepts the
   result. If it cannot, there is no native engine.
2. **The HTML is the same on mobile.** Every selector in
   `frontend/app/assets/js/scrapingHandler.js` was derived from desktop responses. A
   mobile user agent may return different markup, in which case the Android parser is
   not a port but a second, independently-maintained selector set.
3. **The site tolerates a WebView at all.** Cloudflare interstitials, bot checks, or
   an "unsupported browser" page would end the "browse just like the web" premise
   before any engine work matters.

Writing production Kotlin before answering these would be building on assumptions.
The fallback if (1) or (3) fails — driving every request through injected `fetch`
inside the WebView — is a fundamentally different, slower, more fragile design, and
discovering that need in month three is the expensive way to learn it.

This change is a **hard gate**. Its output is a written report and a fixture corpus,
not shippable code.

## What Changes

- A throwaway single-Activity Android project, built to be deleted. It is not the
  `android/` Gradle root from `unified-release-pipeline` and shares no code with the
  eventual app.
- Five spike questions answered in writing, each with evidence attached.
- **A captured HTML fixture corpus at `docs/fixtures/eksisozluk/`** — every endpoint
  the extension touches, recorded under three user agents. This is the highest-value
  output and outlives the spike: it becomes the Android parser's test corpus *and*
  lets the existing extension's JSDOM selectors be regression-tested against the same
  bytes.
- A spike report at `docs/android/spike-report.md` ending in an explicit go / no-go,
  and — on go — the list of follow-on OpenSpec changes to create.

## Capabilities

### New Capabilities

- `eksisozluk-client-contract`: the Ekşi Sözlük HTTP surface both clients depend on —
  the single write endpoint, the three JSON shapes, every selector, the load-bearing
  headers, and the observed rate limit. Seeded here from captured evidence rather than
  from reading the extension source, so it records what the site *does*, not what the
  extension *assumes*.

### Modified Capabilities

None.

## Impact

**New**
- `docs/android/spike-report.md`
- `docs/fixtures/eksisozluk/` — captured HTML and JSON, one subdirectory per user agent
- A throwaway Android project, kept outside `android/` and deleted at archive time

**Untouched**
- All of `frontend/app/` — no extension changes, runtime or tooling.
- `backend/**`.
- `android/**` — the spike must not contaminate the real Gradle root.

**Depends on**
- Nothing. Runs in parallel with `unified-release-pipeline`.

**Blocks**
- `android-foundations` and every subsequent Android change.

## Non-goals

- Any production Android code, Gradle module, or dependency choice.
- UI, Compose, Room, WorkManager, or engine design — all deferred.
- Performance measurement beyond what is needed to observe the rate limit.
- Exhaustive device-matrix testing. One modern device and one API 35+ emulator suffice
  to answer the five questions.
- Deciding the fallback architecture in detail. If the gate fails, that is its own
  change with its own proposal.
