#!/usr/bin/env bash
# Zentro VM pull deployment agent. Installed as /opt/zentro/bin/deploy-agent (root:root 0750) and run by
# zentro-deploy-agent.timer every two minutes. Outbound HTTPS only; no inbound port or runner is needed.
#
# It applies the newest "zentro-vm-deploy" GitHub Deployment only when GitHub itself proves that:
#   - the deployment was created by GitHub Actions for a 40-hex commit that is on main;
#   - the release run and publication run are runs of .github/workflows/production.yml on main in this
#     repository, for that same commit, and the publish job succeeded;
#   - the production-publication and production environments were approved by a reviewer;
#   - the nine-image manifest comes from the publication run's own artifact (never from the payload) and
#     every digest equals the commit-tagged GHCR image.
# Each deployment ID is processed at most once (replay protection); older pending ones are superseded.
# The existing deploy-app-images script performs locking, pulls, health checks and rollback, and its
# result marker is reported back to GitHub. No credential or token is ever printed.
set -euo pipefail
umask 077

root="${ZENTRO_AGENT_ROOT:-/opt/zentro}"
repo=pirathee587/Zentro
api="https://api.github.com/repos/$repo"
registry=ghcr.io/pirathee587/zentro
task=zentro-vm-deploy
environment=production-vm
workflow=.github/workflows/production.yml
token_file="$root/secrets/github-deploy.env"
state_dir="$root/data/deploy-agent"
deploy="$root/bin/deploy-app-images"
max_age_seconds=7200
keys=(GATEWAY_IMAGE S1_IMAGE S2_IMAGE S3_IMAGE S4_IMAGE S5_IMAGE KAFKA_IMAGE KEYCLOAK_IMAGE CADDY_IMAGE)
repositories=(api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service
  tracking-reporting-service notification-integration-service kafka keycloak caddy)

[[ $EUID == 0 ]]
install -d -m 0700 "$state_dir"
exec 8> "$state_dir/agent.lock"
flock -n 8 || exit 0

[[ -f "$token_file" && ! -L "$token_file" && "$(stat -c '%u:%a' "$token_file")" == 0:600 ]] ||
  { echo "deploy-agent: $token_file must exist as root:root 0600" >&2; exit 1; }
token="$(sed -n 's/^GITHUB_DEPLOY_TOKEN=//p' "$token_file")"
[[ "$token" =~ ^(github_pat_|ghp_)[A-Za-z0-9_]+$ ]] || { echo "deploy-agent: invalid token format" >&2; exit 1; }

work="$(mktemp -d "$state_dir/work.XXXXXX")"
trap 'rm -f -- "$work"/*; rmdir -- "$work"' EXIT

github() { # github URL [curl args...]; the token travels in a curl config on stdin, never in argv
  local url="$1"; shift
  printf 'header = "Authorization: Bearer %s"\n' "$token" |
    curl -fsS --proto '=https' --connect-timeout 10 --max-time 60 -K - \
      -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' "$@" "$url"
}
report() { # report ID STATE DESCRIPTION [LOG_URL]
  jq -nc --arg state "$2" --arg description "${3:0:140}" --arg log "${4:-}" \
    '{state: $state, description: $description, auto_inactive: true} + (if $log == "" then {} else {log_url: $log} end)' \
    > "$work/status.json"
  github "$api/deployments/$1/statuses" -X POST --data-binary "@$work/status.json" -o /dev/null
}

deployments="$(github "$api/deployments?environment=$environment&task=$task&per_page=20")"
last="$(cat "$state_dir/last-deployment-id" 2>/dev/null || echo 0)"
[[ "$last" =~ ^[0-9]+$ ]]
mapfile -t pending < <(jq -r --argjson last "$last" '[.[] | select(.id > $last)] | sort_by(.id) | .[].id' <<< "$deployments")
[[ ${#pending[@]} -gt 0 ]] || exit 0
id="${pending[-1]}"
# Record before acting: a deployment ID is never processed twice, even after a crash.
printf '%s\n' "$id" > "$state_dir/last-deployment-id.new"; mv -f -- "$state_dir/last-deployment-id.new" "$state_dir/last-deployment-id"
for older in "${pending[@]:0:${#pending[@]}-1}"; do
  report "$older" error "Superseded by newer approved deployment $id" || true
done

deployment="$(jq -c --argjson id "$id" '.[] | select(.id == $id)' <<< "$deployments")"
current_state="$(github "$api/deployments/$id/statuses?per_page=1" | jq -r '.[0].state // "none"')"
case "$current_state" in none|queued|pending) ;; *) echo "deploy-agent: deployment $id already $current_state"; exit 0 ;; esac

reject() { echo "deploy-agent: rejected deployment $id: $1" >&2; report "$id" failure "Rejected by VM agent: $1" "${run_url:-}" || true; exit 1; }

# 1. The deployment record itself.
sha="$(jq -r '.sha' <<< "$deployment")"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || reject "invalid commit"
jq -e --arg task "$task" --arg env "$environment" \
  '.creator.login == "github-actions[bot]" and .task == $task and .environment == $env' <<< "$deployment" >/dev/null ||
  reject "not created by GitHub Actions for $environment"
created="$(date -d "$(jq -r '.created_at' <<< "$deployment")" +%s)"
(( $(date +%s) - created <= max_age_seconds )) || reject "deployment older than two hours"
release_run="$(jq -r '.payload.release_run_id | numbers' <<< "$deployment")"
publication_run="$(jq -r '.payload.publication_run_id | numbers' <<< "$deployment")"
[[ "$release_run" =~ ^[1-9][0-9]*$ && "$publication_run" =~ ^[1-9][0-9]*$ ]] || reject "missing run identifiers"

# 2. The commit is on main.
github "$api/compare/$sha...main" | jq -e '.status == "identical" or .status == "ahead"' >/dev/null ||
  reject "commit is not on main"

# 3. Both runs are this repository's production workflow on main for this commit, with approvals.
verify_run() { # verify_run RUN_ID ENVIRONMENT_THAT_MUST_BE_APPROVED
  github "$api/actions/runs/$1" > "$work/run.json"
  jq -e --arg sha "$sha" --arg repo "$repo" --arg path "$workflow" '
    .head_sha == $sha and .head_branch == "main" and .path == $path
    and .repository.full_name == $repo and .head_repository.full_name == $repo
    and (.event == "push" or .event == "workflow_dispatch")' "$work/run.json" >/dev/null || return 1
  github "$api/actions/runs/$1/approvals" |
    jq -e --arg env "$2" 'any(.[]; .state == "approved" and any(.environments[]; .name == $env))' >/dev/null
}
verify_run "$release_run" production || reject "release run provenance or production approval missing"
run_url="$(jq -r '.html_url' "$work/run.json")"
github "$api/actions/runs/$release_run/jobs?per_page=100" |
  jq -e 'any(.jobs[]; .name == "vm-release" and .status == "in_progress")' >/dev/null || reject "release job not active"
verify_run "$publication_run" production-publication || reject "publication run provenance or approval missing"
github "$api/actions/runs/$publication_run/jobs?per_page=100" |
  jq -e 'any(.jobs[]; .name == "publish" and .conclusion == "success")' >/dev/null || reject "publish job did not succeed"

# 4. The manifest comes from the publication run's artifact, not from the deployment payload.
artifact="$(github "$api/actions/runs/$publication_run/artifacts?name=production-image-manifest" |
  jq -er '[.artifacts[] | select(.expired == false)] | if length == 1 then .[0].archive_download_url else error("count") end')" ||
  reject "manifest artifact unavailable"
github "$artifact" -L --max-filesize 65536 -o "$work/manifest.zip" || reject "manifest download failed"
python3 -I - "$work/manifest.zip" "$work/images.env" <<'PY' || reject "manifest archive invalid"
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    if names != ["images.env"] or archive.getinfo("images.env").file_size > 4096:
        raise SystemExit(1)
    open(sys.argv[2], "wb").write(archive.read("images.env"))
PY
mapfile -t entries < "$work/images.env"
[[ ${#entries[@]} -eq ${#keys[@]} ]] || reject "manifest must list nine images"
for i in "${!keys[@]}"; do
  prefix="${keys[i]}=$registry/${repositories[i]}@sha256:"
  [[ "${entries[i]}" == "$prefix"* && "${entries[i]#"$prefix"}" =~ ^[a-f0-9]{64}$ ]] || reject "manifest line $((i + 1)) invalid"
  tagged="$(docker buildx imagetools inspect "$registry/${repositories[i]}:$sha" --format '{{json .Manifest}}' 2>/dev/null |
    jq -r '.digest')" || reject "${repositories[i]} commit tag not readable"
  [[ "$tagged" == "sha256:${entries[i]#"$prefix"}" ]] || reject "${repositories[i]} digest differs from its commit tag"
done

# 5. Deploy with the existing script and report its marker.
report "$id" in_progress "VM agent deploying ${sha:0:12}" "$run_url"
echo "deploy-agent: deploying deployment $id (${sha:0:12})"
status=0
output="$("$deploy" < "$work/images.env" 2>&1)" || status=$?
markers="$(grep -Eo 'ZENTRO_[A-Z_]+' <<< "$output" | sort -u | tr '\n' ' ' || true)"
if [[ $status == 0 && " $markers " == *" ZENTRO_DEPLOY_OK "* ]]; then
  report "$id" success "ZENTRO_DEPLOY_OK ${sha:0:12}" "$run_url"
  echo "deploy-agent: deployment $id succeeded"
else
  report "$id" failure "Deployment failed: ${markers:-no marker} (exit $status)" "$run_url" || true
  echo "deploy-agent: deployment $id failed: ${markers:-no marker} (exit $status)" >&2
  exit 1
fi
