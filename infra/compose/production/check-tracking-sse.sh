#!/usr/bin/env bash
set -euo pipefail
umask 077

api="$1"
[[ "$api" == https://zentro-production.indiasouthcentral.cloudapp.azure.com ]]
fixture=/opt/zentro/secrets/tracking-probe.env
provider=/opt/zentro/bin/tracking-probe-token
[[ -f "$fixture" && ! -L "$fixture" && "$(stat -c '%u:%a' "$fixture")" == 0:600 ]]
[[ -x "$provider" && ! -L "$provider" && "$(stat -c '%u:%a' "$provider")" == 0:750 ]]
read -r tracking_ref < "$fixture"
[[ "$tracking_ref" =~ ^[A-Za-z0-9_-]{1,64}$ ]]
token="$("$provider")"
[[ "$token" =~ ^[A-Za-z0-9_.-]+$ ]]

probe="$(mktemp -d)"
trap 'rm -f -- "$probe/headers" "$probe/body"; rmdir -- "$probe"' EXIT
status=0
printf 'Authorization: Bearer %s\n' "$token" |
  curl --silent --fail --no-buffer --connect-timeout 5 --max-time 10 --max-filesize 65536 \
    --header @- --header 'Accept: text/event-stream' --dump-header "$probe/headers" \
    --output "$probe/body" "$api/api/v1/tracking/$tracking_ref/events" || status=$?
[[ "$status" == 0 || "$status" == 28 ]]
grep -Eiq '^content-type: *text/event-stream([;[:space:]]|$)' "$probe/headers"
tr -d '\r' < "$probe/body" | grep -Ex 'event: *connected' >/dev/null
