#!/usr/bin/env bash
# Offline tests for deploy-agent.sh. Run as root (CI uses sudo) on a host with bash, jq, python3, flock and
# GNU date:   sudo bash infra/compose/production/test-deploy-agent.sh infra/compose/production/deploy-agent.sh
# GitHub, GHCR and the deploy script are replaced by mocks; nothing leaves the machine.
set -euo pipefail
agent="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
[[ $EUID == 0 ]]
base="$(mktemp -d)"
trap 'rm -rf -- "$base"' EXIT
export ZENTRO_AGENT_ROOT="$base/opt" FIX="$base/fixtures" LOG="$base/log"
mkdir -p "$ZENTRO_AGENT_ROOT"/{bin,secrets,data} "$FIX" "$LOG" "$base/mocks"
export PATH="$base/mocks:$PATH"

sha=0123456789abcdef0123456789abcdef01234567
repos=(api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service
  tracking-reporting-service notification-integration-service kafka keycloak caddy)
keys=(GATEWAY_IMAGE S1_IMAGE S2_IMAGE S3_IMAGE S4_IMAGE S5_IMAGE KAFKA_IMAGE KEYCLOAK_IMAGE CADDY_IMAGE)
for i in "${!repos[@]}"; do
  printf '%s=ghcr.io/pirathee587/zentro/%s@sha256:%064x\n' "${keys[i]}" "${repos[i]}" "$((i + 1))"
done > "$FIX/images.env"
python3 -I - "$FIX/images.env" "$FIX/manifest.zip" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[2], "w") as z: z.write(sys.argv[1], "images.env")
PY

cat > "$base/mocks/curl" <<'MOCK'
#!/usr/bin/env bash
cat > /dev/null  # curl config (token) on stdin is discarded
url="${*: -1}"; out=""; method=GET; data=""
args=("$@")
for i in "${!args[@]}"; do
  case "${args[i]}" in
    -o) out="${args[i+1]}" ;;
    -X) method="${args[i+1]}" ;;
    --data-binary) data="${args[i+1]#@}" ;;
  esac
done
emit() { if [[ -n "$out" ]]; then cat > "$out"; else cat; fi; }
case "$url" in
  */deployments\?environment=*) emit < "$FIX/deployments.json" ;;
  */deployments/*/statuses\?per_page=1) printf '%s' "${EXISTING_STATUS:-[]}" | emit ;;
  */deployments/*/statuses)
    [[ "$method" == POST ]]
    id="${url%/statuses}"; id="${id##*/}"
    printf '%s %s\n' "$id" "$(jq -r .state "$data")" >> "$LOG/statuses"; : | emit ;;
  */compare/*) printf '{"status":"%s"}' "${COMPARE:-ahead}" | emit ;;
  */actions/runs/*/approvals)
    run="${url%/approvals}"; run="${run##*/}"
    if [[ "${MISSING_APPROVAL:-}" == "$run" ]]; then printf '[]'
    else printf '[{"state":"approved","environments":[{"name":"production-publication"},{"name":"production"}]}]'; fi | emit ;;
  */actions/runs/*/jobs*)
    printf '{"jobs":[{"name":"publish","status":"completed","conclusion":"success"},{"name":"vm-release","status":"in_progress","conclusion":null}]}' | emit ;;
  */actions/runs/*/artifacts*)
    printf '{"artifacts":[{"expired":false,"archive_download_url":"https://api.github.com/download/manifest"}]}' | emit ;;
  */download/manifest) emit < "$FIX/manifest.zip" ;;
  */actions/runs/*)
    jq -nc --arg sha "${RUN_SHA:-$SHA}" '{head_sha: $sha, head_branch: "main", path: ".github/workflows/production.yml",
      repository: {full_name: "pirathee587/Zentro"}, head_repository: {full_name: "pirathee587/Zentro"},
      event: "push", html_url: "https://github.com/pirathee587/Zentro/actions/runs/1"}' | emit ;;
  *) echo "unexpected URL $url" >&2; exit 22 ;;
esac
MOCK
cat > "$base/mocks/docker" <<'MOCK'
#!/usr/bin/env bash
[[ "$1 $2 $3" == "buildx imagetools inspect" ]]
ref="$4"; repo="${ref#ghcr.io/pirathee587/zentro/}"; repo="${repo%%:*}"
[[ "${ref##*:}" == "$SHA" ]]
digest="$(sed -n "s#^[A-Z0-9_]*=ghcr.io/pirathee587/zentro/$repo@##p" "$FIX/images.env")"
[[ "${MISMATCH:-}" == "$repo" ]] && digest="sha256:$(printf 'f%.0s' {1..64})"
printf '{"digest":"%s"}\n' "$digest"
MOCK
cat > "$ZENTRO_AGENT_ROOT/bin/deploy-app-images" <<'MOCK'
#!/usr/bin/env bash
cat > "$LOG/deployed-manifest"
echo called >> "$LOG/deploy-calls"
if [[ "${DEPLOY_FAIL:-}" == 1 ]]; then echo ZENTRO_CANDIDATE_HEALTH_FAILED >&2; echo ZENTRO_ROLLBACK_OK >&2; exit 1; fi
echo ZENTRO_DEPLOY_OK
MOCK
chmod 755 "$base/mocks/curl" "$base/mocks/docker" "$ZENTRO_AGENT_ROOT/bin/deploy-app-images"
export SHA="$sha"
printf 'GITHUB_DEPLOY_TOKEN=github_pat_%s\n' "$(printf 'A%.0s' {1..40})" > "$ZENTRO_AGENT_ROOT/secrets/github-deploy.env"
chmod 600 "$ZENTRO_AGENT_ROOT/secrets/github-deploy.env"

deployment() { # deployment ID [CREATOR] [EXTRA_PAYLOAD_JSON]
  jq -nc --argjson id "$1" --arg sha "$sha" --arg creator "${2:-github-actions[bot]}" \
    --arg created "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson extra "${3:-{\}}" \
    '{id: $id, sha: $sha, task: "zentro-vm-deploy", environment: "production-vm", created_at: $created,
      creator: {login: $creator}, payload: ({release_run_id: 11, publication_run_id: 10} + $extra)}'
}
reset() { rm -f "$LOG"/* "$ZENTRO_AGENT_ROOT/data/deploy-agent/last-deployment-id"; unset EXISTING_STATUS COMPARE MISSING_APPROVAL MISMATCH DEPLOY_FAIL RUN_SHA; }
calls() { [[ -f "$LOG/deploy-calls" ]] && wc -l < "$LOG/deploy-calls" | tr -d ' ' || echo 0; }
pass=0
check() { if eval "$2"; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1"; exit 1; fi; }

reset; printf '[%s]' "$(deployment 100)" > "$FIX/deployments.json"
bash "$agent" >/dev/null 2>&1
check "approved release is deployed once" '[[ $(calls) == 1 ]] && cmp -s "$LOG/deployed-manifest" "$FIX/images.env"'
check "result reported as in_progress then success" '[[ "$(cat "$LOG/statuses")" == $'"'"'100 in_progress\n100 success'"'"' ]]'
check "deployment ID recorded" '[[ "$(cat "$ZENTRO_AGENT_ROOT/data/deploy-agent/last-deployment-id")" == 100 ]]'
bash "$agent" >/dev/null 2>&1
check "replayed deployment is ignored" '[[ $(calls) == 1 ]]'

reset; printf '[%s]' "$(deployment 101 'some-user')" > "$FIX/deployments.json"
! bash "$agent" >/dev/null 2>&1
check "deployment not created by GitHub Actions is rejected" '[[ $(calls) == 0 && "$(cat "$LOG/statuses")" == "101 failure" ]]'

reset; printf '[%s]' "$(deployment 102)" > "$FIX/deployments.json"; export MISSING_APPROVAL=11
! bash "$agent" >/dev/null 2>&1
check "missing production approval is rejected" '[[ $(calls) == 0 && "$(cat "$LOG/statuses")" == "102 failure" ]]'

reset; printf '[%s]' "$(deployment 103)" > "$FIX/deployments.json"; export MISSING_APPROVAL=10
! bash "$agent" >/dev/null 2>&1
check "missing publication approval is rejected" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 104)" > "$FIX/deployments.json"; export COMPARE=diverged
! bash "$agent" >/dev/null 2>&1
check "commit not on main is rejected" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 105)" > "$FIX/deployments.json"; export RUN_SHA=fedcba9876543210fedcba9876543210fedcba98
! bash "$agent" >/dev/null 2>&1
check "run for a different commit is rejected" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 106)" > "$FIX/deployments.json"; export MISMATCH=caddy
! bash "$agent" >/dev/null 2>&1
check "digest differing from commit tag is rejected" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 107 'github-actions[bot]' '{"manifest":"GATEWAY_IMAGE=evil"}')" > "$FIX/deployments.json"
bash "$agent" >/dev/null 2>&1
check "payload manifest is ignored; run artifact is used" '[[ $(calls) == 1 ]] && cmp -s "$LOG/deployed-manifest" "$FIX/images.env"'

reset; printf '[%s]' "$(deployment 108 'github-actions[bot]' '{"release_run_id":"11"}')" > "$FIX/deployments.json"
! bash "$agent" >/dev/null 2>&1
check "non-numeric run identifiers are rejected" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 109)" > "$FIX/deployments.json"; export DEPLOY_FAIL=1
! bash "$agent" >/dev/null 2>&1
check "failed deployment (with rollback) is reported as failure" '[[ $(calls) == 1 && "$(tail -1 "$LOG/statuses")" == "109 failure" ]]'

reset; printf '[%s,%s]' "$(deployment 111)" "$(deployment 110)" > "$FIX/deployments.json"
bash "$agent" >/dev/null 2>&1
check "older pending deployment is superseded, newest applied" \
  '[[ $(calls) == 1 ]] && grep -qx "110 error" "$LOG/statuses" && grep -qx "111 success" "$LOG/statuses"'

reset; printf '[%s]' "$(deployment 112)" > "$FIX/deployments.json"; export EXISTING_STATUS='[{"state":"success"}]'
bash "$agent" >/dev/null 2>&1
check "already-finished deployment is not redeployed" '[[ $(calls) == 0 ]]'

reset; printf '[%s]' "$(deployment 113)" > "$FIX/deployments.json"; chmod 644 "$ZENTRO_AGENT_ROOT/secrets/github-deploy.env"
! bash "$agent" >/dev/null 2>&1
check "token file with weak permissions stops the agent" '[[ $(calls) == 0 && ! -f "$LOG/statuses" ]]'
chmod 600 "$ZENTRO_AGENT_ROOT/secrets/github-deploy.env"

echo "deploy-agent tests: $pass passed"
