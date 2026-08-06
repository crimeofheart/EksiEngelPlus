# Telemetry defaults at extension parity

## Why

`android-foundations` shipped `EksiConfig` with `sendData` and `sendLog` defaulting
to `false`, against the extension's `true` (`config.js:25-26`). That was an
unrequested behaviour change made while porting, and it has a concrete cost: the
Android client would report nothing unless each user went and enabled it, so the
Django dashboard would show the app as near-dead regardless of real usage.

The maintainer's decision is parity with the extension. This change records that
decision and its consequences rather than leaving a bare boolean flip in a diff.

## What Changes

- `EksiConfig.sendData` and `EksiConfig.sendLog` default to `true`.
- `author_list` is sent in **plaintext**, as the extension does. No hashing.
- The Play Data Safety consequences are written into the spec so whoever files
  the form is not reconstructing them from memory.

## Capabilities

### Modified Capabilities

- `android-persistence`: the documented defaults change, and the reasoning behind
  them becomes a requirement rather than a code comment.

## Impact

`android/core/datastore/.../Config.kt`, its test, and the canonical
`openspec/specs/android-persistence/spec.md`. No backend change: the payload is
byte-identical to what the extension already sends.

## Non-goals

- Hashing or otherwise reducing `author_list`. Considered and declined: the
  admin's `most_banned`, `most_banned_unique` and `EksiSozlukUserStatView`
  (`api/views.py:44-65`) rank users by plaintext identity, and hashing would
  empty those views.
- A consent screen. The extension has none and this is parity.
- Any change to what is transmitted. Only the defaults move.
