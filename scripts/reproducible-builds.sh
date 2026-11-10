#!/usr/bin/env bash
# Local opt-in only: two clean backend packages. Never deploys, tags or publishes.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel)
if [[ $# -ne 1 ]]; then echo "Usage: $0 ABSOLUTE_EVIDENCE_DIRECTORY" >&2; exit 2; fi
output=$1
[[ "$output" = /* && ! -e "$output" ]] || { echo 'Evidence directory must be absolute and new' >&2; exit 2; }
mkdir -p "$output"
build_snapshot=$(git -C "$repo_root" rev-parse HEAD)
clone_dir=$(mktemp -d /tmp/zentro-repro-184.XXXXXX)
trap 'rm -rf -- "$clone_dir"' EXIT
git clone --quiet --no-hardlinks --no-local "$repo_root" "$clone_dir/source"
git -C "$clone_dir/source" checkout --quiet --detach "$build_snapshot"
printf 'sourceSHA=%s\n' "$build_snapshot" > "$output/inputs.txt"
printf 'command=mvn --batch-mode --no-transfer-progress -DskipTests -Dbuild.revision=SOURCE_SHA clean package\n' >> "$output/inputs.txt"
for run in 1 2; do
  (cd "$clone_dir/source/backend" && mvn --batch-mode --no-transfer-progress -DskipTests "-Dbuild.revision=$build_snapshot" clean package) > "$output/build-$run.log" 2>&1
  java "$script_dir/ci/ArtifactInventory.java" inventory "$clone_dir/source/backend" "$output/run-$run"
done
if diff -ru "$output/run-1" "$output/run-2" > "$output/artifact-diff.txt"; then
  echo 'PASS: whole-JAR hashes and complete entry inventories match (nothing ignored)'
else
  echo "FAIL: artifacts differ; full entry/hash differences: $output/artifact-diff.txt" >&2
  exit 1
fi
