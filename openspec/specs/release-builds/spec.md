# release-builds Specification

## Purpose
TBD - created by archiving change local-release-builds. Update Purpose after archive.
## Requirements
### Requirement: Release artifacts are built and signed on the maintainer's machine

Binds: release tooling for both clients.

`tools/build-release.sh` SHALL produce, in `frontend/publish/dist/`, the Chrome zip,
the Firefox zip, the AAB of the `play` flavor and the APK of the `github` flavor
for the recorded version, with a `SHA256SUMS` file. It SHALL refuse to run on a
working tree that is not clean or a HEAD that is not the tag `v<version>`, unless
`--test` is given, and a `--test` build SHALL never be uploaded. It SHALL refuse any
AAB or APK whose signing certificate is not the pinned upload certificate.

#### Scenario: Normal release
- **WHEN** `v0.6.5` is tagged and pushed and the maintainer runs `tools/build-release.sh`
- **THEN** the four files for 0.6.5 are in `frontend/publish/dist/`, both Android files signed with the upload key

#### Scenario: Wrong key
- **WHEN** the signing config points at any other keystore
- **THEN** the script stops before copying anything to `dist/`

#### Scenario: Untagged tree
- **WHEN** HEAD is not the release tag
- **THEN** the script refuses and names the command that tags it

### Requirement: CI attaches nothing to a release

The tag workflow SHALL check the tag against the recorded version and create a
GitHub Release with notes and links to the stores and to
`https://eksiengelplus.duzgun.org/download/eksiengelplus.apk`, and SHALL NOT build,
sign or attach any artifact, nor use the Android signing secrets.

#### Scenario: Tag pushed
- **WHEN** `v0.6.5` is pushed
- **THEN** a GitHub Release with notes exists and has no assets

### Requirement: One stable address serves the newest APK

`/download/eksiengelplus.apk` SHALL redirect to the highest-versioned
`eksiengelplus-x.y.z.apk` in the release directory, and SHALL answer 404 when there
is none. `tools/build-release.sh --upload-apk` SHALL upload the APK there and commit
the update-policy record.

#### Scenario: Several uploaded
- **WHEN** 0.6.4, 0.9.12 and 0.10.0 are uploaded
- **THEN** the link redirects to `eksiengelplus-0.10.0.apk`

