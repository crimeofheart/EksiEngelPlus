## ADDED Requirements

### Requirement: Every worker the app schedules must be constructible

The `Application` SHALL implement `Configuration.Provider` and supply
`HiltWorkerFactory`, and the manifest SHALL remove `WorkManagerInitializer` so
that configuration is the one WorkManager uses.

Every worker in the app is a `@HiltWorker` with injected dependencies, and the
default `WorkerFactory` can only call a two-argument constructor. Without this,
work still enqueues and WorkManager still reports it, but each job fails the
moment it is picked up — the failure is silent, and the symptom is a button that
does nothing.

This binds the Android client. The extension has no equivalent.

#### Scenario: A scheduled job actually runs

- **WHEN** any worker is enqueued
- **THEN** WorkManager constructs it and the work executes, rather than failing at construction

#### Scenario: Enqueueing successfully is not evidence of anything

- **WHEN** verifying that a scheduled feature works
- **THEN** the check SHALL observe the work executing, because enqueueing and reporting a queued state both succeed even when no worker can be built

### Requirement: Components reached by intent are declared

Every `BroadcastReceiver`, `Service` and `Activity` the app dispatches to SHALL be
declared in the manifest of the module that owns it.

An explicit broadcast to an undeclared receiver is dropped silently: no
exception, no log, no delivery. The notification's pause and stop actions failed
this way, leaving a running operation with no controls at all.

#### Scenario: A notification action arrives

- **WHEN** the user taps an action on an operation notification
- **THEN** the receiver is resolved and the command is delivered

#### Scenario: Nothing dispatched is left undeclared

- **WHEN** a component is added that an intent targets
- **THEN** it is declared in its own module's manifest rather than the app's, so adding the module to a build is one dependency line

### Requirement: Work payloads survive the process boundary

Data handed to WorkManager SHALL be bounded and small. Anything whose size grows
with user input SHALL be persisted and referenced by id instead.

`Data` is capped at 10 KB and throws on the caller when exceeded. An operation
request carries every nick it targets, so a handful typed by hand fit and a list
imported from a CSV does not — the throw reached the UI and closed the app.

#### Scenario: A large target list is scheduled

- **WHEN** an operation is enqueued against a list of several thousand nicks
- **THEN** it schedules successfully, with the request stored in the database and only its id in the work request

#### Scenario: A resumed run finds its request

- **WHEN** a worker starts for an operation whose request was never in its input data
- **THEN** it reads the request from the stored checkpoint

### Requirement: Assembly is verified on a device, not inferred from unit tests

A feature that depends on the manifest, dependency injection into framework
components, or platform serialisation limits SHALL be exercised on a real
device or emulator before it is considered done.

Every defect this change fixes lived between modules that each had passing
tests. The existing suites construct objects directly and never traverse the
manifest, the Hilt-in-`Application` path, or WorkManager's limits, so no amount
of unit testing could have reached them.

#### Scenario: A scheduled feature is claimed complete

- **WHEN** work depending on WorkManager, a manifest entry, or injected framework components is reported done
- **THEN** evidence exists of it running on a device, not only of its parts passing in isolation
