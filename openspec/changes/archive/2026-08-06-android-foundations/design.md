# Design — Android foundations

## Context

`android/` currently holds one `:app` module with no code, existing only so
`unified-release-pipeline` could prove a `v*` tag produces an AAB and APK. The
version derivation in `app/build.gradle.kts` is load-bearing and must survive
untouched.

`android-spike` established the facts this change implements against, all
verified on a real device rather than read out of the extension source:

- OkHttp carrying `CookieManager`'s jar authenticates as the logged-in user, and
  a full `addrelation`/`removerelation` round trip succeeds through it.
- `x-requested-with` is mandatory — without it `/relation-list` answers 500.
- Pagination is 1-indexed; `pageIndex=0` answers 500. Page size 25.
- BAN returns a bare number: `0`/`2` success, `4` self-target. UNDOBAN returns
  `{result, count}`.
- Login is behind Cloudflare Turnstile, so sessions are interactive-only.
- Four `.dropdown-menu` per page; `ul.toggles-menu` matches nothing.
- Live nicks contain spaces.

The contract lives in `eksisozluk-client-contract` and is shared with the
extension. This change implements it; it does not redefine it.

## Goals / Non-Goals

**Goals**

- Module boundaries, Room schema and the cookie bridge settled while changing
  them is still cheap.
- Every parser assertion backed by the committed fixture corpus rather than by
  reading `scrapingHandler.js`.
- `TurkishDateParser` characterised before it has dependents.

**Non-Goals**

- UI, engine, pacer, WorkManager, JS bridge, telemetry.
- The pacer specifically: S4 has not measured the real rate limit, and building a
  pacer against the unverified "12/min" string would bake in a guess.

## Decisions

### Six modules, not one

`core:model`, `core:database`, `core:datastore`, `core:network`, `eksi:parser`,
`eksi:client`. The split that earns its keep is `eksi:parser` — it depends only on
Jsoup and kotlinx-serialization, no Android framework, so its tests are plain JVM
tests running against fixture files with no emulator. That is the difference
between selector regressions being caught in CI in seconds versus not at all.

`core:model` is Android-free for the same reason: `TurkishDateParser` and the
date-filter predicates are pure functions and their characterisation suite must
be trivial to run.

### Interceptors, not a CookieJar

Settled in the spike and proven on device. `CookieManager.getCookie()` returns a
pre-assembled header string with no attributes; reconstructing `okhttp3.Cookie`
objects from it loses domain, path and expiry, and gains nothing. The interceptor
reads that string straight into a `Cookie` header and writes `Set-Cookie` back.

Four interceptors, each with one job: `CookieBridgeInterceptor`,
`UserAgentInterceptor`, `EksiHeadersInterceptor`, `AuthGuardInterceptor`. Ordering
matters — the auth guard runs outermost so it sees the final response.

### The client returns delays; it does not sleep

`RelationClient` parses `Retry-After` and returns it inside `RelationResult.RateLimited`.
It never sleeps. When the pacer arrives it must apply a 429 penalty **globally**,
to every concurrent and subsequent caller, not just the one that got the 429. A
client that slept internally would make that impossible, and the extension's two
divergent cooldown implementations are exactly what that mistake looks like.

### Typed results over numeric codes

`RelationResult` is a sealed interface. The spike found `4`, which neither the
extension nor the contract knew about, purely because the harness printed
"unexpected number" rather than folding it into a boolean. Encoding
success/failure as a sealed type makes the next unknown code equally visible
instead of silently false.

### Counts derived, never stored

One `RelationUserEntity` table keyed `(listType, userId)`, counts via `COUNT(*)`.
The extension stores `mutedUserCount` next to `mutedUserList`; two sources of
truth that can drift. Deriving removes the failure mode rather than guarding it.

### Proto DataStore over Preferences

`dateFilterRules` is a repeated structured value. Preferences would mean
serialising JSON into a string key and hand-parsing it — the thing Proto exists to
avoid.

### Fixtures are the parser's oracle

Every selector assertion runs against `docs/fixtures/eksisozluk/`, captured from
the live site under three user agents. Writing assertions from the extension
source instead would only prove the port matches the original's *assumptions*, not
the site's behaviour — and the spike already found one place they differ.

## Risks / Trade-offs

**Fixtures are logged-out only** → the auth-gated shapes are recorded in the
contract from device runs but have no committed HTML. Those parsers are tested
against hand-built JSON matching the observed shapes. Committing logged-in
captures would embed a real session's content in the repo; not worth it.

**Six modules is a lot for pre-UI code** → the boundaries follow test strategy,
not taxonomy. If `core:datastore` and `core:database` stay thin they can merge
later; splitting after the fact is the expensive direction.

**Room schema will change** → certain. `exportSchema` plus the CI drift check
makes each change deliberate. No migrations are written yet because there is no
installed base; version 1 stands until the first release.

**`4` may not mean self-target** → it was observed exactly once, in a self-block.
The typed result names it `SelfTarget` but records the raw code, so a
contradicting observation is a rename rather than a redesign.

## Migration Plan

Additive. The `:app` module keeps its current build file except for new module
dependencies; `android/version.json` and the `versionCode` derivation are
untouched. Nothing ships to users, so there is no rollback beyond reverting.

Order: modules and catalog first, then `core:model` with its date tests, then
`eksi:parser` against fixtures, then `core:network`, then `eksi:client` with
MockWebServer, then Room and DataStore. Each step is independently green.

## Open Questions

- **The real rate limit.** S4 is unmeasured, so the pacer is deferred. If the
  limit turns out to be far from 12/min, the engine's duration estimates change
  and so does the multi-day UX.
- **`/follower` and `/following` element shapes.** Both test accounts have empty
  lists, so those DTOs are written from the contract and remain unverified against
  live data.
