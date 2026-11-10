# CI build artifacts and release verification (#184)

**Release acceptance is incomplete.** There is no approved release-tag/version policy, `main` does not exist, and no release operation is authorised. The owner explicitly chose to leave the convention open. This task verifies and hardens build-only paths; it does not approve a release.

No project tag, GitHub release, registry package/image publication or deployment was created or manually triggered. No repository setting, protection/ruleset, secret, variable or environment was changed. Images used for local verification are task-scoped and removed after the checks. Deployment, Kubernetes and Helm remain with the designated deployment owner, **to be assigned** from the available ownership evidence.

## Findings, serious items first

| ID | Classification / severity | Evidence | Disposition |
|---|---|---|---|
| CI-01 | PROVEN GAP / major; owner action | Read-only classic protection endpoint returned `Branch not protected` for develop; effective branch rules returned `[]`. `main` lookup returned 404. | Approved protection/required-check configuration remains unverified; no settings changed. |
| CI-02 | PROVEN GAP / major; fixed | Base `.github/workflows/ci.yml:3-7` covered only develop and had no image or Compose check; documentation claimed all branches. | Build CI and metadata checks cover develop/main; add build-only container and metadata/Compose jobs; existing check names retained. |
| ART-01 | PROVEN GAP / major; fixed | All eight base JAR hashes differed across two clean builds. Entry times and dated `META-INF/maven/.../pom.properties` differed; application JARs also embedded the changed starter JAR. | Fixed archive epoch and pin the already-used `maven-jar-plugin` to 3.4.2. All eight final hashes and full entry inventories match. |
| IMG-01 | PROVEN GAP / major; fixed | S1 Compose context was backend/, but its Dockerfile COPY sources start with backend/ and contracts/. Local base build failed at `COPY backend/pom.xml`. | Compose now supplies repository-root context and the corresponding Dockerfile path. |
| SEC-01 | RISK / major; hardened | Backend raw Surefire exports could include properties, captured output and diagnostic payloads. No live secret exposure was established. | Sanitize exported JUnit properties/stdout/stderr/failure payloads; retain testcase outcomes. No claim of a complete secret scanner. |
| SEC-02 | RISK / minor; hardened | Existing checkout/setup/upload refs floated at @v4. | Pin to official repository commits verified through Git fetch; checkout does not persist credentials. github-script retains its existing SHA. |
| SEC-03 | RISK / minor; hardened | Metadata workflows granted write permissions at workflow scope. | Default contents:read, job-scoped writes; task-status sync no longer requests pull-requests:write. |
| TRACE-01 | PROVEN GAP / minor; hardened | No packaged build artifacts/commit manifest; S1–S5 image source label named another repository and revision defaulted to dev; gateway/frontend lacked OCI metadata. | CI uploads commit/run inputs and package hashes; JAR Build-Revision and version entries; all seven image checks supply revision/version labels. |
| VER-01 | PROVEN GAP / major; open decision | Maven 0.1.0-SNAPSHOT, frontend 0.1.0, no project tags or releases; architecture baseline v1.3 is an architecture label. | No scheme, first release version, tag prefix, signing or SBOM policy selected. Release guard requires caller-supplied policy. |
| TOOL-01 | RISK / minor; follow-up | Java 21/Temurin and Node 22 are major pins; runner ubuntu-latest and CI Maven remain floating; no wrapper. Docker bases use version tags without digest locks. | No Java/Node major change or new Maven plugin/action. Exact toolchain/base-image policy remains owner work. |
| CHECK-01 | OK / minor | CI result uses always() only to examine all dependencies and exits nonzero if any did not succeed. No required job failure masking, pull_request_target or self-hosted runner found. | Preserve behavior and add new build-only jobs to aggregate dependencies. |
| DEPLOY-01 | OK / blocker boundary | No tag-push, workflow_dispatch, repository_dispatch, release or registry/deploy workflow found in all four files. create event only updates issue metadata for branch events. | No publishing/deployment workflow added. CI must not deploy or publish without approval. |

Evidence is scoped to base `055da427a2af414c18ec44aed06efb7dcf77da9b` and the source snapshot recorded in the execution artifacts. Line references below describe this branch's files; source links are relative to this document.

## Workflow and check inventory

| Workflow / jobs | Triggers | Runner and tools | Coverage / proposed merge role | Exclusions |
|---|---|---|---|---|
| [CI](../../.github/workflows/ci.yml): `Frontend checks` | Push and PR to develop/main | ubuntu-latest; setup-node v4 with Node 22; Java 21 for safe report export; npm cache keyed by lockfile | npm ci, Vitest/JUnit, ESLint, explicit type-check, Vite build; proposed required | No browser/Playwright execution or frontend coverage plugin |
| CI: `Backend checks` | Same | ubuntu-latest; Temurin Java 21, Maven from runner; Maven dependency cache | Docker preflight; all reactor modules, existing OpenAPI/JWT/configuration guards and merchant E2E via `-DrunE2E verify`; proposed required | Perf profile remains opt-in and excluded; no load added to CI |
| CI: `Build metadata checks` | Same | ubuntu-latest, Java 21, existing Docker Compose | Positive/negative shell/Java fixtures, version consistency, targeted workflow guards, Compose structural validation; proposed required | Not full YAML/actionlint or a release-policy approval |
| CI: `Container build checks` | Same | ubuntu-latest; existing Docker/BuildKit; Dockerfiles retain Java 21 / Node 22 | Build gateway/S1–S5/frontend locally, inspect OCI revision/version, remove check images; proposed required | No registry authentication, push, running application stack or deploy |
| CI: `CI result` | Same; always() | ubuntu-latest | Stable aggregate requires all four jobs to succeed; proposed required | A skipped/cancelled dependency is a failure |
| [Branch name check](../../.github/workflows/branch-name-check.yml): `branch-name-check` | PR develop/main | ubuntu-latest; shell | `<type>/<issue>-<name>`; proposed required | No checkout or secrets |
| [PR linked issue](../../.github/workflows/pr-linked-issue.yml): `pr-linked-issue` | PR opened/edited/synchronize/reopened, develop/main | ubuntu-latest; SHA-pinned github-script | Metadata validation; skips draft validation by design | Not a build/security check; job-scoped issues/PR writes |
| [Task status sync](../../.github/workflows/task-status-sync.yml): `sync-task-status` | create; PR opened/ready_for_review/closed, develop/main | ubuntu-latest; SHA-pinned github-script | Advisory issue labels/assignee metadata | create ignores tag events; no release or deployment |

**Branch protection is not verified as an approved configuration.** Source job names are recommendations, not proof that GitHub requires them. Main is not created by this change; its CI triggers are ready only if the owner approves and creates that branch. CONTRIBUTING continues to direct integration PRs to develop. No `test` branch was observed.

No workflow references repository `secrets.*` or `vars.*` values. `GITHUB_TOKEN` is implicit in GitHub-provided actions; repository default workflow permission was read-only and Actions PR approval disabled at inspection. Metadata jobs alone request write scopes. PR code is not checked out in a privileged pull_request_target context. Caches follow existing setup-action mechanisms; cache isolation/settings are not independently proven here. All current actions have full SHA pins; enforcing a future strict pin policy is an explicit guard option, not an invented release convention.

There is no Dependabot configuration file or standalone dependency scanner workflow. Existing backend configuration/security guard tests are not a complete SCA or secret-scanning replacement. Read-only repository metadata reported secret scanning and push protection enabled, and Dependabot security updates disabled; alert coverage, applicability, validity checks and organisation settings remain owner checks.

## Artifact inventory and traceability

| Artifact | What is recorded / exported | Retention and limits |
|---|---|---|
| `backend-build-<sha>` | Gateway, S1–S5 and security-starter JARs; SHA-256 list; commit/run inputs; JAR `Build-Revision` and `Implementation-Version` | 7 days, same duration as existing report policy; not a Maven/registry publication |
| `frontend-build-<sha>` | Vite dist and separate version/commit/run input metadata | 7 days; all VITE inputs remain public; no secret or .env collection |
| `backend-test-reports` | Sanitized JUnit results; existing S2 JaCoCo XML when generated | 7 days; testcase outcomes retained; properties, captured output and diagnostic payloads removed |
| `frontend-test-reports` | Sanitized Vitest JUnit results | 7 days; no new coverage runtime |
| Local image evidence | All seven image IDs plus OCI source/revision/version; build logs kept outside Git | No push; task image names removed; base images/shared caches not pruned |

Testcase/suite names are retained: sanitization is not proof against arbitrary sensitive parameter names or malicious test-generated output. No actual live secret in the baseline reports was established. Build artifacts contain public runtime configuration code/templates, not ignored environment files. Repository Docker ignores exclude credentials, keys, .env files, targets, node_modules and build output. Retention approval for future releases and long-term provenance remain owner decisions.

Default local JAR `build.revision` is `unknown`; CI and reproducibility/container scripts explicitly supply the source SHA. Labels/manifest strings provide traceability, not signatures or provenance attestations. The test-only merchant E2E JAR is included in the local eight-artifact reproducibility check but deliberately excluded from packaged application uploads.

## Version and release metadata

| Item | Observed value | Meaning |
|---|---|---|
| Maven parent and all eight modules | 0.1.0-SNAPSHOT | Consistent development versions; release mode rejects them |
| Frontend package.json / lockfile | 0.1.0 | Private package; npm ci locks the dependency graph |
| Architecture baseline in WARP | v1.3 | Architecture revision, not an approved application release version |
| Docker image version/revision | Explicit build args from project version and source SHA | No registry tag convention chosen; raw manual builds may say unknown |
| Helm appVersion/chart version | Absent | Helm/k8s contain placeholders only |
| Project tags / signed or annotated tags | None observed | Nothing to inspect for signatures; none created |
| GitHub releases | 0 at read-only inspection | No release process established by this task |
| CHANGELOG / VERSION / release workflow | Absent | Release notes and approved metadata remain owner work |

The owner chose **leave the scheme open**. Options for a later decision include SemVer `vX.Y.Z` (optionally `-rc.N`) and calendar versions. SemVer is a reasonable option because current application versions already use three components; that is a recommendation, not an approved project convention or first release version. No dependency on architecture v1.3 is inferred. Signing, SBOM, provenance and any release/publish workflow require separate approval.

## Reproducibility procedure

The local-only script clones the committed HEAD without ignored/untracked files, runs two clean backend packages with the same revision/environment, hashes every JAR and inventories every entry's order, content hash, timestamp, size, compression method, CRC, extras and comment. Whole-JAR hashes also detect raw ZIP differences outside those interpreted fields. Nothing is silently excluded. It never installs/deploys artifacts to a remote repository or creates Git refs.

```sh
scripts/reproducible-builds.sh /absolute/new/evidence-directory
```

Build command inside the clean clone, twice:

```sh
mvn --batch-mode --no-transfer-progress -DskipTests \
  -Dbuild.revision=<source-sha> clean package
```

Tests are intentionally NOT run in these packaging-only comparisons. Their results come from the separate full backend verification below. Reproducibility doubles build time and is **not added as a required CI check**; doing so needs approval. Exact Java patch/Maven/OS are part of the result; no cross-machine or OS reproducibility claim is made.

Frontend reproducibility uses the existing local Node 22 container by immutable image digest, two separate npm ci/build runs, clean dist directories and a full sorted file hash inventory. Host node/npm were unavailable: host frontend execution is NOT run, while container execution is separately recorded. No tool was installed on the host. Local Snap Docker's daemon could not see host /tmp mounts, so clean frontend verification used a task-owned Desktop worktree; the underlying already-installed CLI/plugins avoided the Snap launcher's silent redirected-output failure.

```sh
scripts/verify-ci-release.sh check .
scripts/test-ci-release.sh
scripts/check-container-builds.sh /absolute/new/image-evidence --twice
```

The last command performs two local image builds per application; its second pass uses no-cache while sharing only the explicit Maven download cache. Local Docker paths may be supplied with ZENTRO_DOCKER_BIN and ZENTRO_BUILDX_BIN; CI uses its existing Docker/BuildKit installation. Image-ID comparison is evidence for this environment, not a general reproducible-layer guarantee.

Tag checks are inert metadata validation and require an explicit caller policy. The following uses a synthetic fixture version, not an approved project release:

```sh
scripts/verify-ci-release.sh release scripts/test-resources/ci-release/stable \
  --scheme semver --prefix v --tag v3.2.1
```

Guards reject SNAPSHOT/repository module drift, missing explicit policy, wrong prefix, missing patch, v-prefix mismatch, wrong version and disallowed prerelease fixtures. Targeted workflow checks reject failure masking and privileged checkout patterns; SHA enforcement is optional. They are conservative source checks, not a complete GitHub Actions schema validator. Actionlint and shellcheck were unavailable and NOT run; basic YAML parsing used the repository's already-cached SnakeYAML dependency, with no installation.

## Owner actions for GitHub settings

Read-only observations do not approve a release policy. These changes must be performed by the owner, not this task:

- Confirm the approved branch strategy; protect develop, and main only if approved/created, with PRs, stable required checks, independent approval and blocked force pushes/deletion as appropriate.
- Confirm required names: CI result, Frontend checks, Backend checks, Build metadata checks, Container build checks and branch-name-check; metadata checks have their documented advisory/draft behavior.
- Keep default GITHUB_TOKEN read-only and Actions PR approval disabled; review allowed actions, fork permissions and cache policy.
- Verify secret scanning, push protection, Dependabot alerts/security updates and GitHub Actions dependency update policy. No setting was toggled here.
- Approve a tag/version scheme, release owner and tag protections only when a release is planned. No v* rule or main branch was created.
- Establish production environment required reviewers and deployment owner if deployment is approved later. Only an unrelated copilot environment without protection rules was observed.
- Decide exact Java/Node patch and Maven/wrapper pin policy, immutable image digest policy, long-term artifact retention, signing, SBOM and provenance separately.

## Human release checklist — not executable approval

- [ ] Release owner and deployment owner are named, and an approved protected release branch exists (main is not currently present).
- [ ] All approved required checks are green on the exact source commit; no skipped check is accepted as passing.
- [ ] Application/module versions, approved tag format, image metadata and release notes agree; SNAPSHOT is removed through a separately reviewed version change.
- [ ] Reproducibility and artifact hashes are recorded for that exact commit/toolchain; dependency/secret findings are reviewed.
- [ ] Independent approval and the owner-approved tag/version policy are recorded.
- [ ] Only the release owner creates an approved tag from the approved main commit; no agent or CI path in this change does so.
- [ ] Any future registry publication/release/deployment has separately approved protected conditions and human/environment gates.

This repository's CI must not deploy or publish without approval. No release or deploy automation was added.

## Follow-ups and ownership

Source ownership: `.github/CODEOWNERS` assigns backend, workflows and docs to @pirathee587; issue #184's primary assignee remains @Deadsecnote1 and independent reviewer @kirusigan03. No contributors/assignee change is made.

| Follow-up | Owner | Next action |
|---|---|---|
| Protection/required checks, fork/token/action settings | @pirathee587 (CODEOWNERS) | Confirm owner settings against the observed gaps; settings remain untouched |
| Release scheme/tag/version/notes process | @Deadsecnote1 (issue owner), release owner to be assigned | Record an approved policy before enabling any release path |
| Exact toolchain/Maven wrapper and Docker digest policy | @pirathee587 (CODEOWNERS) | Review compatible pinning; a new wrapper/plugin or version change needs approval |
| Dependency scanning, signing, SBOM/provenance | @pirathee587; security/release owner to be assigned | Choose tools/policy separately; no new scanner action or signing introduced |
| Production environments and Kubernetes/Helm | to be assigned | Nominate deployment owner and approval settings; placeholder manifests are not deployment proof |
| setup-java v4 deprecation | @pirathee587 | Review a separately tested action-major upgrade; this change pins the existing v4 source |

## Limits and acceptance evidence

Not verified: approved branch protection/required-check configuration, production environment approvals, registry publishing, deployed environments, signing/provenance, release tags, Windows/macOS or other-machine builds. Local results do not make CI green. The base CI run's success and job metadata were read remotely; full log retrieval returned an empty file, so its per-module counts were not claimed or compared. The new workflow's remote result is recorded only when available for this PR. No deployment was used as a verification shortcut.

| #184 criterion | Evidence / status |
|---|---|
| Inspect/reuse existing implementation/issues | Four workflows, POMs/Dockerfiles, CODEOWNERS, contribution/security/development docs, related issues and read-only API observations |
| Five-service/database boundaries | No service Java, routes, auth, schema or event-contract behavior changed |
| Backend authentication/ownership preserved | Existing full backend tests and E2E; this task changes packaging/CI only |
| Negative tests | 19 guard/sanitization cases; wrong-version/tag/failure-masking fixture files; no real workflow/tag dispatched for testing |
| API/event docs if contracts change | No contracts changed; API/event specs untouched |
| Tests/build/reproducibility | Actual tables and hashes below; skipped or unavailable execution labelled explicitly |
| Focused PR to develop | Draft with Refs #184, reviewer @kirusigan03; release policy/settings acceptance remains incomplete |
| Independent reviewer approval | Pending; no approval asserted |
| Merged commit SHA | to be recorded after merge |

## File/line evidence map

| Evidence | Current source location |
|---|---|
| Develop/main build triggers, read token default | `.github/workflows/ci.yml:3`, `:9` |
| Frontend reporter, type-check, package trace | `.github/workflows/ci.yml:43`, `:47`, `:51` |
| Docker preflight, full backend + E2E | `.github/workflows/ci.yml:96`, `:98` |
| Sanitized exports and seven-day artifact policy | `.github/workflows/ci.yml:100`, `:104`, `:120` |
| New build-only checks and strict aggregate | `.github/workflows/ci.yml:136`, `:157`, `:175` |
| Archive version/epoch and revision manifest | `backend/pom.xml:26`, `:28`, `:44` |
| Correct S1 build context | `backend/docker-compose.yml:9` |
| Metadata write scopes limited to jobs | `.github/workflows/pr-linked-issue.yml:15`, `.github/workflows/task-status-sync.yml:16` |
| No provenance/SBOM or registry output | `scripts/check-container-builds.sh:7`, `:8`; default uses local build, explicit Buildx uses --load |
| Diagnostic-free JUnit export | `scripts/ci/ArtifactInventory.java:42` |
| Source owner | `.github/CODEOWNERS:1` |

Packaging follows [Apache Maven's reproducible-build guidance](https://maven.apache.org/guides/mini/guide-reproducible-builds.html). The proof below is the actual paired result on the recorded machine, not a claim that setting a timestamp alone guarantees every future toolchain.

## Actual local verification results

Base: `055da427a2af414c18ec44aed06efb7dcf77da9b`. Backend test/frontend snapshot: `4b3ccba655d97e80242336edab3ea26e5a8522bb`. Final packaging/images snapshot: `50715b5a46f2c5971b6fd60cbf9484dac0ad4642`; the sole intervening code change disables image-builder default attestations. No service/application source changed. [Exact commands and snapshots](ci-release-evidence/commands-and-snapshots.txt).

| Module | Full CI profile tests | Normal test/package tests | Failures / errors / skipped |
|---|---:|---:|---|
| Parent POM | 0 | 0 | Not a test module |
| Security starter | 62 | 62 | 0 / 0 / 0 |
| Gateway | 210 | 210 | 0 / 0 / 0 |
| S1 | 420 | 420 | 0 / 0 / 0 |
| S2 | 675 | 675 | 0 / 0 / 0 |
| S3 | 259 | 259 | 0 / 0 / 0 |
| S4 | 91 | 91 | 0 / 0 / 0 |
| S5 | 425 | 425 | 0 / 0 / 0 |
| Merchant E2E | 2 | NOT run | CI profile: 0 / 0 / 0; normal module skipped by configuration, testcase skip count not reported |
| Total executed | **2144** | **2142** | All executed Testcontainers cases ran; none skipped |

The CI-profile command passed in **10m56s**; the explicit `mvn --batch-mode test package` passed in **9m41s**. The performance test is intentionally NOT run in either path; no performance CI approval was inferred. The local CI-profile invocation used `-Dbuild.revision=4b3ccba` (abbreviated); the paired artifact builds and final image builds used the full 40-character source SHA. [CI results](ci-release-evidence/backend-ci-results.txt); [normal results](ci-release-evidence/backend-test-package-results.txt).

Container frontend: **60 files / 671 tests passed**, zero skips; npm ci, lint, type-check and production build succeeded. The new JUnit reporter flags also passed the same 671 tests and its export was sanitized successfully. Host Node/npm: **NOT run**, unavailable. Two final clean container builds match for **all 51 dist files**, with both manifests retained. Node **22.23.4**, npm **10.9.9**, immutable local image `node@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d`. This is separate from the still-major-only Node 22 CI selection.

Guards: **19 positive/negative/sanitization cases passed**. Shell syntax passed via bash -n; basic YAML parsing passed all four workflows using the already-cached SnakeYAML 2.3. **Actionlint and shellcheck NOT run**, unavailable and not installed. Both Compose files passed structural validation without environment resolution/interpolation. The real repository release-mode guard rejected SNAPSHOT versions as expected; no tag action occurred.

The normal-source/frontend fixtures in the clean Desktop worktrees contain no ignored .env inputs. The Docker launcher/private-/tmp failures and one cancelled automatic-attestation build were setup attempts, not successful reference checks. The discarded image attempt emitted default unsigned attestation metadata; the final image script explicitly sets `--provenance=false --sbom=false` and performs local loads only. No signing or SBOM policy was enabled.

### Backend JAR SHA-256 comparison

All eight base pairs differ; only timestamps and generated pom.properties/nested-starter content changed, with no entry-order differences. [Per-entry content differences](ci-release-evidence/backend-before/content-differences.tsv) and [timestamp/order counts](ci-release-evidence/backend-before/entry-differences.tsv). All eight final whole-JAR pairs and complete entry inventories match; the final diff is empty. A third package from a different clean worktree path also matched all eight final hashes. [Manifest fields](ci-release-evidence/backend-after/manifest-fields.txt) confirm full source SHA/version entries in gateway and S1–S5.

| Artifact | Base run 1 SHA-256 | Base run 2 SHA-256 |
|---|---|---|
| api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar | `6c4bdbe606c6fa9cc07cca41bf14e40f47c17f11862f3c99f5ad742da36a1b06` | `ecd9660acc106122c17905e6f8d428c5aabe5f49823b62052b9ecb25325a6cfd` |
| dispatch-courier-service/target/dispatch-courier-service-0.1.0-SNAPSHOT.jar | `e66eb358ae9501eefa1d4f1c267aea434090b47bc0fbe198fa87de1520f4ee0d` | `b42c2d78292dc5a68420b470a3f57d23d5a3287e06174d5e65f81e3b1aaa251b` |
| identity-partner-service/target/identity-partner-service-0.1.0-SNAPSHOT.jar | `af5539f7808c47f1e0457fbf80bc2f59b816bc5b65959a232eeb27f96a9caf96` | `44f8cc3d7078d13e759856761ca35e9910b3ef640178cd4cc896b03187a40678` |
| libs/zentro-security-starter/target/zentro-security-starter-0.1.0-SNAPSHOT.jar | `2fa63a88e40f176debd9fa94e79144f388a652543bcf5ce328f9c8d4feb54366` | `6e0820c126739a858cf85eeb4d06bf68bee05db5f5fd4a8fc023a01ff0b13662` |
| merchant-order-webhook-e2e/target/merchant-order-webhook-e2e-0.1.0-SNAPSHOT.jar | `fba8497dde1307190e8c2f632f417accb571bfd7e8f73d2c27d238191e9da15a` | `65078a0bc0cfdbd099827bf98d6e773642a2c5f81c7617ccc2b85385af1ad892` |
| notification-integration-service/target/notification-integration-service-0.1.0-SNAPSHOT.jar | `7b6f9df6d88d40c274b7d6f27348007858b4d2efe3bcbfa4a4f7ccb1d83e7d00` | `e864734b9c714df63a937a5fc2056668fb45de18bf3b5e0efd22e0b64dda30a5` |
| parcel-pricing-service/target/parcel-pricing-service-0.1.0-SNAPSHOT.jar | `94ef9733796100f2318c8b3b7fd0501f96f6f3c40d1a85fd4442e9724309227a` | `be05b224ea0d241f9d3de2b6dbabeb75beb09a1a5cdf6a5d9e17a9a80a554e72` |
| tracking-reporting-service/target/tracking-reporting-service-0.1.0-SNAPSHOT.jar | `64e18bf18e2a2697b79c94a952249a68559fd05b378ce50042d1e5e75911c2e9` | `adcd8fbe110b16592241e4fb16cdd0b36e8c10ca3216e1d377da59388ec6f4e8` |

| Artifact | Final run 1 SHA-256 | Final run 2 SHA-256 |
|---|---|---|
| api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar | `b4cdb920f3519943731225594a677103eac09be70eef10c0b17144efef3a1d48` | `b4cdb920f3519943731225594a677103eac09be70eef10c0b17144efef3a1d48` |
| dispatch-courier-service/target/dispatch-courier-service-0.1.0-SNAPSHOT.jar | `0dcad9834346f7b1e03de2940b147a2494c946522f25bb2066503fea204f209e` | `0dcad9834346f7b1e03de2940b147a2494c946522f25bb2066503fea204f209e` |
| identity-partner-service/target/identity-partner-service-0.1.0-SNAPSHOT.jar | `64bacf9f34facde0bf55bfa0bd23eee637d330b56f483f18d47631721025bf60` | `64bacf9f34facde0bf55bfa0bd23eee637d330b56f483f18d47631721025bf60` |
| libs/zentro-security-starter/target/zentro-security-starter-0.1.0-SNAPSHOT.jar | `c9c8c91de2061d672dc33ddb3f73fd2b18f2aa6d01b4f7521fbe4e4a4ce3b6fa` | `c9c8c91de2061d672dc33ddb3f73fd2b18f2aa6d01b4f7521fbe4e4a4ce3b6fa` |
| merchant-order-webhook-e2e/target/merchant-order-webhook-e2e-0.1.0-SNAPSHOT.jar | `76a52215660d4e932a08a68459d25464d14031a14cd23b7df42dcd7008ecc94f` | `76a52215660d4e932a08a68459d25464d14031a14cd23b7df42dcd7008ecc94f` |
| notification-integration-service/target/notification-integration-service-0.1.0-SNAPSHOT.jar | `43f733f75ece8647e7506cfcab6575a83f730a56439780efff66cfc5862d81ec` | `43f733f75ece8647e7506cfcab6575a83f730a56439780efff66cfc5862d81ec` |
| parcel-pricing-service/target/parcel-pricing-service-0.1.0-SNAPSHOT.jar | `7bb039e138018038b11e5c8e933f5edd33f2075d124aed479fdd92b8a675e30f` | `7bb039e138018038b11e5c8e933f5edd33f2075d124aed479fdd92b8a675e30f` |
| tracking-reporting-service/target/tracking-reporting-service-0.1.0-SNAPSHOT.jar | `f6790c34ea539e0a82c443af3cdf3fc21700c45094e5571d38f451012b0b84fa` | `f6790c34ea539e0a82c443af3cdf3fc21700c45094e5571d38f451012b0b84fa` |

### Frontend dist SHA-256 comparison

Every listed hash is identical in both runs; [run 1](ci-release-evidence/frontend/run-1.sha256), [run 2](ci-release-evidence/frontend/run-2.sha256), [empty diff](ci-release-evidence/frontend/artifact-diff.txt). No file is silently ignored.

| File | SHA-256 in both clean builds |
|---|---|
| dist/assets/index-7ompxH8j.js | `c00cf7e114340bef7f0ad77df6f559bcae732fa38303454601935f6c5a55ff28` |
| dist/assets/index-BPtIvUwH.js | `db51c2fc65bd24b5116197a73af7c54dab1888e26d5455e967385f5ccc9e324f` |
| dist/assets/index-BRv5_KmR.css | `cc107cc9a1a68c8aa567b25f2041e0155d4ed8c07e61952c35f1c2050951481b` |
| dist/assets/index-BUvK3mQI.css | `b6862da3af621983d3ad84bcdccccccfeb77606022b7170b338ce0ccae7df5f6` |
| dist/assets/index-Bn-rQRWw.css | `1616f33c95f403f755440cae315a24680b58ceedb75a14d352b49071e3bdefad` |
| dist/assets/index-Boeu4FDJ.js | `801dfba4103e60da27bdcbbbd7fa007bdcb798a5c1f61e008ea11ae5ca62c418` |
| dist/assets/index-Bwhz7iRY.css | `a5b6f565b3c5945ae615c16656ee66b564baea67730d55e5c2230fd05bea2733` |
| dist/assets/index-ByEwT9UU.js | `0223da00bd788df841f7e159a0e7ee74f1902e74fe52a9d077a12404e81385b3` |
| dist/assets/index-C6-vgDvb.js | `d334e25d7548acd35c652757825b5a73c79c969fbe8f5617d5cdeb55ba63d814` |
| dist/assets/outfit-latin-300-normal-AWLfuCom.woff | `bcc41559359a3cb79105f71979b7e70f9bba8ee39460db790057d3f3c0cc77eb` |
| dist/assets/outfit-latin-300-normal-CRYTwY6m.woff2 | `ef88e74f63ce75b54cce4d5a4087c6f180750b9ebae6ab6e5bf2a9939bf6958f` |
| dist/assets/outfit-latin-400-normal-BGsTXAXT.woff2 | `71f028fd2349990b3f8aa643abe767e82e0f596be407ae66f0004ff164121bdb` |
| dist/assets/outfit-latin-400-normal-DMwTpYkH.woff | `f329dc36d2d9285431fb8322f5e951def9cac4a42c1cada679747f7c64b04916` |
| dist/assets/outfit-latin-500-normal-ClnHRwRh.woff | `40083be5d9c1935c562bfe9be662d5a2798910deee9e8049509142549a995626` |
| dist/assets/outfit-latin-500-normal-DKnIMDSk.woff2 | `fc9bff941def6d924a71632bd8a93190528872eae6414844f3c367ff948d92fd` |
| dist/assets/outfit-latin-600-normal-B7SfZ07L.woff2 | `8cfe15c2c6de6ef8efff3eedbd52a383ac9ef23d6c23f6cd9f9b838f5f37dc36` |
| dist/assets/outfit-latin-600-normal-BEfTtDA7.woff | `3d1cd1b42cb525acde934764b2adec41dddf7932e1a965392d285efd56f2c148` |
| dist/assets/outfit-latin-ext-300-normal-BYS6Dpvq.woff2 | `463eec2e5944ce6534089376531a533eae99b6dfae1af87924756fa5af6ba1c0` |
| dist/assets/outfit-latin-ext-300-normal-CMIMshEd.woff | `5c78f172cf303e7df8fba666f3cc1d7003de656e6822b0f99bfc733e4a9aff38` |
| dist/assets/outfit-latin-ext-400-normal-5tcqmc2S.woff2 | `e279fd5005b5936e4f4d20fd21e1fdd6a35d724ff576623d31e7c425c356b7ac` |
| dist/assets/outfit-latin-ext-400-normal-DHm7mdGe.woff | `a6697dab76ab8386fc4244f9f6d3ed8639fce59e3be228c146d6c4586c835022` |
| dist/assets/outfit-latin-ext-500-normal-DrCvqoFD.woff | `0bee549f28f292ac938f04ff97b235dc9fa0b42fbbd8b53a279355da59443cd2` |
| dist/assets/outfit-latin-ext-500-normal-zeox_O30.woff2 | `89894a600bea0ec4724988d191ea32a0353bb1c9c130cbdf346654d356b1ba84` |
| dist/assets/outfit-latin-ext-600-normal-B85nYjL1.woff2 | `a049363d2c3565ef5ea79725c71d23030c91f679cc7a0cf45e9726375bd58e7c` |
| dist/assets/outfit-latin-ext-600-normal-CWJcPgd7.woff | `a0c20185e588d3dd8f6d5557c3499a0e3c95f463a555ebe1bf7e233263972749` |
| dist/assets/plus-jakarta-sans-latin-400-normal-BDKaGhyp.woff | `9709167175cee7dcd331a7ed95be1182a5d5f00715b67a02107ecc80a55f490d` |
| dist/assets/plus-jakarta-sans-latin-400-normal-Dhut76fR.woff2 | `221a4135d06a4b33abbd535e9a0da4e565d19545ddd9267c4e678a916d54d9b6` |
| dist/assets/plus-jakarta-sans-latin-500-normal-Bf-nb4oT.woff2 | `bad081c8dbc15aed6c5e4cde5461914f7b3bdc295b7a7aed177e4beddb79bffa` |
| dist/assets/plus-jakarta-sans-latin-500-normal-DYtqi0QS.woff | `a1b54d9fda75d8de13e283ef09668395160e23cbb7dd9ae1aec91d6199da85bb` |
| dist/assets/plus-jakarta-sans-latin-600-normal-BR_Ojra4.woff | `d9569c0aacc16c33c52e57e1e48c1b94f3b0a6be99e75479edc6438cc44c48e0` |
| dist/assets/plus-jakarta-sans-latin-600-normal-DLTa1BUW.woff2 | `8872bb5c9111df9bd3162c2394aee6354d782b13408a4dd5bbf65c48207206e6` |
| dist/assets/plus-jakarta-sans-latin-700-normal-CfpNZvy6.woff2 | `2050755bf475817c96ac7d914c7f07cc3c2d11ff4b3fb4747b8d41de584aad17` |
| dist/assets/plus-jakarta-sans-latin-700-normal-DqEcQeE5.woff | `6188f593f250aa25137865e8d07373fd0f585f5920a496c73ede84dbbdff7a92` |
| dist/assets/plus-jakarta-sans-latin-ext-400-normal-2ppkqaOR.woff | `d0a823b74d65410a8faaf5efb1232813197cdc898d9191e5997428b42aa4cafd` |
| dist/assets/plus-jakarta-sans-latin-ext-400-normal-D127hM_v.woff2 | `65b5680bf2ba9c6c42af74ddd2e8aadde93af7c523454ebb3d4e32fdba3f94db` |
| dist/assets/plus-jakarta-sans-latin-ext-500-normal-9VZagAe1.woff2 | `d1584f50c388cae7e570bed35be331fa4a9afd6832ef45a32fa8bf81930e6db9` |
| dist/assets/plus-jakarta-sans-latin-ext-500-normal-CqEEAICU.woff | `42793e2d60d4b62191fa9f1968002d76c1c7438e76b7c3bcc145af154803e9d5` |
| dist/assets/plus-jakarta-sans-latin-ext-600-normal-B0WRRYJ1.woff | `ade6f23e14c20160f924f980f6610cd73979c03227541d8734b2c42563d11325` |
| dist/assets/plus-jakarta-sans-latin-ext-600-normal-DQgPU_Cg.woff2 | `080a1fea8589c2bd4fa08750d0d99b3388be63df8c41a649de05cb2dda1ec007` |
| dist/assets/plus-jakarta-sans-latin-ext-700-normal-8-d-IyIQ.woff | `aaa9d5f7040bcf5ee2c9569f6c54aaf8327ee769fd2d02764a858f450a8fbf13` |
| dist/assets/plus-jakarta-sans-latin-ext-700-normal-BajbvANd.woff2 | `b202ec87899e78825db955e6ab43858b88c0cd444abe2dfac2e763f837b8f234` |
| dist/assets/plus-jakarta-sans-vietnamese-400-normal-B8TCSmUq.woff | `4af41a5df515e53b4e9a3c11dd60874f79d7c07f344a658f5334fef6cbaa4a04` |
| dist/assets/plus-jakarta-sans-vietnamese-400-normal-Cg2kxhWu.woff2 | `89bca04c713c80fb9ec1c757c21b3ea1bcb46fe1612e11c21ca78db121003280` |
| dist/assets/plus-jakarta-sans-vietnamese-500-normal-CvE5C8T-.woff | `7b713066d41752b321dcb8193b245ce4e99b55883c67c5b16f4d261de91ad66c` |
| dist/assets/plus-jakarta-sans-vietnamese-500-normal-DiU8zqi-.woff2 | `71b1934f88d0de7be49026dbd4dcea3347267b896255b3d6f68ab91ce336a939` |
| dist/assets/plus-jakarta-sans-vietnamese-600-normal-5YsyKbU1.woff | `f6ae137fcdb5359783aa2d69ccb6e5023c3091ed865c2c57579954256eee2257` |
| dist/assets/plus-jakarta-sans-vietnamese-600-normal-Y4VTTRme.woff2 | `a8443c4814ae569fb8170ed2d6175c077d3742e821d4986b0c2f6f1f452b1b09` |
| dist/assets/plus-jakarta-sans-vietnamese-700-normal-BCh_uG_5.woff | `594042b8ae9144c606b755a7b2ec64b87b245bbdb1bcaf9f9c8bd97af432e7c1` |
| dist/assets/plus-jakarta-sans-vietnamese-700-normal-CV6qDkkj.woff2 | `4dda300501fe09bb074a0559f9d58d8deede6cb9c34a06190d62d91f4790bc58` |
| dist/images/README.md | `b80d5001a9a96449e81c7238f5a225f5b34d96cec31efbea97b3df358156265a` |
| dist/index.html | `7c44941fcbe4b28e9c20ee1046d823f1e9e49c2b9388e545bd497da01ecc0226` |

### Actual required environment-command output

```text
$ java -version
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-1-24.04.4-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.12.1+1-1-24.04.4-Ubuntu, mixed mode, sharing)

$ mvn -v
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.12.1, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-42-generic", arch: "amd64", family: "unix"

$ /snap/docker/current/bin/docker --version
Docker version 29.8.0, build 88096ef

$ node -v (host)
/bin/bash: line 5: node: command not found

$ npm -v (host)
/bin/bash: line 6: npm: command not found

$ uname -sr
Linux 7.0.0-42-generic

$ nproc
16

$ free -h
               total        used        free      shared  buff/cache   available
Mem:           7.5Gi       5.0Gi       969Mi       506Mi       2.3Gi       2.5Gi
Swap:          4.0Gi       2.2Gi       1.8Gi

```

### Final local container results

All **14/14 builds passed**: gateway, S1–S5 and frontend, twice each, with the second pass using `--no-cache`. Every image had the expected full source revision `50715b5a46f2c5971b6fd60cbf9484dac0ad4642` and project version (backend `0.1.0-SNAPSHOT`, frontend `0.1.0`). [All actual image IDs and labels](ci-release-evidence/images/image-ids.tsv) are retained.

**All seven image IDs differed between runs. Container image reproducibility is not established.** The retained [creation/layer samples](ci-release-evidence/images/created-and-layer-samples.txt) show different creation metadata and layer hashes for gateway and S1. Mutable base tags and builder-generated metadata remain uncontrolled inputs; their individual contribution was not isolated. Reproducible JARs and frontend output do not imply reproducible container layers. Immutable bases and deterministic image construction remain a follow-up, without enabling signing, provenance or SBOM policy here.

The script completed successfully and its cleanup removed all 14 task-scoped image names. A final filtered image listing returned no `zentro-ci-184-*` names. Shared images and caches were not pruned. No container image was pushed.
