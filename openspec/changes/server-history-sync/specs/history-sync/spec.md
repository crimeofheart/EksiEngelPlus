## ADDED Requirements

### Requirement: Sync is controlled by `syncHistory`, on by default

Binds: **extension** (`config.js`) and **Android** (`Config.kt`), mirrored field for field.

Both clients SHALL expose a `syncHistory` boolean defaulting to `true`, independent of
`sendData`. While `false` the client SHALL NOT enrol, push, pull or write to the history outbox,
and SHALL empty any outbox it already holds. Turning it back on SHALL resume under the same
account; events from the period it was off are not recorded.

#### Scenario: Fresh install
- **WHEN** a user installs either client and changes no setting
- **THEN** `syncHistory` is `true` and the device enrols on its first run

#### Scenario: Turned off with pending events
- **WHEN** the outbox holds 40 unsent events and the user turns `syncHistory` off
- **THEN** the outbox is emptied, nothing is sent, and the device token is kept

#### Scenario: Independent of diagnostics
- **WHEN** `sendData` is `false` and `syncHistory` is `true`
- **THEN** history syncs and `/api/action/` receives nothing

### Requirement: Devices enrol anonymously and authenticate with a device token

Binds: **backend**, **extension**, **Android**.

`POST /api/sync/enroll` (with the existing `X-API-Key`) SHALL create an account and a device and
return a device token once. The token SHALL be 256 bits of randomness, stored server-side only as
its SHA-256 hash, and sent as `Authorization: Device <token>`. Every other `/api/sync/` route
SHALL require a valid, unrevoked device token and SHALL act only on that device's account.

#### Scenario: Token never stored in plaintext
- **WHEN** the database is inspected after enrolment
- **THEN** no column holds the token itself

#### Scenario: Cross-account read
- **WHEN** a device of account A requests a task id belonging to account B
- **THEN** the server answers 404, not 403, so ids cannot be probed

### Requirement: Devices link by pairing code or by synced browser storage

Binds: **backend**, **extension**, **Android**.

An enrolled device SHALL be able to request a pairing code: 8 characters of Crockford base32,
single use, valid 10 minutes, stored hashed. Another device presenting the code SHALL create a
pending request and spend the code; nothing SHALL move until a device of the code's account
approves the request. Approving SHALL move the claimer into the code's account, re-parent the
tasks, events and join keys of its previous account and delete that account. Refusing, or 10
minutes without a decision, SHALL leave both accounts unchanged. The claimer SHALL learn the
outcome by polling its request.

Before claiming, both clients SHALL warn that this device's history moves to the code's account.
While a code is shown, the code's device SHALL list the pending requests with approve and refuse.
Both clients SHALL carry a short pairing guide.

The extension SHALL also write a join key to `chrome.storage.sync`, so another install of the
extension on the same synced browser profile enrols straight into that account with its own
device token. Two installs SHALL never share one device token. Each browser profile SHALL hold its
own key, and the account SHALL accept every one of them: pairing and device removal SHALL never
replace the key another profile holds. A claimer whose key was revoked SHALL receive a new one
once, on its first poll after approval.

Code claims SHALL be throttled per IP so the 40-bit code space cannot be searched.

#### Scenario: Phone joins laptop
- **WHEN** the laptop shows code `7K3M-QX9A`, the phone enters it within 10 minutes, and the laptop approves the request
- **THEN** the phone sees the laptop's tasks on its next pull

#### Scenario: A code read off the screen
- **WHEN** someone else enters the code first
- **THEN** the laptop shows their request, nothing moves unless it is approved, and the code cannot be entered again

#### Scenario: Other profiles keep their key
- **WHEN** an account holds Chrome profiles A and B and a phone pairs into it
- **THEN** a new install on either profile still enrols into the account without a code

#### Scenario: Expired code
- **WHEN** the code is entered after 10 minutes
- **THEN** the claim fails and neither account changes

#### Scenario: Second Chrome install on a synced profile
- **WHEN** a user installs the extension on a second computer signed into the same Chrome profile
- **THEN** it enrols into the existing account without a code, as a separate device

### Requirement: Linked devices can be listed and removed

Binds: **backend**, **extension**, **Android**.

A device SHALL be able to list the account's active devices (label, client, last seen, which one is
itself) and remove any of them. A removed device's token SHALL stop working and the join key its
profile holds SHALL be revoked. When that key is also the remover's, the remover SHALL receive a
new one; otherwise its key SHALL stand. Removing itself SHALL sign the device out, so its next sync
enrols into a new account.

#### Scenario: Unknown device
- **WHEN** the user removes a device they do not recognise
- **THEN** its next request is refused with 401 and new installs on its browser profile no longer join the account

### Requirement: Uploads are bounded per device

Binds: **backend**, **extension**, **Android**.

The server SHALL accept at most 20,000 new events per device per Istanbul day, more than Ekşi's
action rate allows, and SHALL report `capped` when it stops. Events already stored SHALL still be
acknowledged, so a resend is never stuck. A capped client SHALL stop pushing until its next pass
and keep the rest in its outbox. Task `params` over 20 kB SHALL be replaced by a marker. A takeover
SHALL only follow links on the Ekşi Sözlük host.

#### Scenario: Flood
- **WHEN** one device pushes 30,000 events in a day
- **THEN** 20,000 are stored and the rest wait in its outbox for the next day

### Requirement: Push and pull use a server cursor

Binds: **backend**, **extension**, **Android**.

`POST /api/sync/push` SHALL accept up to 100 task upserts, 500 events and 1000 nicks per request
and return the ids it accepted. A device timestamp before 2015 or more than a day ahead SHALL be
replaced by the time it arrived rather than refused: refusing it would refuse the whole batch on
every retry, and one wrong clock would stop a device from syncing at all. `GET /api/sync/pull?cursor=<n>` SHALL return task changes for the device's
account with a change sequence greater than `n`, in order, at most 500 per page, with
`next_cursor` and `has_more`. The cursor SHALL be a server-assigned, monotonically increasing
integer; device clocks SHALL NOT be used to decide what is new.

Events SHALL NOT be replicated to devices. `GET /api/sync/history?before=<event_id>&limit=<n>`
SHALL return the calling device's own account's events, newest first, and nothing about any other
account. No client UI SHALL use it in this release; it exists so the data a person sent can be
returned to them, and so a later client can show it without a backend change.

#### Scenario: Device offline for a week
- **WHEN** a device with cursor 1200 pulls after 3000 changes
- **THEN** it receives them in 6 pages and ends with the newest cursor

#### Scenario: Wrong clock
- **WHEN** a device's clock is a day behind
- **THEN** it still receives every change made since its last pull

### Requirement: Only the owning device executes a task

Binds: **backend**, **extension**, **Android**.

Each task SHALL have exactly one owner device and an `epoch`. Only the owner SHALL execute it.
Other devices SHALL show it read-only with its owner's label and progress, and MAY:

- request cancellation, which the owner SHALL honour cooperatively on its next pull and then
  report as `CANCELLED`;
- take it over (`POST /api/sync/tasks/<id>/takeover`), allowed while the task is `QUEUED`, or
  `RUNNING` with an owner unseen for 15 minutes. Takeover SHALL increment `epoch` and start the
  task over as `QUEUED` with zero counts.

A device SHALL offer takeover only for a task it can run. The extension runs a task whose params
carry `retry` (a single-target extension task); Android runs a task whose params carry
`android_request` (an Android task whose request fits in 16 KB). Cross-client takeover is not
offered: the two clients describe a run in different shapes, and mapping one to the other is
left for a later change. Cancel works across clients.

The server SHALL refuse an upsert carrying an epoch lower than the task's, or sent by a device
that does not own it, reporting `conflict` for that task in the push response. The previous owner
SHALL then drop its local copy without executing further.

A takeover of a `RUNNING` task SHALL restart it on the new owner with
`enableOnlyRequiredActions`, so targets already in the requested state are skipped.

#### Scenario: Cancel from the phone
- **WHEN** the phone cancels a task running on the laptop
- **THEN** the laptop stops within one pull interval and the task ends `CANCELLED` on both

#### Scenario: Laptop closed mid-run
- **WHEN** the laptop has been unseen for 20 minutes with a task `RUNNING`, and the phone takes it over
- **THEN** the phone owns the task at epoch +1, and if the laptop wakes and pushes progress the push reports `conflict` for it and the laptop stops

#### Scenario: Takeover refused
- **WHEN** the owner was seen 2 minutes ago and the task is `RUNNING`
- **THEN** takeover is refused and the task is unchanged

### Requirement: Sync never blocks or fails an operation

Binds: **extension**, **Android**.

Events and task updates SHALL go through a local outbox. A network failure, timeout or 5xx SHALL
leave them in the outbox for a later attempt with backoff and SHALL NOT pause, slow or fail the
operation that produced them. The extension outbox SHALL be bounded; on overflow the oldest
events are dropped and the number dropped is sent with the next push as a gap marker.

#### Scenario: Server down during a run
- **WHEN** the backend is unreachable for an entire 500-target run
- **THEN** the run completes at its normal pace and its events upload once the server returns

### Requirement: A user can delete their server data

Binds: **backend**, **extension**, **Android**.

`DELETE /api/sync/account` SHALL delete the account and, in the same transaction, all its devices,
tasks, events, relation-state rows and daily actor-set rows, and subtract its events from the
daily tally. Both clients SHALL offer this as a separate, confirmed action ("Sunucudaki verilerimi
sil"), not as a side effect of the toggle. After deletion the client SHALL clear its token and join
key and set `syncHistory` to `false`.

Deletion covers what the account sent through sync. It does not cover `/api/action/` run reports
or the `LEGACY` events derived from them, which are not linked to any device. Those are removed by
the admin on request, as today, and the privacy page SHALL say so.

#### Scenario: Delete
- **WHEN** a user confirms deletion
- **THEN** no row referencing their account remains, and their synced events no longer count toward any ranking or tally

#### Scenario: Run reports are separate
- **WHEN** a user who also sent run reports with `syncHistory` off deletes their account
- **THEN** the legacy account built from those reports is unchanged

#### Scenario: Toggle is not delete
- **WHEN** a user only turns `syncHistory` off
- **THEN** their server data is unchanged
