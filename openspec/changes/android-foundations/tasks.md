## 1. Module skeleton

- [ ] 1.1 Expand `android/gradle/libs.versions.toml` with the real graph: Kotlin 2.2.x, coroutines, OkHttp 4.12 + mockwebserver, Jsoup 1.18+, kotlinx-serialization 1.9, Room 2.8 + KSP, DataStore 1.1 + protobuf, Hilt 2.57, Turbine, JUnit
- [ ] 1.2 Add the six modules to `settings.gradle.kts`: `core:model`, `core:database`, `core:datastore`, `core:network`, `eksi:parser`, `eksi:client`
- [ ] 1.3 Write build files: `core:model` and `eksi:parser` as pure JVM/Kotlin where possible so their tests need no emulator; the rest as Android libraries
- [ ] 1.4 Add Hilt to `:app` and an `@HiltAndroidApp` Application class
- [ ] 1.5 Wire `:app` to depend on the new modules, leaving the version derivation in `app/build.gradle.kts` untouched
- [ ] 1.6 Verify `./gradlew :app:assembleDebug` still succeeds and `printVersion` still reports the derived pair

## 2. core:model

- [ ] 2.1 Port `enums.js` with exact integer pks for `BanSource` (1-14), `BanMode`, `TargetType`, `ClickSource`, `TimeSpecifier`
- [ ] 2.2 Port `LogLevel` with the **client** mapping `{DISABLED:1, INFO:2, WARN:3, ERR:4}`, commenting the divergence from `api/migrations/0007_seed_lookup_data.py:38`
- [ ] 2.3 Add `String.toEksiSlug()` — trim, spaces to hyphens — as the single normalisation point
- [ ] 2.4 Write the ~60-case `TurkishDateParser` table **before** the implementation: month names ocak–aralık, `ağustos 2026`, `temmuz 2026`, ISO, `DD.MM.YYYY`, and unparseable inputs
- [ ] 2.5 Implement `TurkishDateParser` against that table using `java.time` in `Europe/Istanbul`
- [ ] 2.6 Port `getDaysDifference`, `evaluateDateFilter`, `applyDateFilters` as pure functions with tests
- [ ] 2.7 Verify `./gradlew :core:model:test` green

## 3. eksi:parser

- [ ] 3.1 Create `Selectors` holding every selector from the contract in one object
- [ ] 3.2 Implement `parseOwnNick`, `parseAuthorProfile` (`#who` + `.recorddate` with the six fallbacks), `parseEntry`, `parseFavouriters`, `parseTopicAuthors`
- [ ] 3.3 Replace the extension's full-document `querySelectorAll('*')` registration-date fallback with one bounded pass over `li,span,div,p,dd,td` matching `(?i)(kayıt|katılım)\s+tarihi`
- [ ] 3.4 Add kotlinx-serialization DTOs for `/relation-list`, `/follower`, `/following` with `ignoreUnknownKeys`
- [ ] 3.5 Add a `SelectorHealth` counter recording when a should-match selector yields zero
- [ ] 3.6 Write parser tests against `docs/fixtures/eksisozluk/logged-out/` for all three user agents, asserting the counts the spike recorded
- [ ] 3.7 Add a test asserting `ul.toggles-menu` matches zero, so the dead selector cannot be reintroduced as a dependency
- [ ] 3.8 Add JSON tests for the observed shapes including the 25-item populated page and the empty-envelope case
- [ ] 3.9 Verify `./gradlew :eksi:parser:test` green

## 4. core:network

- [ ] 4.1 Implement `CookieBridgeInterceptor` reading `CookieManager.getCookie` into a `Cookie` header and writing `Set-Cookie` back
- [ ] 4.2 Implement `CookieFlusher` debouncing `CookieManager.flush()` to ~10 s with a forced flush hook
- [ ] 4.3 Implement `UserAgentInterceptor` sending `WebSettings.getDefaultUserAgent`, cached, never constructing a WebView per call
- [ ] 4.4 Implement `EksiHeadersInterceptor` for the two load-bearing headers, sending no `Origin`
- [ ] 4.5 Implement `AuthGuardInterceptor` classifying redirect-to-`giris`, 401/403, and HTML-where-JSON-expected as session-expired
- [ ] 4.6 Implement `WebViewAvailability` so `MissingWebViewPackageException` surfaces as a typed state, not a crash
- [ ] 4.7 Provide the OkHttp graph via Hilt with `followRedirects(false)` so a login redirect is observable
- [ ] 4.8 Verify `./gradlew :core:network:testDebugUnitTest` green

## 5. eksi:client

- [ ] 5.1 Define `RelationResult`: `Success`, `AlreadyInState`, `SelfTarget`, `RateLimited(seconds)`, `SessionExpired`, `Failed(code, body)`
- [ ] 5.2 Implement `RelationClient.perform(mode, targetType, id)` building `POST /userrelation/{addrelation|removerelation}/{id}?r={m|i|u|b}` with body `id={id}`
- [ ] 5.3 Parse BAN as a bare number — 0/2 success, 4 self-target, anything else `Failed` recording the code — and UNDOBAN as an object with `result`, ignoring `count`
- [ ] 5.4 Parse `Retry-After` as integer seconds plus one, defaulting to 65, never the HTTP-date form; return the delay rather than sleeping
- [ ] 5.5 Implement `ScrapeClient` with **1-indexed** pagination, `IsLast` termination for `/relation-list` and empty-array termination for `/follower`/`/following`
- [ ] 5.6 Implement `BaseUrlResolver`: treat only a cross-registrable-domain redirect as unreachable, accept plain text or `{"url":…}`, validate HTTPS and bare origin, support a manual override
- [ ] 5.7 MockWebServer tests for every `RelationResult` branch including `4` and both 429 variants
- [ ] 5.8 MockWebServer tests for pagination termination on both endpoint families, and a test asserting the first request uses `pageIndex=1`
- [ ] 5.9 Verify `./gradlew :eksi:client:testDebugUnitTest` green

## 6. core:database

- [ ] 6.1 Define `RelationUserEntity` keyed `(listType, userId)` with `nick`, `addedAt`, `lastSeenAt`, `registrationDate`, `isFollowCurrentUser`, `isBuddy`
- [ ] 6.2 Define `ListSyncStateEntity` carrying a page cursor, replacing the `partial*Users` chunking
- [ ] 6.3 Define `RegistrationDateCacheEntity` with a 30-day TTL and a trim query
- [ ] 6.4 Define `OperationCheckpointEntity` and `CompletedOperationEntity` ready for the engine, unused for now
- [ ] 6.5 Define `AuthorListEntity` and `TelemetryOutboxEntity`
- [ ] 6.6 Write `Flow`-returning DAOs; expose counts only as `COUNT(*)`, never a stored column
- [ ] 6.7 Enable `exportSchema` and commit `android/core/database/schemas/`
- [ ] 6.8 Instrumented tests: upsert idempotence on the composite key, count-follows-content, TTL expiry
- [ ] 6.9 Confirm the CI schema-drift guard fails on an uncommitted schema change

## 7. core:datastore

- [ ] 7.1 Define the config proto: eight booleans from `config.js`, `eksiSozlukUrl`, repeated `DateFilterRule`
- [ ] 7.2 Define the identity proto: `clientUid`, `firstRunAt`, `consentVersion`
- [ ] 7.3 Implement `ConfigRepository` and `IdentityRepository` over Proto DataStore with documented defaults
- [ ] 7.4 Keep the shared API key in `BuildConfig`, never DataStore
- [ ] 7.5 Tests for first-run defaults and round-tripping a rule list

## 8. Close out

- [ ] 8.1 `./gradlew build` green across all modules
- [ ] 8.2 Extend `check.yml` to run every module's unit tests
- [ ] 8.3 Re-verify the extension is unbroken: `cd frontend/app && npm run check && npm run package`
- [ ] 8.4 Confirm `git diff --stat -- frontend/app/assets/` is empty
- [ ] 8.5 Confirm `android/version.json` and the `versionCode` derivation are unchanged
- [ ] 8.6 `openspec validate android-foundations` clean
