## ADDED Requirements

### Requirement: Truthful relation outcomes
Both extension and Android MUST accept only known successful responses from `/userrelation/{addrelation|removerelation}/{id}?r={m|i|u|b}`: addition numeric `0` or `2`, removal JSON object with `result: true`. Other outcomes MUST fail the current target and retain diagnostic HTTP status or response code. Only HTTP 429 SHALL trigger rate-limit cooldown.

#### Scenario: Unknown or malformed response
- **WHEN** a relation responds with an unknown number, HTTP error, null, or malformed payload
- **THEN** the target is not counted successful and subsequent targets remain processable

#### Scenario: Rate limit
- **WHEN** a relation responds with HTTP 429
- **THEN** the caller honors the returned retry delay and retries the current target

### Requirement: Safe conversion
Both clients MUST confirm the replacement restriction before removing the source restriction. Muted-to-blocked MUST collect a stable target list with available IDs before mutating it.

#### Scenario: Rejected block
- **WHEN** adding `r=m` fails during muted-to-blocked conversion
- **THEN** `r=u` is not removed and the next target is processed

#### Scenario: Rejected mute
- **WHEN** adding `r=u` fails during blocked-to-muted conversion
- **THEN** `r=m` is not removed and the next target is processed

#### Scenario: Multiple pages
- **WHEN** successful conversions remove users from a multi-page muted list
- **THEN** every collected target is visited without pagination skips

#### Scenario: Inaccessible profile
- **WHEN** a relation list already provides a positive target ID
- **THEN** conversion uses it without fetching the profile

#### Scenario: Cached source list after partial success
- **WHEN** conversion or bulk removal fails for some targets or stops early
- **THEN** the extension removes only confirmed successful removals from the cached list and retains failed and unvisited targets

### Requirement: Follow preparation uses current outcomes
Both clients MUST require a successful unblock or unmute before a follow requiring that removal. Preparation SHALL remain uncounted so progress describes targets.

#### Scenario: A previous target succeeded
- **WHEN** the current target's unblock fails after an earlier target succeeded
- **THEN** the current target is counted failed and no follow is attempted for it
