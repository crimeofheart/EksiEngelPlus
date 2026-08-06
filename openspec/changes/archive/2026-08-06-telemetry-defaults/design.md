# Design — telemetry defaults

## Context

The port flipped two booleans while translating `config.js` into `EksiConfig`.
Small diff, real consequence: with `sendData` off by default the Django dashboard
would report the Android client as near-dead no matter how many people used it,
because approximately nobody enables telemetry by hand.

## Goals / Non-Goals

**Goals** — parity with the extension; the decision and its Play consequences
recorded where the person filing the Data Safety form will find them.

**Non-Goals** — hashing, consent screens, or any change to the transmitted
payload. Only the defaults move.

## Decisions

### Parity over caution

Three options were on the table:

1. **Split the flag** — operational telemetry on, third-party `author_list` off.
   Keeps near-full coverage of what matters operationally and drops the Data
   Safety declaration to the operator's own id. Costs the most-banned views for
   anyone who does not opt in.
2. **Hash `author_list`** — full coverage, counts and dedup preserved, names not
   recoverable. Lowest exposure. Costs a backend migration and empties the
   most-banned views entirely, since they rank by plaintext identity.
3. **Plaintext parity** — chosen.

Chosen because the dashboard's value is concentrated in exactly the views the
other two options damage, and because the exposure is not new: the extension has
shipped this payload for its whole life. Adding a second platform does not change
the legal posture, and accepting a degraded dashboard to avoid a risk already
being run would be paying twice for nothing.

### Record it as a requirement, not a comment

A code comment saying "default true, matches the extension" invites a future
reader to helpfully flip it back for privacy. Putting the reasoning and the
declined alternatives in the spec makes reversing it a deliberate act with a
visible cost.

## Risks / Trade-offs

**Play review flags User IDs as non-optional** → the settings toggle makes it
genuinely optional in the product sense; the form still declares collection.
Mitigated by an accurate privacy policy, not by hiding the field.

**KVKK/GDPR exposure on third-party nicknames** → real, unchanged from the
extension, and now explicitly recorded rather than implicit. If it ever needs
addressing, hashing is the pre-analysed path and the design notes above are the
starting point.

**Someone reverses this while tidying** → the spec requirement says SHALL NOT be
quietly reversed and names the reasoning.

## Migration Plan

Two default values and one test assertion. No data migration, no backend change,
payload byte-identical to the extension's.
