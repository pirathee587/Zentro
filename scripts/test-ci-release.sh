#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fixtures="$script_dir/test-resources/ci-release"
fixture_temp=$(mktemp -d /tmp/zentro-184-guards.XXXXXX)
trap 'rm -rf -- "$fixture_temp"' EXIT
checks=0
expect_pass() {
  if "$@" > "$fixture_temp/result.log" 2>&1; then checks=$((checks+1));else cat "$fixture_temp/result.log" >&2;exit 1;fi
}
expect_fail() {
  if "$@" > "$fixture_temp/result.log" 2>&1; then echo "Expected guard rejection: $*" >&2;exit 1;else checks=$((checks+1));fi
}
guard="$script_dir/verify-ci-release.sh"
expect_pass "$guard" versions "$fixtures/stable"
expect_fail "$guard" versions "$fixtures/drift"
expect_fail "$guard" release "$fixtures/snapshot" --scheme semver --prefix v --tag v3.2.1-SNAPSHOT
expect_fail "$guard" release "$fixtures/stable" --tag v3.2.1
# Explicit fixture policy; this does not approve a repository release convention.
expect_pass "$guard" release "$fixtures/stable" --scheme semver --prefix v --tag v3.2.1
for tag in release-3.2.1 v3.2 3.2.1 v3.2.2 v3.2.1-rc.1; do
  expect_fail "$guard" release "$fixtures/stable" --scheme semver --prefix v --tag "$tag"
done
expect_fail "$guard" release "$fixtures/prerelease" --scheme semver --prefix v --tag v3.2.1-rc.1
expect_pass "$guard" release "$fixtures/prerelease" --scheme semver --prefix v --allow-prerelease true --tag v3.2.1-rc.1
mkdir -p "$fixture_temp/repo/.github/workflows"
cp "$fixtures/workflows/safe.yml" "$fixture_temp/repo/.github/workflows/check.yml"
expect_pass "$guard" workflows "$fixture_temp/repo" --strict-pins
for fixture in continue-on-error or-true target-checkout mutable-action; do
  cp "$fixtures/workflows/$fixture.yml" "$fixture_temp/repo/.github/workflows/check.yml"
  expect_fail "$guard" workflows "$fixture_temp/repo" --strict-pins
done
mkdir -p "$fixture_temp/junit"
cp "$fixtures/junit.xml" "$fixture_temp/junit/TEST-fixture.xml"
expect_pass java "$script_dir/ci/ArtifactInventory.java" sanitize "$fixture_temp/junit" "$fixture_temp/safe-reports"
if rg -q 'DO_NOT_EXPORT|<properties|system-out|system-err' "$fixture_temp/safe-reports"; then echo 'Unsafe report export' >&2;exit 1;fi
checks=$((checks+1))
echo "PASS: $checks positive/negative guard and report-sanitization cases"
