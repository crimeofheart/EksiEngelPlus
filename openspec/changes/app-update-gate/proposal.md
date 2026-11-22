## Why

There is no way to make an installed copy update. Store users usually get new
versions on their own, but sideloaded APKs never do, Firefox lets users switch
automatic updates off, and an old build that misreads Ekşi's markup keeps
blocking or muting the wrong people until its owner happens to update. The
maintainer needs one lever, pulled whenever they choose, that stops old or bad
builds until they are updated.

OpenOTT (`core/gate`, `app/gate`) and SportGuide TV (`app/.../gate`) already
solve this with a signed update policy. This change ports that mechanism.

## What Changes

- A signed update policy at `https://eksiengelplus.duzgun.org/policy/app-v1.json`:
  ES256 envelope `{"alg","payload","sig"}`, the format OpenOTT and SportGuide TV
  use, with one section per distribution channel (`github`, `play`, `chrome`,
  `firefox`) carrying `minVersionCode`, `revokedVersionCodes`,
  `latestVersionCode` and, for `github`, the APK's `apkUrl` and `sha256`.
  Version codes are `major*10000 + minor*100 + patch` everywhere, the formula
  `android/app/build.gradle.kts` already uses.
- **Android:** a `distribution` flavor dimension. `github` (the sideloaded APK)
  downloads the APK the policy names, checks its SHA-256 and opens the system
  installer. `play` (the AAB) uses Google Play's in-app update and never
  installs anything itself, as Play requires. A blocked build shows only a
  screen with the maintainer's message, **Güncelle**, **Tekrar dene** and
  **Kapat**; new operations are refused and a queued or resumed run parks as
  PAUSED instead of starting. A newer allowed build is offered without blocking.
- **Extension:** the background fetches and verifies the same policy with
  WebCrypto. A blocked build refuses to start operations, the popup shows the
  message and **Güncelle**, which asks the browser to update
  (`runtime.requestUpdateCheck`) and opens the store page when it cannot.
- **Backend:** Django serves the policy and the hosted APK from a release
  directory outside the checkout, so a deploy never touches them.
- **Tooling:** `tools/set-min-version.sh` signs and publishes the policy and,
  with `--apk`/`--release`, uploads the APK the Update button installs.

`frontend/app/` runtime code **is touched**: `updateGate.js` is new, and
`background.js` and `popup.js` consult it. The change is additive: with no
policy published, or an unreachable server, nothing is blocked.

## Non-goals

- Tamper detection and the offline-limit rule of OpenOTT/SportGuide TV. Users of
  a blocking tool have no reason to dodge an update, and the policy host is a
  home server whose outages must never lock everyone out.
- A policy expiry that forces re-signing. `expiresAt` is honoured when present,
  but the tool does not set one by default.
- Server-side refusal of blocked builds on the sync API.
- Automating the policy from CI. Signing stays on the maintainer's machine.

## Capabilities

### New Capabilities
- `app-update-gate`: a signed policy stops builds below a minimum or in a
  revoked list until updated, on both clients.

### Modified Capabilities
None.

## Impact

Android: `app` (new `gate` package, flavors, GateActivity, Play in-app update
dependency on `play` only, `REQUEST_INSTALL_PACKAGES` and a FileProvider on
`github` only), `ops/runtime` (`RunGate` hook in `OperationWorker`).
CI: `release.yml` builds `bundlePlayRelease` and `assembleGithubRelease`.
Extension: `assets/js/updateGate.js`, `background.js`, `popup.js`, `popup.html`.
Backend: `api/update_policy.py`, two routes, `RELEASE_DIR` setting.
Docs: `CLAUDE.md` "Forcing an update".
