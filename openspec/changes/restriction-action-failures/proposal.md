## Why

Muted-to-blocked conversion can treat a rejected block as successful and remove the mute. One inaccessible target must not abort a batch or cause another user's restriction to be removed.

## What Changes

- Fix relation success/failure classification for every extension action and preserve diagnostics on Android.
- Collect muted targets before conversion so removals do not shift pagination, retaining IDs from relation lists.
- Add the replacement restriction before removing the original in both conversion directions on both clients.
- Gate follow preparation on the current request's outcome and continue after target-specific failures.
- Retain failed and unvisited users in cached lists after bulk removals.

## Capabilities

### New Capabilities
- `safe-relation-actions`: Truthful mutation outcomes, safe conversions, and isolated target failures on extension and Android.

### Modified Capabilities

None.

## Impact

Touches frontend/app/ runtime code and Android's operation engine. Both Chrome and Firefox consume the same extension implementation. No dependencies, version bump, server mutations during testing, or backend changes.

## Non-goals

Guessing undocumented site codes or proving the reporter's target blocked them. The screenshot's `(unexpected-error)` is in the source-author label position.
