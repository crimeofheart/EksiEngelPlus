## 1. Policy and tooling

- [x] 1.1 Generate the primary and backup ES256 policy keys (`~/.android/eksiengelplus-policy-key{,-backup}.pem`).
- [x] 1.2 `tools/set-min-version.sh`: sign, merge from `backend/policy/app-v1.json`, upload APK then policy, read back.
- [x] 1.3 Django: serve `policy/app-v1.json` and `download/eksiengelplus-<version>.apk` from `RELEASE_DIR`, with tests.
- [x] 1.4 Re-verify the extension builds: `cd frontend/app && npm run check && npm run package`.

## 2. Android

- [x] 2.1 Port `AppPolicy`/`PolicyCodec`/`Gate` (no offline limit, no tamper) with `UpdateGateTest`.
- [x] 2.2 `UpdateGate` (fetch, persist, Play grace), `GateActivity`, update-available snackbar in `BrowserActivity`.
- [x] 2.3 `distribution` flavors; `github`: `ApkInstaller`, FileProvider, `REQUEST_INSTALL_PACKAGES`; `play`: Play in-app update.
- [x] 2.4 `RunGate` in `ops/runtime`; `OperationWorker` parks a blocked run as PAUSED; `BrowserActivity.enqueue` refuses.
- [x] 2.5 `release.yml` builds `bundlePlayRelease` + `assembleGithubRelease`; unit tests and `lintGithubRelease`/`lintPlayRelease` pass.
- [x] 2.6 Re-verify the extension builds: `cd frontend/app && npm run check && npm run package`.

## 3. Extension

- [x] 3.1 `assets/js/updateGate.js`: fetch, WebCrypto verify (DER→raw), persisted state, decision; `scripts/test-update-gate.mjs`.
- [x] 3.2 `processHandler` refuses while blocked; alarm refresh; reload when an update is ready.
- [x] 3.3 Popup banner with Güncelle.
- [x] 3.4 Re-verify the extension builds: `cd frontend/app && npm run check && npm run package`.

## 4. Release

- [x] 4.1 CLAUDE.md "Forcing an update"; changelog entry for both platforms.
- [ ] 4.2 Deploy the backend, create `RELEASE_DIR`, publish a first policy with no minimum, and read it back from both clients.
- [ ] 4.3 Back up both keys (age-encrypted in chezmoi).
