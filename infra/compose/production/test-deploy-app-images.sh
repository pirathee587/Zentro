#!/usr/bin/env bash
# Run as root in a disposable, network-disabled Linux container.
set -euo pipefail
[[ ! -e /opt/zentro ]]
umask 077
export PRODUCTION_DIR=/opt/zentro/infra/compose/production
mkdir -p "$PRODUCTION_DIR/secrets" /opt/zentro/{bin,secrets,data/kafka,data/caddy/data,data/caddy/config,mocks}
cd "$PRODUCTION_DIR"
touch release.env /opt/zentro/secrets/tracking-probe.env
for service in s1 s2 s3 s4 s5 keycloak; do
  touch "secrets/$service.env"
done
cat > /opt/zentro/bin/check-tracking-sse <<'MOCK'
#!/bin/bash
[[ "${FAILURE:-}" != sse || "$(cat "$PRODUCTION_DIR/running")" != candidate ]]
MOCK
printf '#!/bin/bash\nexit 0\n' > /opt/zentro/bin/tracking-probe-token
chmod 750 /opt/zentro/bin/check-tracking-sse /opt/zentro/bin/tracking-probe-token
export PATH="/opt/zentro/mocks:$PATH"

cat > /opt/zentro/mocks/docker <<'MOCK'
#!/bin/bash
case "$1" in
  info) [[ $# == 1 ]] || printf '/opt/zentro\n' ;;
  inspect) printf 'healthy\n' ;;
  manifest) exit 0 ;;
  compose)
    case "$8" in
      ps)
        if [[ "$9" == --status ]]; then
          [[ "${FAILURE:-}" != baseline ]] || printf 'existing-app\n'
        else
          printf 'dependency\n'
        fi ;;
      pull) [[ "${FAILURE:-}" != pull ]] ;;
      up)
        if [[ "$5" == *.previous.* ]]; then
          printf 'previous\n' > "$PRODUCTION_DIR/running"
        else
          printf 'candidate\n' > "$PRODUCTION_DIR/running"
          [[ "${FAILURE:-}" != startup ]]
        fi ;;
      stop) printf 'stopped\n' > "$PRODUCTION_DIR/running" ;;
    esac ;;
esac
MOCK
cat > /opt/zentro/mocks/curl <<'MOCK'
#!/bin/bash
case "${*: -1}" in
  */.well-known/openid-configuration)
    [[ "${FAILURE:-}" != discovery || "$(cat "$PRODUCTION_DIR/running")" != candidate ]] ;;
  */protocol/openid-connect/certs)
    [[ "${FAILURE:-}" != jwks || "$(cat "$PRODUCTION_DIR/running")" != candidate ]] ;;
  */api/v1/parcels)
    if [[ "${FAILURE:-}" == https && "$(cat "$PRODUCTION_DIR/running")" == candidate ]]; then
      printf '502'
    else
      printf '401'
    fi ;;
  */auth/admin/realms|*/auth/health|*/auth/realms/master|*/actuator/health) printf '404' ;;
  'Access-Control-Request-Method: GET')
    while [[ "$1" != -D ]]; do shift; done
    if [[ "${FAILURE:-}" != cors || "$(cat "$PRODUCTION_DIR/running")" != candidate ]]; then
      printf 'Access-Control-Allow-Origin: https://zentro.jeyakumaranpiratheepan120.workers.dev\r\n' > "$2"
    fi ;;
esac
MOCK
printf '#!/bin/bash\ncat >/dev/null\n' > /opt/zentro/mocks/jq
printf '#!/bin/bash\nprintf "header\\nfilesystem 1 1 99999999 /\\n"\n' > /opt/zentro/mocks/df
cat > /opt/zentro/mocks/awk <<'MOCK'
#!/bin/bash
if [[ "${*: -1}" == /proc/meminfo ]]; then exit 0; fi
exec /usr/bin/awk "$@"
MOCK
chmod 750 /opt/zentro/mocks/*

keys=(GATEWAY_IMAGE S1_IMAGE S2_IMAGE S3_IMAGE S4_IMAGE S5_IMAGE KAFKA_IMAGE KEYCLOAK_IMAGE CADDY_IMAGE)
services=(api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service tracking-reporting-service notification-integration-service kafka keycloak caddy)
for i in "${!keys[@]}"; do
  printf '%s=ghcr.io/pirathee587/zentro/%s@sha256:%064d\n' "${keys[i]}" "${services[i]}" 1 >> baseline.env
  printf '%s=ghcr.io/pirathee587/zentro/%s@sha256:%064d\n' "${keys[i]}" "${services[i]}" 2 >> candidate.env
done

script="$1"
for failure in pull startup https discovery jwks cors sse; do
  cp baseline.env images.env
  printf 'previous\n' > running
  if output="$(FAILURE="$failure" bash "$script" < candidate.env 2>&1)"; then
    exit 1
  fi
  cmp baseline.env images.env
  [[ "$(cat running)" == previous ]]
  if [[ "$failure" == pull ]]; then
    [[ "$output" == *ZENTRO_IMAGE_PULL_FAILED* ]]
  else
    if [[ "$output" != *ZENTRO_ROLLBACK_OK* ]]; then
      printf '%s\n' "$output" >&2
      exit 1
    fi
  fi
  printf 'PASS %s\n' "$failure"
done

if printf 'S1_IMAGE=invalid\n' | bash "$script"; then
  exit 1
fi
cmp baseline.env images.env
printf 'PASS malformed manifest\n'

# Existing six-image manifests remain valid rollback inputs.
head -n 6 baseline.env > images.env
if output="$(FAILURE=https bash "$script" < candidate.env 2>&1)"; then exit 1; fi
[[ "$output" == *ZENTRO_ROLLBACK_OK* && "$(wc -l < images.env)" == 6 ]]
touch /opt/zentro/data/kafka/meta.properties
if output="$(bash "$script" < candidate.env 2>&1)"; then exit 1; fi
[[ "$output" == *ZENTRO_KAFKA_UPGRADE_REQUIRED* ]]
rm /opt/zentro/data/kafka/meta.properties
mv images.env baseline.active.env
if output="$(FAILURE=baseline bash "$script" < candidate.env 2>&1)"; then
  exit 1
fi
[[ "$output" == *ZENTRO_BASELINE_REQUIRED* && "$(cat running)" == previous ]]
printf 'PASS unverified running baseline\n'

if output="$(FAILURE=startup bash "$script" < candidate.env 2>&1)"; then
  exit 1
fi
[[ "$output" == *ZENTRO_INITIAL_APPS_STOPPED* && ! -e images.env && "$(cat running)" == stopped ]]
printf 'PASS first-release failure\n'

bash "$script" < candidate.env | grep -Fx ZENTRO_DEPLOY_OK
cmp candidate.env images.env
printf 'PASS first-release success\n'
