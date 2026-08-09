## ADDED Requirements

### Requirement: The app explains itself without leaving it

A help screen SHALL be reachable from the browser's top bar, alongside listeler
and ayarlar, covering how to act from the browsing UI, what each bulk operation
does, how the date filter and its rules behave, what the lists screen offers,
and what the author list accepts.

That bar is this app's equivalent of the extension's popup, where "Ayarlar ve
Yardım" sits beside the operations entry rather than inside it. Help SHALL have
exactly one entry point: a destination reachable two ways teaches neither.

The extension's guide SHALL be the source of the content but not of its shape.
`faq.html` describes a popup, two browser tabs and an "Ayarlar ve Yardım"
destination, none of which exist here — transcribing it would document a
different program. Where the app's navigation differs, the help describes the
app.

Where behaviour genuinely differs from the extension, the help SHALL say so
rather than describing the extension. The date filter is the live example: it is
on by default here and off there, and it requires every enabled rule to pass
where the extension blocks anyone who matched none. A user who read the
extension's documentation and applies it here would predict the opposite
behaviour.

The screen SHALL be text the platform can scale and a screen reader can read. The
extension's guide leans on screenshots of a browser UI, which would be wrong
pictures of the wrong program.

#### Scenario: Help is reachable

- **WHEN** the user is browsing
- **THEN** the help screen opens from the top bar, without going through Settings first

#### Scenario: Help has one door

- **WHEN** the app is searched for entry points to the guide
- **THEN** exactly one is found

#### Scenario: Help describes this app

- **WHEN** the help describes how to start an operation
- **THEN** it names this app's screens, not the extension's popup or tabs

#### Scenario: A divergence is documented as one

- **WHEN** a documented behaviour differs from the extension's
- **THEN** the help states the app's behaviour and that it differs

### Requirement: An upgrading user is told what changed

On the first launch after an install or an upgrade, the app SHALL show the
current version and its release notes, once per version.

The extension opens `welcome.html` on both INSTALL and UPDATE
(`background.js:1095-1101`) and `changelog.js` keys notes by version, so its
users are told. An Android user gets an unattended Play update and, without
this, no account of what changed.

Notes SHALL be keyed by version and SHALL degrade to a generic line when a
version has no entry, exactly as `changelog.js` does with `fallbackNote`. A
release must never be blocked on someone remembering to write a note, and a
missing note must never be shown as a blank screen.

The version already shown SHALL be recorded, so the notes appear once and not on
every launch. A first install SHALL be treated as an upgrade from nothing and
see the notes for the version it installed.

The screen SHALL be dismissible and SHALL never gate access to the app. It is an
announcement, not a consent gate — consent was ruled out deliberately, as parity
with an extension that has none.

#### Scenario: First launch after an upgrade

- **WHEN** the app starts and the running version differs from the last one shown
- **THEN** the version and its notes are shown, and the running version is recorded

#### Scenario: Every launch after that

- **WHEN** the app starts and the running version has already been shown
- **THEN** nothing is shown and the app opens as usual

#### Scenario: A version with no notes

- **WHEN** the running version has no entry
- **THEN** a generic line is shown rather than an empty screen or nothing at all

#### Scenario: The notes are not a gate

- **WHEN** the notes are shown
- **THEN** dismissing them opens the app, and no feature is withheld until they are read
