## Context

OpenOTT (`core/src/main/kotlin/org/openott/core/gate/Gate.kt`, `app/src/main/kotlin/org/openott/app/gate/UpdateGate.kt`)
and SportGuide TV (`app/src/main/java/com/sportguidetv/app/gate/`) gate their
apps on a signed policy. Their `Gate` is a pure function over a persisted
`GateState`; it is ported here minus the offline-limit and tamper rules.

## Decisions

**One policy, four channels.** The extension and the app ship one version
number, so one file serves both. Channels exist because the stores do not move
in step (CLAUDE.md "Store submission"): a Chrome minimum raised before Chrome
review passes would block users with nothing to install, so each channel's
minimum is raised on its own when its store serves the version.

**Version codes.** `major*10000 + minor*100 + patch`, already the Android
`versionCode` (`android/app/build.gradle.kts`, `productVersionCode`). The
extension derives the same number from `runtime.getManifest().version`, so the
tool takes `0.6.5` and writes `605` for all four.

**Signature.** ES256 over the decoded payload, DER signature, as both sibling
projects. Android verifies with `SHA256withECDSA`. WebCrypto's ECDSA expects the
raw `r||s` form, so `updateGate.js` converts DER to 64 raw bytes before
`crypto.subtle.verify`. Two keys are built in (primary and offline backup).
Transport is not pinned: the signature is the trust anchor.

**Flavors.** Google Play forbids self-updating and restricts
`REQUEST_INSTALL_PACKAGES`, and the AAB and APK are built from the same module
today (`release.yml`, "Build"). A `distribution` dimension splits them: `github`
carries the permission, a FileProvider and `ApkInstaller`; `play` carries the
Play in-app update library and `StoreUpdate`. Shared code calls a flavor-specific
`Updater`. CI builds `bundlePlayRelease` and `assembleGithubRelease`, so asset
names on the GitHub Release are unchanged.

**Where the gate is enforced on Android.** `BrowserActivity` asks on every
resume and shows `GateActivity` when blocked, and its `enqueue` refuses.
`OperationWorker.runOperation()` asks through a `RunGate` interface bound
optionally (`@BindsOptionalOf`) in `OpsModule`, so `devharness` — a second Hilt
app with no gate — keeps compiling; the app binds `UpdateGate`. A blocked run is
recorded `PAUSED`, the state a user pause uses, so the resume bar offers it
after the update. No new `OperationState`.

**Where the gate is enforced in the extension.** `processHandler` in
`background.js` is the one function every operation runs through, whatever
message enqueued it, so it checks first and ends the run with a notice.

**Hosting.** Django serves `RELEASE_DIR/policy/app-v1.json` and
`RELEASE_DIR/download/eksiengelplus-<version>.apk`; `RELEASE_DIR` defaults to
`/var/lib/eksiengelplus/release`, outside `/var/www/EksiEngelPlus`, because the
host deploys with `git pull --ff-only` and a file the tool wrote inside the
checkout would make that fail. GitHub Release assets are not used as the APK URL:
the `prune` job deletes them from every release but the newest, which would break
the Update button the moment the next version is tagged.

## Risks

- Losing the private key means no new policy can be signed; the last one stays
  in force. Both keys must be backed up (age-encrypted in chezmoi, as for the
  sibling projects).
- A Play minimum raised before a 100 % rollout blocks after the 7-day grace.
  Raise `--min-play` only after Play serves the version to everyone.
