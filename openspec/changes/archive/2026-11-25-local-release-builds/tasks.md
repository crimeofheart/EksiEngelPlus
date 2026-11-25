## 1. Signing off CI

- [x] 1.1 Recover the upload key's alias and passwords from repository secrets (age-encrypted one-off workflow, then deleted with its run and artifact); confirm the keystore matches the 0.6.4 APK's certificate.
- [x] 1.2 Store them as `eksiengelplus.*` in `~/.gradle/gradle.properties`; age-encrypt keystore and properties in chezmoi.
- [x] 1.3 `build.gradle.kts` reads the Gradle properties, environment as fallback.

## 2. Local builds

- [x] 2.1 `tools/build-release.sh`: checks, extension zips, AAB + APK, pinned-certificate check, `SHA256SUMS`, `--upload-apk`, `--test`.
- [x] 2.2 `release.yml`: verify + notes only; drop the build, prune and Play jobs.
- [x] 2.3 Drop `set-min-version.sh --release`; `ext.mjs release` prints the build step.
- [x] 2.4 Re-verify the extension builds: `cd frontend/app && npm run check && npm run package`.

## 3. Downloads

- [x] 3.1 `/download/eksiengelplus.apk` redirect with tests; landing page and Ekşi first entry link to it.
- [x] 3.2 Upload the 0.6.4 APK to the server; delete the assets left on GitHub Releases.
- [x] 3.3 CLAUDE.md release, store and signing sections.
- [x] 3.4 Deploy the backend and confirm the redirect serves 0.6.4.
