#!/usr/bin/env bash
set -euo pipefail
umask 077

cd /opt/zentro/infra/compose/production
exec 9>deployment.lock
flock -n 9

candidate="$(mktemp "$PWD/.images.XXXXXXXX")"
previous="$(mktemp "$PWD/.images.previous.XXXXXXXX")"
diagnostics="$(mktemp -d "$PWD/.deploy.XXXXXXXX")"
cleanup() {
  rm -f -- "$candidate" "$previous" "$diagnostics/headers"
  rmdir -- "$diagnostics"
}
trap cleanup EXIT
head -c 4097 > "$candidate"
[[ "$(wc -c < "$candidate")" -le 4096 ]]

keys=(GATEWAY_IMAGE S1_IMAGE S2_IMAGE S3_IMAGE S4_IMAGE S5_IMAGE KAFKA_IMAGE KEYCLOAK_IMAGE CADDY_IMAGE)
services=(api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service tracking-reporting-service notification-integration-service)
infrastructure=(kafka keycloak caddy)
repositories=("${services[@]}" "${infrastructure[@]}")
unset "${keys[@]}"
validate_manifest() {
  local entries i expected
  mapfile -t entries < "$1" || return 1
  [[ "${#entries[@]}" -eq "${#keys[@]}" || ( "$1" == images.env && "${#entries[@]}" -eq "${#services[@]}" ) ]] || return 1
  for i in "${!entries[@]}"; do
    expected="${keys[i]}=ghcr.io/pirathee587/zentro/${repositories[i]}@sha256:"
    [[ "${entries[i]}" == "$expected"* && "${entries[i]#"$expected"}" =~ ^[a-f0-9]{64}$ ]] || return 1
  done
}

validate_manifest "$candidate"
if [[ -e images.env ]]; then
  [[ -f images.env && ! -L images.env && "$(stat -c '%u:%a' images.env)" == 0:600 ]]
  validate_manifest images.env
  cp -- images.env "$previous"
fi
current=(docker compose --env-file release.env --env-file "$previous" -f compose.yml)
next=(docker compose --env-file release.env --env-file "$candidate" -f compose.yml)

require_private_file() {
  [[ -f "$1" && ! -L "$1" && "$(stat -c '%u:%a' "$1")" == 0:600 ]]
}

[[ $EUID == 0 ]]
docker info >/dev/null 2>&1
docker_root="$(docker info --format '{{.DockerRootDir}}')"
[[ -d /opt/zentro/data/kafka && -d /opt/zentro/data/caddy/data && -d /opt/zentro/data/caddy/config ]]
df -Pk /opt/zentro "$docker_root" | awk 'NR > 1 && $4 < 8388608 { exit 1 }'
awk '/^MemTotal:/ { found=1; if ($2 < 12582912) exit 1 } END { if (!found) exit 1 }' /proc/meminfo
for service in s1 s2 s3 s4 s5 keycloak; do
  require_private_file "secrets/$service.env"
done
require_private_file release.env
require_private_file /opt/zentro/secrets/tracking-probe.env
for path in /opt/zentro/bin/check-tracking-sse /opt/zentro/bin/tracking-probe-token; do
  [[ -f "$path" && ! -L "$path" && -x "$path" && "$(stat -c '%u:%a' "$path")" == 0:750 ]]
done
"${next[@]}" config --quiet >/dev/null 2>&1
if [[ ! -s "$previous" ]]; then
  running_apps="$("${next[@]}" ps --status running -q "${services[@]}")"
  if [[ -n "$running_apps" ]]; then
    printf '%s\n' ZENTRO_BASELINE_REQUIRED >&2
    exit 1
  fi
fi
while IFS='=' read -r _ image || [[ -n "$image" ]]; do
  docker manifest inspect "$image" >/dev/null 2>&1
done < "$candidate"

api=https://zentro-production.indiasouthcentral.cloudapp.azure.com
issuer="$api/auth/realms/zentro"
origin=https://zentro.jeyakumaranpiratheepan120.workers.dev
curl_https=(curl -fsS --connect-timeout 5 --max-time 15)

verify_identity() {
  "${curl_https[@]}" "$issuer/.well-known/openid-configuration" |
    jq -e --arg issuer "$issuer" '.issuer == $issuer and .token_endpoint == ($issuer + "/protocol/openid-connect/token") and .jwks_uri == ($issuer + "/protocol/openid-connect/certs")' >/dev/null || return 1
  "${curl_https[@]}" "$issuer/protocol/openid-connect/certs" |
    jq -e '.keys | type == "array" and length > 0' >/dev/null
}

verify_release() {
  local path
  verify_identity || return 1
  for path in /auth/admin/realms /auth/health /auth/realms/master /actuator/health; do
    [[ "$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$api$path")" == 404 ]] || return 1
  done
  [[ "$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$api/api/v1/parcels")" == 401 ]] || return 1
  "${curl_https[@]}" -D "$diagnostics/headers" -o /dev/null -X OPTIONS "$api/api/v1/parcels" \
    -H "Origin: $origin" -H 'Access-Control-Request-Method: GET' || return 1
  tr -d '\r' < "$diagnostics/headers" | grep -Fix "access-control-allow-origin: $origin" >/dev/null || return 1
  timeout 30 /opt/zentro/bin/check-tracking-sse "$api" >/dev/null 2>&1
}

verify_previous_health() {
  local service
  for service in "${services[@]}"; do
    [[ "$(docker inspect --format '{{.State.Health.Status}}' "$("${current[@]}" ps -q "$service")")" == healthy ]] || return 1
  done
  verify_release
}

if ! "${next[@]}" pull "${repositories[@]}" >/dev/null 2>&1; then
  printf '%s\n' ZENTRO_IMAGE_PULL_FAILED >&2
  if [[ -s "$previous" ]]; then
    if ! verify_previous_health >/dev/null 2>&1; then
      printf '%s\n' ZENTRO_PREVIOUS_HEALTH_FAILED >&2
    fi
  fi
  exit 1
fi

# Never switch a populated broker directory to an unverified Kafka version.
kafka_dir="$(find /opt/zentro/data/kafka -mindepth 1 -maxdepth 1 -print -quit)"
if [[ -n "$kafka_dir" ]]; then
  kafka_container="$("${current[@]}" ps -a -q kafka)"
  kafka_image="$(sed -n 's/^KAFKA_IMAGE=//p' "$candidate")"
  if [[ -z "$kafka_container" || "$(docker inspect --format '{{.Image}}' "$kafka_container")" != "$(docker image inspect --format '{{.Id}}' "$kafka_image")" ]]; then
    printf '%s\n' ZENTRO_KAFKA_UPGRADE_REQUIRED >&2
    exit 1
  fi
fi

start_stack() {
  local -n compose="$1"
  "${compose[@]}" up -d --no-deps --wait --wait-timeout 180 "${infrastructure[@]}" &&
    "${compose[@]}" run --rm --no-deps kafka-topics &&
    "${compose[@]}" exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile &&
    "${compose[@]}" up -d --no-deps --wait --wait-timeout 180 "${services[@]}" &&
    verify_release
}

if ! { start_stack next; } >/dev/null 2>&1; then
  printf '%s\n' ZENTRO_CANDIDATE_HEALTH_FAILED >&2
  if [[ -s "$previous" ]]; then
    if { start_stack current; } >/dev/null 2>&1; then
      printf '%s\n' ZENTRO_ROLLBACK_OK >&2
    else
      printf '%s\n' ZENTRO_ROLLBACK_FAILED >&2
    fi
  elif "${next[@]}" stop "${services[@]}" >/dev/null 2>&1; then
    printf '%s\n' ZENTRO_INITIAL_APPS_STOPPED >&2
  else
    printf '%s\n' ZENTRO_INITIAL_STOP_FAILED >&2
  fi
  exit 1
fi
if [[ -s "$previous" ]]; then
  mv -- "$previous" images.previous.env
fi
mv -- "$candidate" images.env
printf '%s\n' ZENTRO_DEPLOY_OK
