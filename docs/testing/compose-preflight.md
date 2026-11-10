# Docker Compose startup preflight (#173)

Run from the repository root in Windows PowerShell 5.1 or later:

```powershell
powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.ps1
$LASTEXITCODE
```

The default repository root is resolved from the script location, independent of the current directory. An alternate checkout can be selected with `-RepositoryRoot <directory>`.

## Results

- `PASS`: the individual prerequisite check succeeded.
- `FAIL`: a required prerequisite is unavailable or a check failed. The process exits `1` if any check fails; otherwise it exits `0`.
- `SKIP`: a check cannot run because its CLI or environment-file prerequisite failed. A skip does not erase that prerequisite failure.

The script checks Docker CLI execution, Compose availability and daemon reachability in the current Docker context. Daemon failure does not prevent local Compose configuration validation when Compose and the necessary files are available.

The required file inventory matches the current three Compose definitions:

- Root Compose: the six `backend/<service>/.env` files (Gateway and S1-S5).
- Backend Compose: Identity & Partner and Gateway `.env` files.
- Keycloak Compose: `infra/keycloak/.env`, explicitly supplied with `--env-file` for interpolation.

Every required file is reported by repository-relative filename only. Root `.env`, `.env.common` and frontend `.env` are not required by these checks: the first is optional interpolation input, and the latter two are not loaded by the current Compose definitions. Keep the inventory synchronized if definitions change.

Each eligible definition is validated with `docker compose ... config -q`. Missing files skip only affected stacks. All native output, including errors, is discarded because Compose diagnostics can contain interpolated secrets. A configuration failure therefore gives a generic message rather than the underlying error text. Correct private configuration using the [development configuration guide](../development/configuration.md); this script never copies templates or changes configuration.

## Safety and limits

The only Docker invocations are `--version`, `compose version`, `info` and quiet Compose `config` commands. No containers, images, networks or volumes are changed. The script makes no application/database requests, writes no files and never prints environment contents or resolved configuration. Compose itself reads existing environment inputs for validation. Run trusted repository definitions with a trusted Docker executable.

A pass is not proof of successful builds, healthy services, reachable databases/Kafka, valid credentials, persistence or recovery. Those runtime checks remain separate work. Docker commands use the existing context and may wait for its connection timeout; the script does not start Docker or change context/settings to resolve failures.

## Regression tests

```powershell
powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.Tests.ps1
```

No Pester installation is needed. Tests mock Docker discovery/invocations and use GUID-named temporary fixtures, cleaned up only within the OS temporary directory. They cover success, missing/broken CLI, missing Compose, unreachable daemon, failed configuration, missing environment/Compose files, command allowlisting, working-directory restoration and unchanged fixture contents. Harmless child PowerShell processes verify native stdout/stderr suppression and exit-code handling; they do not invoke Docker.
