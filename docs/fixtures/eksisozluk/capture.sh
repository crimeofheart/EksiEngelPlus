#!/usr/bin/env bash
# android-spike S2: capture each public endpoint under three user agents so the
# selectors in eksisozluk-client-contract can be compared across them.
# Read-only, low volume, public pages only. Authenticated endpoints
# (/relation-list, /follower, /following, favorileyenler) need a session and are
# captured in the device phase.
set -uo pipefail

OUT="${1:?usage: capture.sh <outdir>}"
BASE="https://eksisozluk.com"

UA_DESKTOP="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
UA_ANDROID_CHROME="Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
UA_WEBVIEW="Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP4A.250105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/139.0.0.0 Mobile Safari/537.36"

# The extension sends these on every request; x-requested-with is load-bearing.
hdr_ct='Content-Type: application/x-www-form-urlencoded; charset=UTF-8'
hdr_xrw='x-requested-with: XMLHttpRequest'

fetch() { # fetch <ua> <name> <path>
  local ua="$1" name="$2" path="$3"
  local dir="$OUT/$name"
  mkdir -p "$dir"
  curl -sS --max-time 30 -A "${!ua}" -H "$hdr_ct" -H "$hdr_xrw" \
       -o "$dir/$ua.html" \
       -w "%{http_code} %{size_download} %{num_redirects} %{url_effective}\n" \
       "$BASE$path" 2>&1 | sed "s|^|$name/$ua |"
  sleep 1   # politeness
}

for ua in UA_DESKTOP UA_ANDROID_CHROME UA_WEBVIEW; do
  fetch "$ua" home "/"
done

# Public content pages. Targets are resolved from the homepage at capture time
# rather than hardcoded, so the corpus never depends on an entry surviving.
TITLE_PATH="${TITLE_PATH:-}"
NICK="${NICK:-}"
ENTRY_ID="${ENTRY_ID:-}"
for ua in UA_DESKTOP UA_ANDROID_CHROME UA_WEBVIEW; do
  [ -n "$TITLE_PATH" ] && fetch "$ua" title "$TITLE_PATH"
  [ -n "$NICK" ]       && fetch "$ua" profile "/biri/$NICK"
  [ -n "$ENTRY_ID" ]   && fetch "$ua" entry "/entry/$ENTRY_ID"
done
