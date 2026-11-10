# ZD-098 / #173: Docker Compose clean-start preflight evidence

## Status and environment

**Partial verification only. Issue #173 remains open.** Automated preflight regressions pass; actual local preflight fails on missing configuration prerequisites. Full startup, application readiness, persistence and recovery were not attempted or passed.

- Date: **2026-11-10**, Asia/Colombo (UTC+05:30).
- Test observation: 15:20:41; preflight observation: 15:21:34.
- Repository: `pirathee587/Zentro`.
- Branch: `feature/173-docker-compose-health-validation`.
- Tested HEAD: `c8f63b7437da7d0ebb63eeeac7fedcdb78d4f8d3`; working tree was clean before evidence collection.
- Windows: `Microsoft Windows NT 10.0.26300.0`.
- Windows PowerShell: `5.1.26100.9711`.
- Docker CLI: `29.9.2`, build `a82e528`; Docker Compose: `v5.6.1`.
- Actual preflight confirmed daemon reachability in the existing Docker context. No engine version, application endpoint or database connectivity claim is made from that check.

The preflight ran outside the agent filesystem sandbox so it could access Docker Desktop's named pipe, using the existing Docker configuration. No Docker installation, reconfiguration or lifecycle operation was performed. No real environment files were created/modified; only filenames and sanitized findings are recorded.

## Exact verification commands

Run from the repository root. Repository and environment identification used:

```powershell
git status
git branch --show-current
git rev-parse HEAD
'POWERSHELL=' + $PSVersionTable.PSVersion.ToString()
'OS=' + [Environment]::OSVersion.VersionString
Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'
docker compose version
docker --version
```

The focused suite was executed first, with its native process exit captured immediately:

```powershell
powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.Tests.ps1
'TEST_EXIT_CODE=' + $LASTEXITCODE
```

The actual standalone preflight was then run with this in-memory capture/count wrapper. It does not save configuration or raw Docker diagnostics:

```powershell
$lines = @(powershell.exe -NoProfile -File .\scripts\Test-ComposePreflight.ps1 2>&1)
$preflightExit = $LASTEXITCODE
$lines
'PREFLIGHT_EXIT_CODE=' + $preflightExit
foreach ($status in @('PASS','FAIL','WARN','SKIP')) {
    $status + '=' + @($lines | Where-Object { $_ -match ('^' + $status + ' ') }).Count
}
'UNEXPECTED_OUTPUT_LINES=' + @($lines | Where-Object { $_ -notmatch '^(PASS|FAIL|WARN|SKIP) ' }).Count
Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'
```

The script internally uses Docker version/info, Compose help, source-only JSON parsing without environment interpolation/loading, and eligible quiet configuration validation. JSON is retained in memory only; native diagnostics and environment values are not printed. See the [preflight guide](../testing/compose-preflight.md) for flags, required-file inventory and limits.

## Observed results

| Execution | Actual result | Process exit |
|---|---|---:|
| Focused automated suite | **62 passed, 0 failed** | 0 |
| Actual local preflight | **24 PASS, 6 FAIL, 73 WARN, 10 SKIP** | 1 |

There were **0 unexpected output lines** in the actual preflight capture. Standalone initialization reached checks without an exception. Exit `1` is a validation result, not a parameter-binding failure. All six FAIL lines named missing required backend environment files:

```text
backend/identity-partner-service/.env
backend/api-gateway/.env
backend/parcel-pricing-service/.env
backend/dispatch-courier-service/.env
backend/tracking-reporting-service/.env
backend/notification-integration-service/.env
```

`infra/keycloak/.env` exists. Its contents were not displayed or audited. Root `.env`, `.env.common` and frontend `.env` are not part of the preflight's required-file inventory; no assumption about their presence or values is needed for these results.

| Definition | Source inspection | Quiet configuration validation |
|---|---|---|
| `docker-compose.yml` | Completed; required dependency references exist and graph is acyclic | **SKIP:** required backend environment files missing |
| `backend/docker-compose.yml` | Completed; required dependency references exist and graph is acyclic | **SKIP:** required Identity/Gateway environment files missing |
| `infra/keycloak/docker-compose.keycloak.yml` | Completed; declared graph is acyclic | **PASS:** quiet configuration validation passed |

Source parsing does not load service environment files, so it can complete while quiet validation is skipped. Acyclic declared graphs do not prove implicit API/event dependencies are ready. Keycloak configuration PASS does not establish valid credentials, database connectivity, a healthy container or successful authentication.

## Verified configuration concerns and evidence limits

- Root Compose declares health tests for S1-S5. Their command text identifies liveness; the preflight warns that dependency readiness is not established. None of those probes was executed.
- Gateway, frontend and Keycloak have no health test in their inspected Compose definitions. Backend-only S1 also lacks a Compose-level test. These are WARN/SKIP findings: effective image/Dockerfile inheritance was not inspected by this run, so an effective container probe is not declared absent.
- Root Gateway waits only for S1 container startup. Backend-only Gateway waits for service health, whose effective probe still requires verification. Other services declare no dependency gates; that is a concern to review, not proof each needs a gate.
- Root/backend definitions do not provision their documented databases or Keycloak. Root also lacks Kafka for enabled S2/S4/S5 messaging. The inventory reports these external prerequisites without reading endpoints or feature flags. External management is not automatically a defect, and no external dependency was contacted to test availability.
- S1-S5 have no service data mount; application persistence depends on their separately configured databases. The separate Keycloak definition declares a named volume plus two bind mounts. Declaration/classification does not establish writable paths, database storage selection, ownership, durability or data surviving restarts.
- Restart policies are declared for the inspected services. Their presence does not prove application recovery or automatic restart in response to an unhealthy status.
- Explicit container names, network names/external networks and published ports raise isolation warnings. Existing resource ownership and port availability were not checked. A new Compose project name alone does not establish isolation.
- Every definition ends with a warning that repeatable clean-start behavior cannot be certified from source inspection alone.

The six missing files are immediate blockers to the eligible configuration checks, **not an exhaustive list of startup blockers**. Image builds, configuration contents, issuer/JWKS connectivity, migrations, credentials, database privileges, Kafka enablement and application workflows remain untested. No image build or service test outside the focused preflight suite ran in this evidence collection.

## Why full startup was not attempted

This task authorized non-destructive preflight evidence only. Missing backend prerequisites prevent a configured full-stack trial, and shared/fixed resources require an isolation plan. Starting the current definitions could attach to existing infrastructure or conflict with other work. No containers, images, networks, volumes, real databases or environment files were changed. Automated tests used disposable fixtures, mocked Docker calls and a test-only command shim; their temporary fixtures were cleaned up.

## Pending isolated-runtime acceptance criteria

These are proposed acceptance criteria, **not executed results**. Obtain approval and coordinate the environment owner before running them:

1. Agree required services/features, startup timeout and #173/#259 responsibilities; record the source SHA and test environment identifier.
2. Allocate dedicated project-scoped container/network names, unused loopback ports, fresh test-only volumes, separate service databases/users, isolated identity storage and Kafka. Verify that no mount, database URL or external network targets shared resources; do not overwrite real environment files or reuse shared identity data.
3. Resolve prerequisite configuration privately, align browser/internal identity addressing and explicitly select messaging features. Require quiet configuration validation to pass for each intended definition and then separately verify image builds.
4. Demonstrate bounded clean startup and suitable health/readiness behavior per component, including dependency ordering. Confirm readiness does not pass solely because a process is alive.
5. Verify approved authenticated workflows and negative authorization cases through Gateway while retaining independent service JWT validation and database ownership boundaries.
6. Write test-only records via approved APIs, restart only the explicitly approved isolated resources, and verify records, event processing/offset behavior and identity state survive as required. Do not infer this from named-volume declarations.
7. Exercise controlled dependency loss and restoration in the isolated stack; verify recovery and lack of duplicate processing. Do not treat a restart policy as recovery evidence.
8. Repeat from a fresh, separately named test environment; retain sanitized timing/status evidence. Inventory all test resources, verify shared resources remain untouched and agree a separately authorized, narrowly scoped cleanup procedure without global pruning or deleting existing data.

## Ownership, blockers and follow-up

GitHub issue bodies/state were read during this collection. No issue comments, assignments or statuses were changed.

| Issue | Verified owner / reviewer | Boundary and next action |
|---|---|---|
| [#173 / ZD-098](https://github.com/pirathee587/Zentro/issues/173) (open) | @pavi3alnx / @Deadsecnote1 | Own this focused preflight/evidence; arrange review, identify safe private test configuration and track outstanding acceptance evidence. |
| [#259 / ZD-162](https://github.com/pirathee587/Zentro/issues/259) (open) | @pirathee587 / @14Jannan | Substantially overlaps clean-environment, dependency, persistence and secrets verification. Agree one integrated runtime plan and reuse this evidence; do not create duplicate infrastructure. Its body lists #258 as a prerequisite; #258 completion was not verified here. |
| [#172 / ZD-097](https://github.com/pirathee587/Zentro/issues/172) (open) | @Nilakshana23 / @pavi3alnx | Own dependency/image scan remediation. Coordinate Dockerfile/dependency edits there; no scan results or remediation are claimed by this report. |

The working boundary above is a coordination proposal, not an established exclusive split in the issue bodies. Neither #259 nor #172 is asserted to be a proven blocking dependency of #173 merely because scopes overlap.

| Remaining item | Responsible party / next action |
|---|---|
| Six missing backend environment files | #173 owner @pavi3alnx coordinates private test configuration with service owners; specific database/credential provisioning owners are **not verified**. Do not copy shared credentials or overwrite existing files. |
| External databases, Kafka and identity prerequisites | Confirm dedicated environment/provisioning owners with #259 owner @pirathee587 and service maintainers; existence, reachability and ownership remain unverified. |
| Health/readiness and fixed-resource concerns | #173 owner coordinates findings and any proposed shared-file changes with maintainers and #259 owner; none are fixed or runtime-tested by this documentation commit. |
| Runtime startup, persistence, recovery and repeatability | Joint scope must be agreed with #259 owner; execute only after explicit approval and isolation review. |
| Independent acceptance | #173 reviewer @Deadsecnote1 must assess evidence. PR, CI/build evidence, merged SHA and reviewer sign-off remain required by the issue; this report does not satisfy or invent them. |

**Conclusion:** local developer preflight implementation is regression-tested, but the current environment is not ready for full clean-start acceptance. This report is factual partial evidence, not completion of Issue #173.
