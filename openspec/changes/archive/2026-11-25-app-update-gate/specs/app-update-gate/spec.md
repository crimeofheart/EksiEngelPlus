## ADDED Requirements

### Requirement: Both clients obey one signed update policy

Binds: extension and Android.

Both clients SHALL fetch `https://eksiengelplus.duzgun.org/policy/app-v1.json`
at start and at most every 6 hours while in use. The file SHALL be an ECDSA
P-256 / SHA-256 envelope `{"alg":"ES256","payload":"<base64 JSON>","sig":"<base64 DER>"}`
for app id `org.duzgun.eksiengelplus`, verified against two public keys built
into each client (a primary and an offline backup). An unsigned, altered,
wrong-app or expired policy, or one older than the policy already accepted,
SHALL be ignored and SHALL change nothing. Each client reads only its own
channel: `github` (sideloaded APK), `play` (Play build), `chrome`, `firefox`.
Version codes SHALL be `major*10000 + minor*100 + patch` of the version name on
every channel.

#### Scenario: Forged policy
- **WHEN** a policy raising the minimum is served without a valid signature from a built-in key
- **THEN** the client ignores it and keeps working

#### Scenario: Replayed old policy
- **WHEN** a validly signed policy older than the accepted one is served
- **THEN** the client ignores it and cannot lose a minimum or revocation that way

#### Scenario: No policy at all
- **WHEN** the server is unreachable or serves 404
- **THEN** nothing is blocked

### Requirement: Old or revoked builds stop until updated

Binds: extension and Android.

When the installed version code is below the channel's accepted minimum, or is
listed in its `revokedVersionCodes`, the client SHALL refuse to start any
operation and SHALL show the maintainer's message (Turkish, English fallback)
with a way to update. A minimum once accepted SHALL never go down on that
installation; a revocation SHALL follow the newest accepted policy so it can be
lifted.

On Android the blocked app SHALL show only a screen with the message,
**Güncelle**, **Tekrar dene** and **Kapat**, and a run that the worker would
start or resume SHALL be parked as PAUSED instead, so it can be resumed after
updating.

In the extension a blocked build SHALL end each operation before it touches Ekşi
and SHALL show the message and **Güncelle** in the popup.

#### Scenario: Minimum raised
- **WHEN** the `github` minimum is 605 and the installed APK is 0.6.4 (604)
- **THEN** the app shows only the update screen, and no operation starts

#### Scenario: One build revoked
- **WHEN** 605 is in `revokedVersionCodes` and 604 and 606 are not
- **THEN** only 0.6.5 is blocked

#### Scenario: Revocation lifted
- **WHEN** a newer policy no longer lists 605
- **THEN** 0.6.5 works again after its next check

#### Scenario: Queued run on a blocked build
- **WHEN** the worker picks up a queued run after the gate started blocking
- **THEN** the run is recorded PAUSED and no Ekşi request is made

#### Scenario: Blocked extension
- **WHEN** the `chrome` minimum is above the installed extension and the user starts a block from Ekşi
- **THEN** the operation ends before any Ekşi request and the popup offers Güncelle

### Requirement: The update comes from where the build came from

Binds: Android (`github`, `play`), extension.

The `github` flavor SHALL download the APK from the policy's `apkUrl` (https
only), verify its SHA-256 against the signed policy, and open the system
installer; a mismatch SHALL delete the file and say so, and a missing install
permission SHALL open the system setting for it. The `play` flavor SHALL NOT
declare `REQUEST_INSTALL_PACKAGES` and SHALL start Google Play's immediate
in-app update, opening the store page when Play has no update to offer; a
minimum raised before Play offers the version SHALL leave the app usable for 7
days. The extension SHALL call `runtime.requestUpdateCheck` where the browser
has it and reload once the update is ready, and SHALL open its store page
otherwise.

When a newer allowed version exists (`latestVersionCode`), the Android app
SHALL offer it without blocking.

#### Scenario: Sideloaded update
- **WHEN** the user taps Güncelle on the blocking screen of the APK build
- **THEN** the APK downloads, its hash matches, and the system installer opens

#### Scenario: Tampered download
- **WHEN** the downloaded APK's SHA-256 differs from the policy's
- **THEN** the file is deleted, nothing is installed, and the screen says the download was invalid

#### Scenario: Play build
- **WHEN** the user taps Güncelle on the Play build
- **THEN** Play's update flow or the Play listing opens; the app never downloads an APK

### Requirement: The maintainer publishes the policy with one command

`tools/set-min-version.sh` SHALL sign the policy with the maintainer's key,
refuse a key whose public half is not built into the clients, keep the last
policy in `backend/policy/app-v1.json` and merge unspecified values from it,
accept versions as `x.y.z` or as codes, upload the APK before any policy that
names it, publish the policy to the server's release directory, and read it
back from the public URL. Django SHALL serve the policy with
`Cache-Control: no-cache` and `Access-Control-Allow-Origin: *`, and SHALL serve
only files named `eksiengelplus-<version>.apk` under `/download/`.

#### Scenario: Force everyone onto 0.6.5
- **WHEN** the maintainer runs `tools/set-min-version.sh --release v0.6.5 --min 0.6.5`
- **THEN** the 0.6.5 APK is uploaded, the signed policy raises every channel's minimum to 605, and the served file matches what was signed
