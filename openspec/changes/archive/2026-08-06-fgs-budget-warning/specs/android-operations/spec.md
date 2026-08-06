# android-operations

## MODIFIED Requirements

### Requirement: Execution is chunked across days

The worker SHALL track foreground-service time against a soft budget below the
platform cap, checkpoint when it is exhausted, enter `PAUSED_BUDGET`, and schedule
a continuation for when the budget resets.

Android 15 caps a `dataSync` foreground service at roughly 6 hours per rolling 24
and then calls `onTimeout()`. At twelve actions/minute a 10,000-user run needs
about 14 hours. These cannot be reconciled, so multi-session execution is
structural.

Time SHALL count against the budget only while the foreground service holds the
process. With a visible activity the operation runs unconstrained, so a user who
leaves the app open finishes sooner.

**The client SHALL warn before the budget is spent, not only after.** At a
configurable fraction of the budget — default 80% — it SHALL post an actionable
notification stating the remaining work and offering to continue immediately by
opening the app. Tapping SHALL bring the app forward, at which point the
operation continues without consuming budget.

The warning SHALL fire at most once per slice. Repeating it would train the user
to dismiss the one notification that matters.

The client SHALL NOT select a foreground-service type that avoids the cap.
`mediaPlayback`, `location` and `specialUse` are all uncapped and all would
misrepresent the service to the platform and to Play review.

The user SHALL be told the estimate before starting, not discover it mid-run.

#### Scenario: Budget exhausted

- **WHEN** the soft budget is reached
- **THEN** the operation checkpoints, enters `PAUSED_BUDGET`, and a continuation is scheduled

#### Scenario: Warning before exhaustion

- **WHEN** consumed budget first crosses the warning threshold
- **THEN** a high-importance notification offers to continue now by opening the app, stating how much work remains

#### Scenario: The warning does not repeat

- **WHEN** the threshold has already been crossed in this slice
- **THEN** no further warning is posted, however many checkpoints follow

#### Scenario: Opening the app continues the work for free

- **WHEN** the user brings the app to the foreground mid-operation
- **THEN** billing stops and the operation continues without consuming budget

#### Scenario: Platform timeout

- **WHEN** `onTimeout()` fires before the soft budget
- **THEN** the same checkpoint-and-reschedule path runs

#### Scenario: Foreground work is not billed

- **WHEN** the operation runs with the app visible
- **THEN** no budget is consumed

#### Scenario: Estimate shown up front

- **WHEN** a bulk operation is confirmed
- **THEN** the item count and estimated duration are stated, including that it may span days
