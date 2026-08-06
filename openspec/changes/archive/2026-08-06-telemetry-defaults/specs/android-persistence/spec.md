# android-persistence

## MODIFIED Requirements

### Requirement: Configuration is structured and typed

Config SHALL use a **typed** DataStore — `DataStore<T>` with a custom serializer —
not Preferences, because `dateFilterRules` is a repeated structured value
(`config.js:43-55`) and Preferences would mean hand-parsing JSON out of a string
key. It SHALL carry the eight booleans from `config.js`, the base URL, and the
rule list. Install identity SHALL live in a separate store.

The serializer SHALL be kotlinx-serialization rather than protobuf. Both satisfy
"typed and structured"; kotlinx-serialization is already a dependency for the
three JSON endpoints, needs no `protoc` toolchain in the build, and keeps the
schema declared in Kotlin next to the code that uses it.

**Telemetry defaults SHALL match the extension**: `sendData` and `sendLog` both
default to `true`, and `author_list` is transmitted in plaintext. This is a
deliberate decision, not an oversight, and SHALL NOT be quietly reversed.

The alternatives were weighed and declined. Hashing `author_list` would empty the
admin's `most_banned`, `most_banned_unique` and `EksiSozlukUserStatView` views
(`api/views.py:44-65`), which rank users by plaintext identity. Defaulting off
would make the dashboard show the Android client as near-dead regardless of real
usage, since almost nobody enables telemetry manually.

**Consequences that the Play submission must carry.** `author_list` contains up
to 10,000 nicknames and ids of people who are not users of this app and cannot
opt out. The Data Safety form therefore declares *User IDs* as collected, sent
off-device, and **not** optional, alongside *App activity*, *Device or other IDs*
(`client_uid`) and *Diagnostics* (the `log` field). A privacy policy enumerating
both endpoints, the exact fields, and retention is mandatory. Under KVKK and
GDPR the third-party identifiers are the exposed surface, and that exposure
already exists in the shipped extension — this change does not create it, but it
does extend it to a second platform.

The API key SHALL NOT be stored in DataStore; it belongs in `BuildConfig`.

#### Scenario: Defaults on first run

- **WHEN** config is read before anything is written
- **THEN** documented defaults are returned rather than an error

#### Scenario: Telemetry is on out of the box

- **WHEN** a fresh install reads config
- **THEN** `sendData` and `sendLog` are both `true`, matching `config.js:25-26`

#### Scenario: The user can still turn it off

- **WHEN** the user disables `sendData`
- **THEN** no action telemetry is transmitted, and the setting persists across restarts
