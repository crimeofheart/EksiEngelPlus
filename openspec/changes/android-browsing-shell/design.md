# Design — browsing shell and JS bridge

## Context

The engine works but has no entry point. Every way a user starts an operation in
the extension is a menu item injected into an Ekşi page, and none of that exists.

The WebView is also the only route to a session: `/giris` is behind Turnstile, so
a real browser context is mandatory. That was measured in the spike, not assumed.

## Goals / Non-Goals

**Goals** — the site as users know it, with our items in it; a bridge narrow
enough to be safe on a page full of user-posted links; injection that survives
in-page navigation.

**Non-Goals** — native rendering of Ekşi content; the remaining operation
sources; settings and history screens.

## Decisions

### Document-start injection over `onPageFinished`

`addDocumentStartJavaScript` runs before page script on every document and is
origin-scoped. `onPageFinished` would inject after paint, so the user sees the
menu appear a beat late — small, but on every page. Both spike devices supported
`DOCUMENT_START_SCRIPT` (WebView 124 and 149), so the fallback is genuinely a
fallback.

### `addWebMessageListener` over `addJavascriptInterface`

`addJavascriptInterface` exposes the object to **every** page the WebView loads,
with no origin scoping. This WebView browses a user-content site whose pages are
full of arbitrary outbound links, so that is a real hole rather than a
theoretical one. `addWebMessageListener` is origin-scoped by contract.

Paired with `shouldOverrideUrlLoading` restricting the WebView to Ekşi origins:
the allowlist only means something if off-site navigation cannot happen inside
the privileged context.

### One persistent observer, not `waitForElm`

`script.js:75-105` resolves and disconnects. Fine for one page load; wrong for
XHR pagination, and much more visible in a WebView where the user never reloads
because there is no address bar. Replaced with a single observer over a registry
of injectors, coalesced through rAF plus a trailing debounce so a 200-node insert
triggers one pass.

`pushState`/`replaceState` are monkey-patched because they mutate nothing and
would otherwise be missed entirely.

### Text-matching the entry menu is required, not defensive

Four `.dropdown-menu` elements render per entry page — measured on device. Position
cannot identify the right one, so the extension's match against
`['engelle','modlog','şikayet','mesaj']` is load-bearing and gets ported as-is.

`ul.toggles-menu` matched zero elements everywhere it was checked. Dropped rather
than carried forward as a dead alternative.

### Config in the preamble

The extension reads config asynchronously and can render a label before the value
arrives. Serialising it into the document-start script makes the read synchronous
and the race disappears. Changes are additionally pushed to open pages, so a
settings edit does not require a reload.

### Fixing the attribute casing

The extension marks nodes via `dataset.eksiengelProcessed` — producing
`data-eksiengel-processed` — but guards with
`:not([data-eksiengelProcessed="true"])`. HTML lowercases attribute names, so that
guard never matches and de-duplication rests entirely on an early-return check.
Harmless today because the check exists; fixed here so the guard is real.

## Risks / Trade-offs

**Ekşi changes its DOM** → the injection stops appearing. Selectors live in one
place and the fixture corpus is the regression oracle. Unavoidable for any client
that augments a third-party page.

**A malicious page reaches the bridge** → origin-scoped listener plus a WebView
that refuses to navigate off-site. Both would have to fail.

**Turnstile starts challenging ordinary browsing, not just login** → would affect
the extension identically. Nothing to do in advance.

**Mobile layout differs from desktop** → all injection targets were verified on
device under the mobile user agent, and WebView matched Android Chrome byte for
byte.

## Migration Plan

Additive. A new module plus a launcher activity. `script.js` is untouched; the
port is a copy that diverges only where Android requires.

## Open Questions

- **Custom Tabs versus an intent for off-site links.** Custom Tabs is nicer but
  adds a dependency; an intent is free. Starting with an intent.
- **Whether config push needs re-registering the document-start script.** Live
  pages get a message; the next document needs the updated preamble. Both are
  implemented; whether the re-registration races a navigation in flight needs a
  real-device look.
