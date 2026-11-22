#!/usr/bin/env bash
# Sign and publish the update policy both clients check (openspec change
# app-update-gate). Ported from SportGuide TV's and OpenOTT's tool of the same name.
#
#   tools/set-min-version.sh [options]
#
# Versions are given as x.y.z (0.6.5) or as a code (605); every channel uses
# major*10000 + minor*100 + patch, the Android versionCode.
#
#   --min V             every channel: builds below V stop until updated
#   --min-github V      only the sideloaded APK
#   --min-play V        only the Play build -- raise after Play serves V to everyone
#   --min-chrome V      only the Chrome extension -- raise after the Web Store serves V
#   --min-firefox V     only the Firefox add-on -- raise after AMO serves V
#   --revoke V          stop build V on every channel (repeatable)
#   --unrevoke V        lift a revocation (repeatable)
#   --latest V          newest Android build, for the non-blocking "update available"
#   --apk FILE          upload FILE (eksiengelplus-x.y.z.apk) for the APK's Update
#                       button; its SHA-256 goes into the signed policy, and its
#                       version becomes --latest unless one is given
#   --release TAG       the same with the APK downloaded from GitHub Release TAG
#   --no-apk            drop the APK and latest version from the policy
#   --message TEXT      text for the update screen (Turkish; the apps are Turkish)
#   --message-LANG TEXT the same in another language, e.g. --message-en "..."
#   --clear-message     drop every message
#   --days N            give the signature an expiry N days out (default: none)
#   --backup-key        sign with the offline backup key instead of the primary
#   --dry-run           write backend/policy/app-v1.json only, upload nothing
#
# Values not given are kept from backend/policy/app-v1.json, the last policy
# published. A minimum never goes down on a device that has seen it, so lowering
# one here only affects devices that never fetched the higher one.
#
# Needs the policy key (~/.android/eksiengelplus-policy-key.pem, or
# EKSIENGEL_POLICY_KEY) and, to publish, `ssh proxmox1` with `pct exec` on the
# production container (EKSIENGEL_PVE_HOST, EKSIENGEL_CT).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
key="${EKSIENGEL_POLICY_KEY:-$HOME/.android/eksiengelplus-policy-key.pem}"
pve_host="${EKSIENGEL_PVE_HOST:-proxmox1}"
ct="${EKSIENGEL_CT:-110}"
origin="${EKSIENGEL_ORIGIN:-https://eksiengelplus.duzgun.org}"
release_dir="${EKSIENGEL_RELEASE_DIR:-/var/lib/eksiengelplus/release}"
out="$root/backend/policy/app-v1.json"
key_index=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

args=()
dry=false
apk=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry=true; shift ;;
        --clear-message) args+=(--clear-message x); shift ;;
        --no-apk) args+=(--no-apk x); shift ;;
        --backup-key) key="${EKSIENGEL_POLICY_BACKUP_KEY:-$HOME/.android/eksiengelplus-policy-key-backup.pem}"; key_index=1; shift ;;
        --release)
            [[ $# -ge 2 ]] || { echo "missing value for $1" >&2; exit 2; }
            gh release download "$2" --repo crimeofheart/EksiEngelPlus --pattern 'eksiengelplus-*.apk' --dir "$work/release"
            apk="$(find "$work/release" -name 'eksiengelplus-*.apk' | head -1)"
            [[ -n "$apk" ]] || { echo "release $2 has no APK (older releases are pruned)" >&2; exit 2; }
            shift 2 ;;
        --apk) [[ $# -ge 2 && -f "$2" ]] || { echo "no such file: ${2:-}" >&2; exit 2; }
               apk="$2"; shift 2 ;;
        --*) [[ $# -ge 2 ]] || { echo "missing value for $1" >&2; exit 2; }
             args+=("$1" "$2"); shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -f "$key" ]] || { echo "policy key not found: $key" >&2; exit 1; }
if [[ -n "$apk" ]]; then
    apk_name="$(basename "$apk")"
    # The server serves nothing else, and an "-unsigned" debug build cannot
    # install over a signed release anyway.
    [[ "$apk_name" =~ ^eksiengelplus-([0-9]+\.[0-9]+\.[0-9]+)\.apk$ ]] || {
        echo "the APK must be named eksiengelplus-x.y.z.apk, not $apk_name" >&2; exit 2; }
    args+=(--apk-version "${BASH_REMATCH[1]}" --sha256 "$(sha256sum "$apk" | cut -d' ' -f1)"
           --apk-url "$origin/download/$apk_name")
fi

python3 - "$out" "$work/payload.json" "${args[@]}" <<'PY'
import base64, json, re, sys, time
from pathlib import Path

CHANNELS = ("github", "play", "chrome", "firefox")

def code(value):
    """x.y.z -> major*10000 + minor*100 + patch, as android/app/build.gradle.kts."""
    if re.fullmatch(r"\d+", value):
        return int(value)
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", value)
    if not m:
        sys.exit(f"not a version: {value}")
    major, minor, patch = map(int, m.groups())
    if minor > 99 or patch > 99:
        sys.exit(f"{value}: minor and patch must stay below 100")
    return major * 10000 + minor * 100 + patch

previous, target, *argv = sys.argv[1:]
payload = {"v": 1, "app": "org.duzgun.eksiengelplus", "channels": {}, "message": {}}
if Path(previous).exists():
    envelope = json.loads(Path(previous).read_text())
    payload.update(json.loads(base64.b64decode(envelope["payload"])))
payload.pop("expiresAt", None)
channels = payload["channels"]
for name in CHANNELS:
    channels.setdefault(name, {}).setdefault("minVersionCode", 0)
revoked = set(channels["github"].get("revokedVersionCodes", []))
latest_given = False
apk_version = None
days = None
for option, value in zip(argv[0::2], argv[1::2]):
    if option == "--min":
        for name in CHANNELS: channels[name]["minVersionCode"] = code(value)
    elif option.startswith("--min-") and option[6:] in CHANNELS:
        channels[option[6:]]["minVersionCode"] = code(value)
    elif option == "--revoke": revoked.add(code(value))
    elif option == "--unrevoke": revoked.discard(code(value))
    elif option == "--latest":
        latest_given = True
        for name in ("github", "play"): channels[name]["latestVersionCode"] = code(value)
    elif option == "--apk-version": apk_version = code(value)
    elif option == "--apk-url": channels["github"]["apkUrl"] = value
    elif option == "--sha256": channels["github"]["sha256"] = value.lower()
    elif option == "--message": payload["message"]["tr"] = value
    elif option == "--clear-message": payload["message"] = {}
    elif option == "--no-apk":
        for field in ("apkUrl", "sha256", "latestVersionCode"): channels["github"].pop(field, None)
        channels["play"].pop("latestVersionCode", None)
    elif option.startswith("--message-"): payload["message"][option[len("--message-"):]] = value
    elif option == "--days": days = int(value)
    else: sys.exit(f"unknown option {option}")
if apk_version is not None and not latest_given:
    channels["github"]["latestVersionCode"] = apk_version
for name in CHANNELS:
    channels[name]["revokedVersionCodes"] = sorted(revoked)
github = channels["github"]
if not github.get("apkUrl", "https://").startswith("https://"):
    sys.exit("the APK URL must be https")
if ("apkUrl" in github) != ("sha256" in github):
    sys.exit("an APK URL and its SHA-256 go together")
if apk_version is None and "apkUrl" in github:
    named = re.search(r"eksiengelplus-(\d+\.\d+\.\d+)\.apk$", github["apkUrl"])
    apk_version = code(named.group(1)) if named else None
if github["minVersionCode"] > (apk_version or 0):
    print(f"warning: the github minimum {github['minVersionCode']} is above the APK the policy names "
          f"({apk_version or 'none'}); blocked sideloaded users will be sent to the website instead",
          file=sys.stderr)
now = int(time.time())
payload["issuedAt"] = now
if days:
    payload["expiresAt"] = now + days * 86400
Path(target).write_text(json.dumps(payload, separators=(",", ":"), sort_keys=True))
print(json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False))
PY

openssl dgst -sha256 -sign "$key" -out "$work/payload.sig" "$work/payload.json"
openssl ec -in "$key" -pubout -out "$work/public.pem" 2>/dev/null
openssl dgst -sha256 -verify "$work/public.pem" -signature "$work/payload.sig" "$work/payload.json" >/dev/null

# Both clients carry the public keys; refuse to publish under any other.
actual="$(openssl ec -in "$key" -pubout -outform DER 2>/dev/null | base64 -w0)"
for source in android/app/src/main/kotlin/org/duzgun/eksiengelplus/gate/AppPolicy.kt \
              frontend/app/assets/js/updateGate.js; do
    expected="$(grep -o '"MFkw[A-Za-z0-9+/=]*"' "$root/$source" | sed -n "$((key_index + 1))p" | tr -d '"')"
    [[ "$expected" == "$actual" ]] || { echo "this key is not public key #$key_index in $source" >&2; exit 1; }
done

mkdir -p "$(dirname "$out")"
python3 - "$work/payload.json" "$work/payload.sig" "$out" <<'PY'
import base64, json, sys
from pathlib import Path
payload, sig, out = sys.argv[1:]
envelope = {"alg": "ES256",
            "payload": base64.b64encode(Path(payload).read_bytes()).decode(),
            "sig": base64.b64encode(Path(sig).read_bytes()).decode()}
Path(out).write_text(json.dumps(envelope) + "\n")
PY
echo "wrote ${out#"$root"/}"

$dry && exit 0

# Runs one shell command inside the production container.
in_ct() {
    ssh -o BatchMode=yes -o ConnectTimeout=10 "$pve_host" "pct exec $ct -- sh -c $(printf '%q' "$1")"
}

if [[ -n "$apk" ]]; then
    # The APK first: a policy must never name a file that is not there yet.
    in_ct "mkdir -p $release_dir/download && cat > $release_dir/download/$apk_name.tmp" < "$apk"
    in_ct "set -eu
        cd $release_dir/download
        echo '$(sha256sum "$apk" | cut -d' ' -f1)  $apk_name.tmp' | sha256sum -c --quiet
        chmod 0644 $apk_name.tmp && mv $apk_name.tmp $apk_name"
    curl -fsS -o /dev/null -I "$origin/download/$apk_name" || {
        echo "uploaded, but $origin/download/$apk_name does not answer" >&2; exit 1; }
fi
in_ct "mkdir -p $release_dir/policy && cat > $release_dir/policy/app-v1.json.tmp" < "$out"
in_ct "chmod 0644 $release_dir/policy/app-v1.json.tmp && mv $release_dir/policy/app-v1.json.tmp $release_dir/policy/app-v1.json"

# Read it back the way the clients do.
served="$(curl -fsS -H 'Cache-Control: no-cache' "$origin/policy/app-v1.json")"
[[ "$served" == "$(cat "$out")" ]] || { echo "the published policy differs from ${out#"$root"/}" >&2; exit 1; }
echo "published $origin/policy/app-v1.json -- commit ${out#"$root"/} so the next run starts from it"
