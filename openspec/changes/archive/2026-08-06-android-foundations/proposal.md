# Android foundations

## Why

`android-spike` passed its gate. S1, S2, S3 and S5 are answered: the site renders
in a WebView, an interactively-established session survives the handoff into
OkHttp, a real block/unblock round trip succeeds through it, and both JS-bridge
APIs are available. The architecture is confirmed and nothing argues for the
`fetch`-in-WebView fallback.

What exists in `android/` today is a placeholder shell with no launcher activity,
built only to prove the release pipeline. This change turns it into a real
foundation: the modules, persistence, HTTP stack and parser that every later
phase builds on.

It deliberately ships **no UI and no operations**. The point is to get the layers
that are hard to change later — module boundaries, the Room schema, the cookie
bridge — right while they are cheap, and to make them testable before anything
depends on them.

## What Changes

- **Multi-module Gradle build** replacing the single `:app` placeholder:
  `core:model`, `core:database`, `core:datastore`, `core:network`, `eksi:client`,
  `eksi:parser`. Hilt wiring, version catalog, JDK 17, `minSdk 26`.
- **`core:model`** — the enums from `enums.js` with their **exact integer pks**,
  since those are shared database keys with rows the extension already wrote.
  Plus `TurkishDateParser` and the date-filter predicates as pure functions.
- **`eksi:parser`** — one `Selectors` object holding every selector, Jsoup
  extraction functions, and kotlinx-serialization DTOs for the three JSON
  endpoints. Tested against the committed fixture corpus.
- **`core:network`** — `CookieBridgeInterceptor` (proven on device in the spike),
  `UserAgentInterceptor`, `EksiHeadersInterceptor`, `AuthGuardInterceptor`.
- **`eksi:client`** — `ScrapeClient` and `RelationClient` over that stack, with
  the typed `RelationResult` sealed interface, **1-indexed** pagination, and
  `Retry-After` parsing. No pacer yet; that arrives with the engine.
- **`core:database`** — Room schema for lists, the registration-date cache, and
  operation checkpoints, with `exportSchema = true` and schemas committed.
- **`core:datastore`** — Proto DataStore for config and identity.
- **Tests** — parser assertions against `docs/fixtures/eksisozluk/`, a
  characterisation suite for `TurkishDateParser`, and MockWebServer suites for
  both clients including the spike's observed response codes.

## Capabilities

### New Capabilities

- `android-persistence`: what the app stores, in which layer, and the invariants
  that replace `chrome.storage.local`'s hand-maintained counters.
- `android-http-stack`: how requests are constructed, authenticated from the
  WebView jar, and classified — including session-expiry detection.

### Modified Capabilities

- `eksisozluk-client-contract`: no requirement changes. This change *implements*
  it; the spec stays the shared authority for both clients.

## Impact

**New** — `android/core/{model,database,datastore,network}`,
`android/eksi/{client,parser}`, `android/core/database/schemas/`, and unit tests
in each.

**Modified** — `android/settings.gradle.kts` (module list),
`android/gradle/libs.versions.toml` (the real dependency graph),
`android/app/build.gradle.kts` (depends on the new modules; keeps the version
derivation exactly as is), `.github/workflows/check.yml` if the test task list
needs widening.

**Untouched** — all of `frontend/app/`, `backend/`, and the version-lockstep
mechanism. `android/version.json` and the derived `versionCode` are not touched.

**Depends on** — `android-spike` for the fixture corpus and the confirmed
contract. `unified-release-pipeline` for the module skeleton it extends.

## Non-goals

- Any UI, Compose, or navigation.
- The operations engine, pacer, WorkManager, or notifications.
- The WebView shell and JS bridge.
- Telemetry to the Django backend.
- S4. The rate limit is still unmeasured, so the pacer is deliberately deferred
  rather than built against a guess.
