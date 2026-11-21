#!/usr/bin/env bash
set -euo pipefail

# Windows ACL validation and private credential loading are implemented in the
# PowerShell entry point. This wrapper deliberately contains no credentials and
# performs no Keycloak requests itself.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  script_path="$(cygpath -w "$script_dir/seed-users.ps1")"
elif command -v wslpath >/dev/null 2>&1; then
  script_path="$(wslpath -w "$script_dir/seed-users.ps1")"
else
  echo 'Secure seed workflow requires Windows PowerShell path conversion; no action taken.' >&2
  exit 1
fi
if ! command -v powershell.exe >/dev/null 2>&1; then
  echo 'Windows PowerShell is unavailable; no action taken.' >&2
  exit 1
fi
exec powershell.exe -NoLogo -NoProfile -NonInteractive -File "$script_path"
