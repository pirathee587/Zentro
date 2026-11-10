# Docker Compose startup preflight (#173)

Run from the repository root in Windows PowerShell 5.1 or later:

```powershell
powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.ps1
$LASTEXITCODE
```

The default repository root is resolved from the script location, independent of the current directory. An alternate checkout can be selected with `-RepositoryRoot <directory>`.

## Results

- `PASS`: a prerequisite succeeded, a declared health test/volume satisfies WARP.md section 18's configuration requirement, dependency references form an acyclic graph, or a restart policy is declared. This is not runtime verification.
- `FAIL`: a required prerequisite/configuration check failed, the source model is invalid, or a health test is explicitly disabled contrary to WARP.md section 18. The process exits `1` if any check fails; otherwise it exits `0`.
- `WARN`: source inspection identifies a potential readiness/isolation concern requiring further verification. Warnings alone do not make the process fail.
- `SKIP`: inspection cannot be performed (for example, missing CLI, unsupported source-inspection flags, or no health test to classify). Quiet configuration validation is also skipped when required environment files are missing. A skip does not erase a prerequisite failure.

The script checks Docker CLI execution, Compose availability and daemon reachability in the current Docker context. Daemon failure does not prevent local Compose configuration validation when Compose and the necessary files are available.

The required file inventory matches the current three Compose definitions:

- Root Compose: the six `backend/<service>/.env` files (Gateway and S1-S5).
- Backend Compose: Identity & Partner and Gateway `.env` files.
- Keycloak Compose: `infra/keycloak/.env`, explicitly supplied with `--env-file` for interpolation.

Every required file is reported by repository-relative filename only. Root `.env`, `.env.common` and frontend `.env` are not required by these checks: the first is optional interpolation input, and the latter two are not loaded by the current Compose definitions. Keep the inventory synchronized if definitions change.

Each eligible definition is validated with `docker compose ... config -q`. Missing files skip only affected stacks. All native output, including errors, is discarded because Compose diagnostics can contain interpolated secrets. A configuration failure therefore gives a generic message rather than the underlying error text. Correct private configuration using the [development configuration guide](../development/configuration.md); this script never copies templates or changes configuration.

## Health, readiness and isolation source inspection

Each existing definition is also inspected even when service environment files or the daemon are unavailable, provided the Compose CLI is available. Compose's own parser is used rather than a partial YAML parser:

```text
docker compose --env-file NUL -f <definition> config --format json --no-interpolate --no-env-resolution --no-normalize --no-path-resolution
```

`NUL` is the Windows null device. These flags prevent environment interpolation and service environment-file loading; JSON stays in memory and is never printed or saved. Native diagnostics are suppressed. If Compose does not advertise `--no-env-resolution`, source inspection is skipped. Other parser/flag failures are reported generically as failures. Existing quiet validation remains separate and does read its normal environment inputs.

Every service is inspected, including Gateway, frontend and Keycloak:

- A declared health command passes the configuration-presence check; it is never executed. Explicit `disable: true` or `test: [NONE]` fails. An absent/empty test warns because image/Dockerfile inheritance is unknown; it does not prove the effective container lacks a probe.
- Commands containing `liveness` or `readiness` are classified only by their text and warn about the unverified complementary behavior. Generic/custom commands warn that their semantics are unknown. No common endpoint is imposed on all services.
- Dependency checks distinguish `service_healthy`, `service_started`, short syntax and `service_completed_successfully`. All retain readiness warnings: even a health gate may target a liveness-only or inherited probe. A health gate targeting an explicitly disabled check fails. No gates warns; it does not assert that every service needs a dependency.
- Explicit container names, explicitly named/external networks or volumes, and published host ports warn about cross-project sharing/collision. Resource-name values, port values, command bodies and environment values are not printed; reports identify services by their Compose service keys. Dynamic interpolation/port ranges are not resolved, and port availability is not checked. No isolation PASS is inferred merely from a project name.

This inspects the specified files only, not implicit overrides, built image metadata, Dockerfile stages, remote image health checks or live resources. Compose references must be trusted. Changing shared Compose definitions, Dockerfiles and runtime verification remain outside this commit's scope.

## Startup dependencies and persistence

Required `depends_on` references must exist within the inspected definition and declared edges must not form cycles (including self-dependencies). Invalid references/cycles fail; an absent optional dependency (`required: false`) warns. An acyclic graph with valid required references passes only the declared-graph check. Compose may reject an invalid graph before returning JSON; that produces the existing generic source-inspection failure. These checks do not infer API calls, profile-specific availability, links or other implicit dependencies. Existing started/healthy/completed condition findings are reused, not duplicated.

The current project's documented prerequisites are inventoried by known service keys, without reading endpoint/credential values:

- S1-S5 each require their separately configured service database and Keycloak; Gateway also requires Keycloak.
- S2, S4 and S5 require Kafka when their associated messaging features are enabled. Feature enablement is not inspected.
- Root Compose provisions neither PostgreSQL nor Kafka and relies on separately started Keycloak. Backend Compose also relies on external dependencies. See [development configuration](../development/configuration.md).

A service key or recognized PostgreSQL/Kafka/Keycloak image family identifies only a candidate in the definition. It does not prove endpoints are wired to it, that five separate databases/users exist, or that it is reachable. An absent candidate warns that the documented prerequisite must be supplied separately; externally managed databases are not automatically defects. This is a deliberately small project-specific inventory, not generic connection discovery. Custom service names/images and other data stores may require manual review. Definitions are inspected independently; the separate Keycloak definition is not automatically merged into root Compose.

Persistence classification supports short and long volume syntax:

- Declared named volumes pass the configuration-presence check; undeclared named volume references fail. Externally managed volumes warn that existence and ownership require verification.
- Bind mounts warn about host paths, permissions, read-only intent and durability; configuration/theme binds are not mistaken for proven application data storage. Host paths are neither printed nor accessed.
- Anonymous and memory-backed mounts warn about repeatable reuse/durability. Unknown mount types skip classification.
- S1-S5 with no data mount warn that their data persistence depends on their separate database. Other services without mounts skip classification; stateless services are not assumed defective.

Restart policies are reported as declared or absent/disabled; no policy is treated as proof of recovery. Existing container/network/port isolation findings are reused. Every successfully inspected model ends with a clean-start-plan finding: known configuration failures fail; otherwise a warning says that prerequisites, isolation and runtime evidence still need a separate plan. No fully isolated/repeatable startup PASS is inferred from source alone. Database data surviving restart, migration behavior, Kafka offsets, volume retention, application recovery and port availability require an approved isolated runtime test.

## Issue ownership boundary

#173 provides this focused developer preflight and source-configuration evidence. #259 (@pirathee587) substantially overlaps clean-environment verification; coordinate the final integrated test environment, dependency provisioning, persistence/recovery scenarios and acceptance evidence with its owner rather than creating a second stack/harness. This is a proposed working boundary, not an issue-authorized exclusive ownership split. No shared Compose infrastructure, image remediation (#172), environment files, deployment configuration or CI workflows are changed here. Neither issue is complete solely because preflight tests pass.

## Safety and limits

The only Docker invocations are `--version`, `compose version`, `info`, `compose config --help`, quiet configuration validation and the source-only JSON command above. No containers, images, networks or volumes are changed. The script makes no application/database requests, writes no files and never prints environment contents or resolved configuration. Compose itself reads existing environment inputs for quiet validation. Run trusted repository definitions with a trusted Docker executable.

A pass is not proof of successful builds, healthy services, reachable databases/Kafka, valid credentials, persistence or recovery. Those runtime checks remain separate work. Docker commands use the existing context and may wait for its connection timeout; the script does not start Docker or change context/settings to resolve failures.

## Regression tests

```powershell
powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.Tests.ps1
```

No Pester installation is needed. Tests mock Docker discovery/invocations and use GUID-named temporary fixtures, cleaned up only within the OS temporary directory. They cover success, missing/broken CLI, missing Compose, unreachable daemon, failed configuration, missing environment/Compose files, command allowlisting, working-directory restoration and unchanged fixture contents. Source-model cases cover declared/missing/disabled health checks, dependency conditions, probe labels, naming/ports, malformed/empty models, parser exceptions, unavailable flags and secret suppression. Warnings-only success and source-failure exit behavior are also checked. Harmless child PowerShell processes verify native stdout/stderr suppression and exit-code handling; they do not invoke Docker.

Additional source fixtures cover valid/missing graph references, cycles, external versus declared dependency candidates, named/external/undeclared/anonymous volumes, bind mounts (including Windows paths), memory-backed mounts, restart policies and clean-start-plan outcomes. Existing resource-name/port, malformed-model and suppressed-diagnostic tests remain in the same focused suite. No real Docker resources are used by these tests.
