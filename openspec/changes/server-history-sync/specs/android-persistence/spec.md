## MODIFIED Requirements

### Requirement: Configuration is structured and typed

Config SHALL use a **typed** DataStore — `DataStore<T>` with a custom serializer —
not Preferences, because `dateFilterRules` is a repeated structured value
(`config.js:59-71`) and Preferences would mean hand-parsing JSON out of a string
key. It SHALL carry the nine booleans from `config.js`, the base URL, and the
rule list. Install identity SHALL live in a separate store.

The serializer SHALL be kotlinx-serialization rather than protobuf. Both satisfy
"typed and structured"; kotlinx-serialization is already a dependency for the
three JSON endpoints, needs no `protoc` toolchain in the build, and keeps the
schema declared in Kotlin next to the code that uses it.

**Telemetry defaults SHALL match the extension**: `sendData` and `sendLog` both
default to `true`, and `author_list` is transmitted in plaintext. This is a
deliberate decision, not an oversight, and SHALL NOT be quietly reversed.

**`syncHistory` SHALL default to `true`**, mirroring `config.js`, for the same
reason: history that only exists for the few who enable it by hand cannot answer
"who is blocked most". It is independent of `sendData` — either can be off while
the other is on. While it is on, the run report sends `author_list` empty (with
`author_list_size` unchanged) because the history ledger already carries every
target with its outcome; while it is off, `author_list` is sent in full as below.

The alternatives were weighed and declined. Hashing `author_list` would empty the
admin's `most_banned`, `most_banned_unique` and `EksiSozlukUserStatView` views
(`api/views.py:44-65`), which rank users by plaintext identity. Defaulting off
would make the dashboard show the Android client as near-dead regardless of real
usage, since almost nobody enables telemetry manually.

**Consequences that the Play submission must carry.** `author_list` contains up
to 10,000 nicknames and ids of people who are not users of this app and cannot
opt out. The Data Safety form therefore declares *User IDs* as collected, sent
off-device, and **not** optional, alongside *App activity*, *Device or other IDs*
(`client_uid`) and *Diagnostics* (the `log` field). History sync sends the same
classes; the form's retention answer is "until the user deletes it", and deletion
is offered in the app ("sunucudaki verilerimi sil"). A privacy policy enumerating
both endpoints, the exact fields, and retention is mandatory. Under KVKK and
GDPR the third-party identifiers are the exposed surface, and that exposure
already exists in the shipped extension — this change does not create it, but it
does extend it to a second platform.

The API key SHALL NOT be stored in DataStore; it belongs in `BuildConfig`. The
history device token SHALL live in the install-identity store, not in config, so
resetting settings does not detach the device from its account.

#### Scenario: Defaults on first run

- **WHEN** config is read before anything is written
- **THEN** documented defaults are returned rather than an error

#### Scenario: Telemetry is on out of the box

- **WHEN** a fresh install reads config
- **THEN** `sendData` and `sendLog` are both `true`, matching `config.js:25-26`

#### Scenario: History sync is on out of the box

- **WHEN** a fresh install reads config
- **THEN** `syncHistory` is `true`, matching `config.js`

#### Scenario: The user can still turn it off

- **WHEN** the user disables `sendData`
- **THEN** no action telemetry is transmitted, and the setting persists across restarts

## ADDED Requirements

### Requirement: History events commit with the checkpoint

Binds: **Android** only. The extension has no transactional store and accepts that a crash between
the request and the outbox write can lose one event.

History events SHALL be written to a `history_outbox` table in the same Room transaction as the
`operation_checkpoint` update that records the target as processed, so a crash leaves either both
or neither. The task's `task_id` SHALL be the checkpoint's `operationId`. Outbox rows SHALL be
deleted only after the server acknowledges their `event_id`, and SHALL NOT be abandoned after a
retry count — unlike `telemetry_outbox` — because history is meant to be complete.

The table SHALL be added by a Room migration from schema version 2, with the exported schema
committed, together with `history_task` (what the server was last told about each task this
device owns), `remote_task` (other devices' tasks as last pulled) and `history_sync_state` (the
pull cursor and backoff). `queued_task` and `completed_operation` SHALL gain a nullable `taskId`,
and `completed_operation` a `stopped` flag, so one task keeps one id from the queue to the
archive.

Task states SHALL be derived from `queued_task`, `operation_checkpoint` and the last seven days of
`completed_operation` on each sync pass, not reported at each change: a task can leave the queue
in more ways than any one hook would cover, and a path that forgot to report would leave it
waiting on every other device. A task this device announced that is in none of the three reads as
`CANCELLED`, so taking a queued run and writing its checkpoint SHALL happen in one transaction.

#### Scenario: Process death after the request

- **WHEN** the process dies after a block request succeeds but before the checkpoint commits
- **THEN** after restart the target is re-processed and exactly one event exists for it

#### Scenario: Server unreachable for days

- **WHEN** the backend is unreachable for three days
- **THEN** every event is still in `history_outbox` and uploads once it returns
