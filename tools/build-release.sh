#!/usr/bin/env bash
# Build every release artifact on this machine (openspec change local-release-builds).
#
#   tools/build-release.sh [--upload-apk] [--test]
#
# Run after the release is tagged and pushed:
#   cd frontend/app && npm run release -- patch && git push origin master --follow-tags
#   tools/build-release.sh --upload-apk
#
# Writes to frontend/publish/dist/ (gitignored):
#   eksiengelplus-<v>-chrome.zip    Chrome Web Store
#   eksiengelplus-<v>-firefox.zip   addons.mozilla.org (desktop and Android)
#   eksiengelplus-<v>.aab           Google Play -- the `play` flavor; Play delivers its updates
#   eksiengelplus-<v>.apk           sideload    -- the `github` flavor; installs its own updates
#   SHA256SUMS
#
# Both Android files are signed with the upload key named in ~/.gradle/gradle.properties
# (eksiengelplus.*, age-encrypted in chezmoi) and refused unless their certificate is
# UPLOAD_CERT: a sideloaded APK only updates over itself under the same key, so a
# release signed with any other key would strand every install.
#
#   --upload-apk  also publish the APK on eksiengelplus.duzgun.org (tools/set-min-version.sh
#                 --apk) and commit the policy record. The website's download link and the
#                 APK's "yeni sürüm var" offer follow it; no minimum is raised.
#   --test        build the working tree as it is: no clean-tree or tag check, no upload.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# SHA-256 of the upload certificate (CN=Ali Duzgun Kahramaner, OU=EksiEngelPlus),
# the one every release since v0.1.x carries.
UPLOAD_CERT=0f8c86d6f5a3c8e7ee337a5285e13fbcad1f40803f251c7e49a03efbdb0ac3e9
java_home="${EKSIENGEL_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
dist="$root/frontend/publish/dist"

upload=false
test_build=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --upload-apk) upload=true; shift ;;
        --test) test_build=true; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
die() { echo "build-release: $*" >&2; exit 1; }
$test_build && $upload && die "--test builds are never uploaded"

cd "$root"
version="$(node -p "require('./frontend/app/package.json').version")"
tag="v$version"
if ! $test_build; then
    [[ -z "$(git status --porcelain)" ]] || die "the working tree is not clean"
    tagged="$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)"
    [[ -n "$tagged" ]] || die "no tag $tag: run \`npm run release -- patch\` (or \`-- current\`) first"
    [[ "$(git rev-parse HEAD)" == "$tagged" ]] || die "HEAD is not $tag; check out the tag to rebuild it"
fi

gradle_props="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
grep -q '^eksiengelplus\.storePassword=' "$gradle_props" 2>/dev/null \
    || die "no signing config in $gradle_props (restore it with \`chezmoi apply\`)"
[[ -x "$java_home/bin/java" ]] || die "JDK 17 not found at $java_home (set EKSIENGEL_JAVA_HOME)"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f android/local.properties ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' android/local.properties)"
fi
sdk="${sdk:-$HOME/Android/Sdk}"
build_tools="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[[ -x "${build_tools}apksigner" ]] || die "apksigner not found under $sdk/build-tools"

echo "== extension $version"
# `package` empties dist/ first, so it runs before the Android files land there.
(cd frontend/app && npm run --silent check >/dev/null && npm run --silent package)

echo "== android $version"
outputs=android/app/build/outputs
rm -rf "$outputs/bundle/playRelease" "$outputs/apk/github/release"
(cd android && JAVA_HOME="$java_home" ./gradlew --console=plain -q :app:bundlePlayRelease :app:assembleGithubRelease)
aab="$outputs/bundle/playRelease/app-play-release.aab"
apk="$outputs/apk/github/release/app-github-release.apk"
[[ -f "$apk" && -f "$aab" ]] || die "no signed AAB/APK came out -- is the signing config complete?"

apk_certs="$("${build_tools}apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')"
[[ "$apk_certs" == "$UPLOAD_CERT" ]] || die "the APK is signed with ${apk_certs:-nothing}, not the upload key"
"$java_home/bin/jarsigner" -verify "$aab" >/dev/null || die "the AAB's signature does not verify"
# sed rather than head throughout: head exits early, the writer gets SIGPIPE, and
# under pipefail that ends the script without a word.
aab_cert="$("$java_home/bin/keytool" -printcert -jarfile "$aab" | sed -n 's/^[[:space:]]*SHA256: //p' | sed -n 1p | tr -d ':' | tr 'A-F' 'a-f')"
[[ "$aab_cert" == "$UPLOAD_CERT" ]] || die "the AAB is signed with ${aab_cert:-nothing}, not the upload key"
if [[ -x "${build_tools}aapt2" ]]; then
    badging="$("${build_tools}aapt2" dump badging "$apk" | sed -n 1p)"
    [[ "$badging" == *"versionName='$version'"* ]] || die "the APK is not $version: $badging"
fi

cp "$aab" "$dist/eksiengelplus-$version.aab"
cp "$apk" "$dist/eksiengelplus-$version.apk"
(cd "$dist" && sha256sum eksiengelplus-"$version"* > SHA256SUMS && cat SHA256SUMS)

if $upload; then
    echo "== publishing the APK"
    tools/set-min-version.sh --apk "$dist/eksiengelplus-$version.apk" >/dev/null
    git add backend/policy/app-v1.json
    git commit -q -m "chore(policy): offer the $version APK" -m "Uploaded to eksiengelplus.duzgun.org/download/ by tools/build-release.sh."
    echo "published https://eksiengelplus.duzgun.org/download/eksiengelplus-$version.apk"
    echo "committed the policy record -- push with: git push origin master"
fi

cat <<EOF

${dist#"$root"/}/ is ready. Upload by hand:
  Google Play   eksiengelplus-$version.aab   (Play Console -> Test and release)
  Chrome        eksiengelplus-$version-chrome.zip
  Firefox       eksiengelplus-$version-firefox.zip
Force older builds off only once a store serves $version: tools/set-min-version.sh --min-<channel> $version
EOF
