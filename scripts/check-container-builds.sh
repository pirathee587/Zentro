#!/usr/bin/env bash
# Build-only check. All named images are local, scoped to this process and removed on exit.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel)
docker_bin=${ZENTRO_DOCKER_BIN:-docker}
builder=("$docker_bin" build)
if [[ -n ${ZENTRO_BUILDX_BIN:-} ]]; then builder=("$ZENTRO_BUILDX_BIN" build --load);fi
[[ $# -ge 1 && $# -le 2 ]] || { echo "Usage: $0 OUTPUT_DIRECTORY [--twice]" >&2; exit 2; }
output=$1
[[ ! -e "$output" ]] || { echo 'Output directory must be new' >&2; exit 2; }
mkdir -p "$output"
revision=$(git -C "$repo_root" rev-parse HEAD)
backend_version=$("$script_dir/verify-ci-release.sh" version "$repo_root")
frontend_version=$("$script_dir/verify-ci-release.sh" frontend-version "$repo_root")
runs=1
if [[ ${2:-} == --twice ]]; then runs=2; elif [[ $# == 2 ]]; then echo 'Unknown option' >&2; exit 2; fi
images=()
cleanup() {
  for image in "${images[@]}"; do
    if "$docker_bin" image inspect "$image" >/dev/null 2>&1; then "$docker_bin" image rm "$image" >/dev/null; fi
  done
}
trap cleanup EXIT
printf 'run\timage\tid\trevision\tversion\n' > "$output/image-ids.tsv"
for ((run=1;run<=runs;run++)); do
  for service in api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service tracking-reporting-service notification-integration-service frontend; do
    context="$repo_root/backend"; file="$repo_root/backend/$service/Dockerfile"; version="$backend_version"
    if [[ $service == identity-partner-service ]]; then context=$repo_root; fi
    if [[ $service == frontend ]]; then context="$repo_root/frontend/zentro-web";file="$context/Dockerfile";version=$frontend_version;fi
    image="zentro-ci-184-$$-$service:run-$run"
    images+=("$image")
    extra=()
    if [[ $run == 2 ]]; then extra+=(--no-cache);fi
    DOCKER_BUILDKIT=1 "${builder[@]}" "${extra[@]}" --build-arg "GIT_SHA=$revision" --build-arg "APP_VERSION=$version" -f "$file" -t "$image" "$context" > "$output/$service-$run.log" 2>&1
    identity=$("$docker_bin" image inspect "$image" --format '{{.Id}}')
    label_revision=$("$docker_bin" image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
    label_version=$("$docker_bin" image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.version"}}')
    [[ "$label_revision" == "$revision" && "$label_version" == "$version" ]] || { echo "Traceability label mismatch: $service" >&2;exit 1; }
    printf '%s\t%s\t%s\t%s\t%s\n' "$run" "$service" "$identity" "$label_revision" "$label_version" >> "$output/image-ids.tsv"
    echo "Built $service run $run: $identity"
  done
done
