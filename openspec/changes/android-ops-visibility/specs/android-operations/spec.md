## ADDED Requirements

### Requirement: A run is live from the moment it begins

The worker SHALL mark its checkpoint `RUNNING` before it performs any network
work, without disturbing the cursor. State MUST NOT wait on the first checkpoint
write, which happens only after the first action lands.

Binds the Android client.

#### Scenario: A run opening with a cooldown is not reported as not-started

- **GIVEN** a run whose first action waits on the rate limit
- **WHEN** İşlem durumu is opened during that wait
- **THEN** the run appears under `süren ve bekleyen`, not under `sıradakiler` as
  `başlamadı`

#### Scenario: A run in its opening wait blocks the queue behind it

- **GIVEN** a run that has begun but not yet checkpointed
- **WHEN** a second request arrives
- **THEN** the live count includes the run, and the second request is queued
  rather than started alongside it

#### Scenario: Marking a run started preserves where it was

- **WHEN** a resumed run is marked `RUNNING`
- **THEN** its cursor, processed counts and `startedAt` are unchanged, so the
  resume continues from the recorded position

### Requirement: Signals are honoured from anywhere in the run

Pause, stop and budget signals SHALL park the run wherever it stands, including
when raised from inside a rate-limit wait — which happens below the target loop,
in the retry path. A signal MUST NOT escape the runner: doing so reports the
user's own Durdur back to them as `İşlem başarısız`.

Saving the parked state SHALL tolerate the checkpoint itself signalling. The
context raises a stop when the row is gone, which is how a cancel reaches a live
run, and a signal raised while handling a signal would become the same spurious
failure.

Binds the Android client.

#### Scenario: Stopping inside a wait parks rather than fails

- **WHEN** a stop signal is raised from inside the permit wait
- **THEN** the runner returns `STOPPED` and checkpoints the index it had reached,
  and no failure is reported to the user

#### Scenario: Cancelling a live run is not a failure

- **GIVEN** a run whose checkpoint row has been deleted by a cancel
- **WHEN** the runner tries to park and the checkpoint signals in turn
- **THEN** the run ends as `STOPPED`

### Requirement: The queue drains whenever nothing is live

The queue SHALL be drained on any transition to an idle system, not only when a
run reaches a terminal state. A queue that outlives the process — the app
killed, or reinstalled — has nothing left to trigger it, so startup
reconciliation MUST check.

The drain SHALL start its run directly rather than re-entering the enqueue path,
which consults the live check and could return the run to the queue it came from.

Binds the Android client.

#### Scenario: A queue survives a reinstall and still runs

- **GIVEN** queued operations and no live run, after the process has restarted
- **WHEN** the app starts
- **THEN** the next queued operation begins, rather than waiting for a run that
  will never finish

### Requirement: Cancellation is never reported as failure

Code that reports a failure to the user SHALL NOT catch `CancellationException`.
`runCatching` catches `Throwable`, so leaving a screen mid-operation surfaced the
coroutine machinery's own bookkeeping as `işlem başarısız: Job was cancelled`.
Swallowing it also leaves a scope that never finishes unwinding.

Binds the Android client.

#### Scenario: Leaving a screen mid-import reports nothing

- **WHEN** a screen is closed while an import is running
- **THEN** the cancellation propagates and no failure message is shown

### Requirement: Every announcement of a run offers a way to it

Wherever the app tells the user a run has started or finished, it SHALL offer a
route to İşlem durumu. The screen the user is standing on shows no trace of a run
that was just handed off.

The route SHALL be an implicit intent private to the app, not a class reference:
`:feature:lists` depends on `:ops:runtime`, so naming the activity from the
notifier would invert that dependency. Because nothing checks the action string
and the intent-filter agree at compile time, and a mismatch is invisible at
runtime, an assembly test SHALL assert the action resolves.

Binds the Android client.

#### Scenario: The progress notification leads to the detail

- **WHEN** a run is in progress
- **THEN** the notification carries a `Göster` action, and tapping its body opens
  İşlem durumu

#### Scenario: The completion notification leads to the detail

- **WHEN** a run finishes
- **THEN** tapping `İşlem tamamlandı` opens İşlem durumu and dismisses the
  notification

#### Scenario: Queuing a run offers a way to see it

- **WHEN** a run is queued from a list screen
- **THEN** the message shown carries a `göster` action

### Requirement: A run states its size before it acts

The number of targets SHALL be published before the first action, not after it,
and the published counts — processed, total, successful, failed — SHALL be
recorded on the checkpoint row each time they change.

The notification reads them from the worker; İşlem durumu reads them from the
row, which nothing wrote between checkpoints. The two surfaces disagreed about
the same run: `0 / 1` against `0 / 0` before the size was known, then `8 / 13`
against `5 / 13` as the row moved in steps of five.

These writes are display only. The cursor stays the business of `checkpoint()`
alone, so a resumed run still restores the position it genuinely resumes from.
Otherwise a run waiting out its first cooldown reads `0 / 0 · API limiti
bekleniyor` — indistinguishable from a run against nobody, which is how a
genuine 37-follower run came to look like a minute spent on an empty one.

A run with no targets SHALL complete without consulting the pacer and without
writing a checkpoint. Nothing to do costs nothing.

Binds the Android client.

#### Scenario: The notification and the screen agree throughout

- **WHEN** a run has processed eight of thirteen targets, between checkpoints
- **THEN** the checkpoint row reads `8 / 13`, the same as the notification —
  not the `5 / 13` of the last checkpoint

#### Scenario: A run waiting for its first permit says what it will do

- **GIVEN** a run over 37 followers whose first action waits on the rate limit
- **WHEN** İşlem durumu is read during that wait
- **THEN** the row reads `0 / 37`, not `0 / 0`

#### Scenario: A run against nobody costs nothing

- **WHEN** an operation resolves zero targets — an entry with no favouriters, an
  author with no followers
- **THEN** it completes immediately, taking no permit and waiting no cooldown

### Requirement: The screen and the notification agree

İşlem durumu SHALL show the same rate-limit countdown the notification shows, for
the run it belongs to. A run in a wait MUST NOT read as stalled on one surface
while visibly ticking on the other.

The wait SHALL be held in memory, not persisted: it is worth exactly as long as
the worker it describes, and one restored after a process death would count down
a pause nothing is observing. It SHALL be cleared when the wait ends by any
route, including a signal.

Binds the Android client.

#### Scenario: The running row counts down

- **WHEN** a run is waiting on the rate limit
- **THEN** its row reads `… · API limiti bekleniyor 4 sn`, updating each second

#### Scenario: Stopping mid-wait leaves nothing counting down

- **WHEN** the user stops a run while it is waiting
- **THEN** the countdown is cleared rather than left ticking against a run that
  has ended

### Requirement: A run says which nick it is about

Every surface that names a run — İşlem durumu's three sections and the
notification — SHALL render it as the operation's name followed by the nick it
targets in brackets: `favlayanlar (coh)`. The nick SHALL be omitted, brackets and
all, when the run has no single subject: a sweep over the whole blocked list, or
a pasted list of forty names.

One name, in one place. The label SHALL live in `:ops:runtime`, which both the
screen and the notification can reach; `:feature:lists` cannot be reached from
the notification, which is why the same run was titled `favlayanlar` on the
screen and `FAV` in the shade.

`FAV` SHALL be named after the author whose entry was clicked, not the
favouriters it acts on — that is what the user picked and what they will
recognise. The bridge SHALL therefore send `authorName` on a fav enqueue.

The nick SHALL survive archival. `completed_operation` holds no request, and the
checkpoint that carried one is removed as the run is archived, so the nick is
written into `summaryJson`. Rows archived before this SHALL degrade to the bare
name rather than failing to render.

Binds the Android client.

#### Scenario: Three runs of the same kind are told apart

- **GIVEN** favlayanlar runs queued from three different entries
- **WHEN** İşlem durumu is opened
- **THEN** each row reads `favlayanlar (<nick>)` with the author of its own
  entry, rather than three identical rows

#### Scenario: The notification names the run the same way the screen does

- **WHEN** a run is in progress
- **THEN** the notification title reads `favlayanlar (coh)`, the same string the
  running row shows — not the `FAV` enum constant

#### Scenario: A finished run keeps its nick

- **WHEN** a run completes and moves into tamamlananlar
- **THEN** the history row still reads `favlayanlar (coh)`, though the request it
  came from has been deleted

#### Scenario: A run with no single subject shows no brackets

- **WHEN** tüm engelleri kaldırma runs, or a list of forty names
- **THEN** the row reads `tüm engelleri kaldırma` with nothing in brackets

### Requirement: A finished run is reported to the backend

The app SHALL post every completed and stopped run to `POST /api/action/` in the
payload shape `commHandler.js` uses — `{"action": {...}, "action_config": {...}}`
— authenticated with the `X-API-Key` header, so the endpoint needs no branch for
which client is calling.

The report SHALL carry the user's Ekşi identity as `eksi_engel_user`. The
serializer keys every record to one and rejects a report without it, so a run
that cannot be attributed SHALL be dropped rather than sent. Identity is resolved
once from the homepage and profile and cached, as `scrapingHandler.js:73-104`
caches it.

The report SHALL name its origin as `client: "ANDROID"`. Neither the payload nor
the user agent distinguished the app from the extension — the WebView reports a
mobile Chrome UA — so the server had no way to tell them apart.

The shared API key SHALL be a build-time constant with an env override, not a
value read at runtime from a source no caller populates.

`author_list` SHALL carry every target the run acted on, matching the decision
recorded in `android-persistence`. Only the slice a single worker ran is
reported: the buffer does not survive process death, and persisting targets for
the sake of a report would cost more than the report is worth.

Binds the Android client and the Django backend.

#### Scenario: A completed run reaches the server against the user's account

- **WHEN** a run finishes with `sendData` on and a resolved identity
- **THEN** the action appears on the server keyed to that Ekşi user, with the
  run's planned, performed and successful counts

#### Scenario: An app run is distinguishable from an extension run

- **WHEN** the app and the extension both report
- **THEN** the rows differ by `client`, and the admin can filter on it

#### Scenario: A logged-out install reports nothing rather than something wrong

- **WHEN** a run finishes but the identity cannot be resolved
- **THEN** no report is written, rather than one filed against an empty user

#### Scenario: The extension's payload is unaffected

- **WHEN** the shipped extension posts its existing body, with no `client` field
- **THEN** it validates as before and is recorded as `EXTENSION`
