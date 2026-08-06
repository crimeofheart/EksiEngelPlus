## 1. Defaults

- [x] 1.1 Set `EksiConfig.sendData` and `EksiConfig.sendLog` to default `true`, replacing the comment block that justified the off-by-default choice
- [x] 1.2 Update `ConfigTest` to assert both are `true` on a fresh config, with the reasoning in the test so the assertion is not mistaken for an oversight
- [x] 1.3 Add a test proving a user-disabled `sendData` round-trips through the serializer, so the toggle demonstrably works

## 2. Verify

- [x] 2.1 `./gradlew :core:datastore:testDebugUnitTest` green
- [x] 2.2 `./gradlew build` green across all modules
- [x] 2.3 Re-verify the extension is unbroken: `cd frontend/app && npm run check`
- [x] 2.4 `openspec validate telemetry-defaults` clean, then archive
