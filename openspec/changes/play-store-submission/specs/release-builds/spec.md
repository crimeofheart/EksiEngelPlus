## ADDED Requirements

### Requirement: A release is not published until the stores serve it

Binds: release tooling, both clients.

Building and tagging a version SHALL NOT be treated as publishing it. A version
is published on a channel only when that channel's store serves it; until then
its minimum on that channel stays where it is. For Google Play this means the
`play` AAB built by `tools/build-release.sh` is uploaded, reviewed and rolled
out, and `tools/set-min-version.sh --min-play <version>` runs afterwards — see
`openspec/changes/play-store-submission/specs/play-store-release/spec.md`.

`docs/eksisozluk/announcements/` and the website SHALL describe what is actually
served, so a tagged-but-unshipped version (0.5.2 is the precedent) is neither
announced nor referenced by a minimum.

#### Scenario: Tagged but not yet on Play
- **WHEN** `v0.6.5` is tagged and its AAB is in `frontend/publish/dist/` but Play still serves 0.6.4
- **THEN** the `play` minimum stays at the previous value and the announcement does not claim a Play release

#### Scenario: Store serves the build
- **WHEN** Play's rollout reaches 100% for the tagged version
- **THEN** that channel's minimum may be raised to it, and the release counts as published
