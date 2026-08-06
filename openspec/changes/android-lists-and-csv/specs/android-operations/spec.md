## MODIFIED Requirements

### Requirement: The six single-shot sources are supported

The engine SHALL support the sources in `background.js`: SINGLE (1), FAV (2),
FOLLOW (3), LIST (4), UNDOBANALL (5) and TITLE (6).

Each SHALL resolve its target set, then apply the requested relation to each
member through the pacer, checkpointing as it goes. The `ban_source` integers
SHALL match `enums.js`, because they are keys in a shared database.

LIST's target set SHALL be the saved author list — the rows of `author_list` in
insertion order — read once when the operation starts. The list is a stored
document the user edits between runs, so a run SHALL NOT observe edits made
after it started; a mid-run change to the target set would make the checkpoint
cursor meaningless.

#### Scenario: Block everyone who favourited an entry

- **WHEN** a FAV operation runs for an entry id
- **THEN** favouriters are resolved from both the standard and novice endpoints, ids are backfilled, and each is blocked through the pacer

#### Scenario: Block a title's participants

- **WHEN** a TITLE operation runs with a time specifier
- **THEN** the matching pages are paginated and each distinct author is blocked once

#### Scenario: Unblock everyone

- **WHEN** an UNDOBANALL operation runs
- **THEN** the blocked list is scraped and each entry is unblocked, checkpointing per unit

#### Scenario: LIST reads the saved author list

- **WHEN** a LIST operation starts
- **THEN** its targets are the `author_list` rows in insertion order, and an empty list ends the operation immediately rather than starting a run with nothing to do

#### Scenario: Editing the list mid-run does not disturb it

- **WHEN** the author list is edited while a LIST operation is running
- **THEN** the running operation continues against the set it resolved at start, and the edit applies to the next run
