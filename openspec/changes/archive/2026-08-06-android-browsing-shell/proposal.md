# Browsing shell and JS bridge

## Why

The engine can perform operations but nothing can start one. Every entry point in
the extension is a menu item injected into an Ekşi page — the entry dropdown, the
title menu, the profile buttons — and none of that exists yet.

This change adds the browsing half: a WebView showing the real site with our menu
items in it, and the bridge that turns a tap into an operation.

It is also the only viable login path. `/giris` is behind Cloudflare Turnstile, so
a session can only be established in a real browser context. The WebView is that
context.

## What Changes

- **`BrowserScreen`** — a WebView rendering eksisozluk.com, sharing its cookie jar
  with the engine's OkHttp stack.
- **`bridge.js`** — ported from `script.js`, roughly 80% unchanged. Injects the
  same items into the same targets.
- **Injection via `addDocumentStartJavaScript`** rather than `onPageFinished`, so
  the menu is present before the page paints instead of appearing a beat later.
- **`addWebMessageListener`** for the JS→Kotlin channel, origin-scoped, replacing
  `chrome.runtime.sendMessage`.
- **A persistent `IdempotentInjector`** replacing `waitForElm`, which resolves once
  and disconnects — correct for one page load, wrong for Ekşi's XHR pagination.
- **`SessionMonitor`** exposing login state, so `PAUSED_AUTH` operations can resume
  when a session reappears.
- **URL allowlisting**: only Ekşi origins load in the WebView; everything else
  opens in a Custom Tab.

## Capabilities

### New Capabilities

- `android-browsing`: what the WebView loads, what gets injected where, how the
  page talks to the app, and how login state is observed.

### Modified Capabilities

None.

## Impact

**New** — `android/webview` with `bridge.js` as an asset, and a browser screen in
`:app`.

**Modified** — `:app` manifest gains a launcher activity; `settings.gradle.kts`.

**Untouched** — `frontend/app/`, `backend/`, version lockstep. `script.js` itself
is not modified; the port is a copy that diverges where Android requires.

## Non-goals

- The remaining operation sources (migrations, date filters, list refresh).
- Settings, history and list screens.
- Replacing the dev harness — that still exercises the engine directly.
- Native rendering of Ekşi content. The WebView shows the real site; we add to it
  rather than reimplement it.
