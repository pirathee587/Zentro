# Notification workstreams — baseline and completion report

## 1. Repository baseline (2026-11-14)

`origin/develop` remains `b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22` after fetch.
WARP.md was read from that revision; no service-specific AGENTS.md was found.
Original worktree `develop` has user deletions of `docs/service-env-templates.html` and
`.pdf`; these remain untouched. G17 continues in the existing clean `Zentro-356`
worktree; three new decision branches start directly from this latest develop, not PR #371.

## 2. PR #371 current status

At initial inspection PR #371 is OPEN, non-draft, mergeable, and unmerged at
`a29262e8833207b780128afbcf3945afd4805a85`; no submitted reviews or comments were found.
@Kiruthiyan is requested. Its six changed files match the previously reported work.
[CI 43482716658](https://github.com/pirathee587/Zentro/actions/runs/43482716658) is in
progress at inspection: frontend, dependency scan, container build and metadata jobs
passed; backend checks remain running. Branch and linked-issue checks passed.
The prior local 568-test S5 and 2,424-test full-backend results are historical evidence
for implementation `2c86992b16521669778994640c6797539f5ff6bf`, not a new test run.

| Stream | Baseline status | Verified implementation / remaining work |
|---|---|---|
| G17 | PARTIAL | PR #371 fixes result-write classification; seven recovery tests/runbook exist. Add local HTTP receiver deduplication and rejection-write failure evidence; review/CI/merge remain pending. |
| G15 | BLOCKED | Gateway prefix reserved; no general S5 audit handler/migration. S1 user audit, S3 assignment audit and S5 replay facts stay local. Prepare source/privacy/access/retention decisions before ingestion/API. |
| G16 | BLOCKED | S3 transactional lifecycle outbox exists. S5 has only parcel consumers and tracking-reference mock recipients. Prepare S1 authorization/lookup, ordering, consent, templates and delivery contract decisions. |
| X06 | BLOCKED | NotificationSender and MockSmsSender already exist. No approved real provider or recipient/preference contract. Prepare release/provider/security/credential decision; do not duplicate abstraction. |

### Inspection and approval boundary

Issues #348, #349, #170 and #24 and all comments were inspected. #348/#349 explicitly
approve allocation only, not pending policies. #24 is historically closed for the mock
implementation; it does not approve a real provider. Current consolidated ownership remains
@Deadsecnote1 with independent @Kiruthiyan review. S1/S2/S3/S4/S5, gateway routing/security,
service migrations and test fixtures were inspected without modifying other service code.

The request's “no infinite retry loops” cannot safely mean finite database retry followed
by offset commit: #170 explicitly forbids that loss mode. Preserve unlimited durable
storage retry with backoff and operational escalation. Poison records are durably rejected
and acknowledged; provider attempts have finite budgets. A new DLT/finite ingestion policy
is proposed only after payload privacy, retention, recovery and ownership approval.
No DLT implementation or real delivery authorization is inferred from this request.


## 3. G17 implementation — PARTIAL

G17 adds two recovery integration cases (loopback receiver acceptance/deduplication after
result-write rollback, and unacknowledged rejection-storage failure) plus unauthenticated
replay denial. Existing database/broker/JVM restart, duplicate/reorder, retry/exhaustion,
parser/version and replay role tests are retained. The synthetic HTTP receiver's claim set
is in memory; durable real-merchant/provider idempotency is not proven. Its failure is an
injected transaction error, not a kill at exact acceptance. Runbook inspection/troubleshooting
now includes bounded rejection metadata SQL and an explicit DLT policy boundary.

Full-backend verification passed. The PR is technically ready for review of the approved
recovery changes; G17 remains PARTIAL while DLT/finite-ingestion recovery policy and real
deployed receiver guarantees are unresolved and independent acceptance is outstanding.

## 4. G15 implementation — BLOCKED

[PR #372](https://github.com/pirathee587/Zentro/pull/372) supplies the source/ownership,
access/query, redaction, retention and archive decision artifact. No audit runtime is added.

## 5. G16 implementation — BLOCKED

[PR #373](https://github.com/pirathee587/Zentro/pull/373) inventories actual lifecycle events
and proposes recipient, causal-order, consent, template and replay contracts. No assignment
consumer or contact lookup is activated.

## 6. X06 implementation — BLOCKED

[PR #374](https://github.com/pirathee587/Zentro/pull/374) compares mock/SMS/email and inventories
configuration, credential, sandbox and release decisions. Existing abstraction is reused;
no real adapter or unnecessary duplicate foundation is added.

These documentation-only proposals are independently reviewable; no acceptance of their
policies is inferred from PR creation or technical review. No real SMS/email is sent.

## 7. Files changed

On #371 this follow-up modifies:

- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/NotificationRecoveryIntegrationTest.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/admin/controller/IntegrationAttemptControllerTest.java`
- `docs/operations/s5-notification-recovery.md`
- `docs/testing/issue-356-implementation.md` (link to this dated follow-up)

Created on #371: `docs/testing/notification-master-baseline.md` (this report) and
`docs/testing/shared-e2e-keycloak-cache-reset.patch` (unapplied main-10 fixture fix proposal).
Separately created in the three develop-based branches:

- `docs/architecture/g15-operational-audit-decision.md`
- `docs/architecture/g16-courier-notification-contract-decision.md`
- `docs/architecture/x06-notification-provider-decision.md`

No files deleted.

## 8. Database migrations

No migration added/edited; applied migrations remain unchanged.

## 9. Kafka events and contracts

No Kafka topic/schema/version or production event changed. Current S3 event examples and
proposed future fields are documentation, not an approved shared schema change.

## 10. API endpoints

No runtime API endpoint or OpenAPI contract added/changed. Illustrative
proposed routes/templates are not active features. No S1/S2/S3/S4, gateway, shared security,
CI/build, infrastructure or frontend production implementation is changed by this follow-up.
The existing runtime fix remains in #371, not develop, until independently accepted/merged.

## 11. Security and privacy verification

Existing replay denies CUSTOMER, MERCHANT, COURIER and SERVICE and permits Dispatcher;
new test verifies unauthenticated POST is rejected without service/repository calls.
MVC tests mock JWT decoding and exercise Spring Security, not a real IdP signature check;
full backend E2E exercises its existing disposable Keycloak cases separately.
Rejection metadata is tested for absence of a private payload canary. HTTP evidence uses
synthetic event IDs with no destination or message body, on loopback only. No credential,
provider SDK or real contact lookup is introduced. The independent receiver still needs
its own durable transactional deduplication in production. No cross-service SQL or new
ADMIN authority is added. G15 runtime redaction/access/retention and G16 real recipient
security tests remain blocked with their contracts.

## 12. Automated test evidence — new runs only

Environment: 2026-11-14 Asia/Colombo; Ubuntu/Linux 7.0.0-42-generic amd64,
OpenJDK 21.0.12.1, Maven 3.8.7, Docker server 29.8.0. S5 uses Testcontainers 1.21.4,
PostgreSQL 16-alpine and Kafka 7.6.1 KRaft. Full reactor also uses gateway Testcontainers
1.20.4 and cross-service Testcontainers 1.19.8/Kafka 7.8.0/Keycloak 26.3.3.
No local resource-intensive stacks were run concurrently.

Commands:

```sh
# First attempt from repository root: exit 1, no tests discovered (wrong reactor directory).
mvn --batch-mode --no-transfer-progress -pl notification-integration-service -am -Dtest=NotificationRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
# Corrected directory: backend; exit 0, 9 tests passed, 0 failures/errors/skips, 01:05.
mvn --batch-mode --no-transfer-progress -pl notification-integration-service -am -Dtest=NotificationRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
# backend; exit 0, 571 passed, 0 failures/errors/skips, 02:49.
mvn --batch-mode --no-transfer-progress -pl notification-integration-service -am verify
# backend; candidate 580295a73c991bdd89f6aab70ce598fbc1505362, exit 0, 2,427 passed, 0 failures/errors/skips, 14:20.
mvn --batch-mode --no-transfer-progress -Dbuild.revision=580295a73c991bdd89f6aab70ce598fbc1505362 -DrunE2E verify
```

Focused run was an uncommitted precursor with the same nine recovery cases; the replay
unauthenticated test was added before immutable candidate `580295a` and verified in the
571-test service run (62 starter + 509 S5). Full-reactor totals: starter 62; gateway 213; identity 434; parcel 719; dispatch 327;
tracking 151; S5 509; E2E 12. Full E2E classes: order 1, webhook replay 4, credentials 5,
approval 2. No selected service or full-reactor tests skipped. Existing
provider retry schedulers run in their own suites; the recovery class mocks its background
scheduler to avoid injected crash-window races. Repository spies inject one write error
but otherwise use PostgreSQL. HTTP receiver is synthetic; no provider sandbox tested.

Documentation validation: a Python 3 inline check read the three proposal Markdown files,
required PROPOSED/BLOCKED labels and balanced code fences, and parsed all JSON fences with
`json.loads`: 3 documents/3 examples valid, exit 0. Each staged `git diff --cached --check`
passed, exit 0. No runtime test is claimed for documentation-only PRs. No OpenAPI validation
was required because no implemented API/OpenAPI artifact changed. Opt-in performance tests
are excluded by the existing full `runE2E` profile; the `perf` profile is not run.

## 13. CI results

CI snapshots for the updated #371 head and proposal PRs are supplied with the final handover.
Earlier CI for a29262e is separate evidence and cannot validate newly added tests.
Subsequent inspection confirmed [43482716658](https://github.com/pirathee587/Zentro/actions/runs/43482716658)
SUCCESS in every job. Its downloaded backend log independently totals 2,424 tests including
12 E2E, zero failures/errors/skips and BUILD SUCCESS. Thus the original report is corroborated
by CI, while the new candidate requires its own run.

Proposal CI started (snapshot during local candidate verification):
[G15 43484879465](https://github.com/pirathee587/Zentro/actions/runs/43484879465),
[G16 43484939735](https://github.com/pirathee587/Zentro/actions/runs/43484939735),
[X06 43484943864](https://github.com/pirathee587/Zentro/actions/runs/43484943864).
All three branch-name, linked-issue and task-sync checks passed. Main CI subsequently FAILED
on each proposal: 2,415 reported tests, 2 failures, 0 errors, 0 skips (2,413 passed). All
service tests passed; E2E reports 11 tests with setup failures in MerchantOrderAcceptanceIT
and MerchantApprovalGapIT, each expecting Keycloak client creation 201 but receiving 401 at
DisposableGatewayStack.createServiceClient:203. Failed setup prevents the normal 12-case
E2E suite from completing; zero reported skips is not evidence those blocked cases ran.
Other main-CI jobs passed. No failing test was disabled or silently excluded.

Inspection shows shared static adminAccessToken/expiry cached across KEYCLOAK.stop/start;
a new realm cannot authenticate that old token even before its cached expiry. CI's different
class order exposes the cache lifetime mismatch. This is a source-supported diagnosis, not
a passing fix claim. A two-line reset before fresh Keycloak startup is prepared as
`shared-e2e-keycloak-cache-reset.patch`; `git apply --check` passes without applying it.
Main 10 owns this shared fixture; authorization to apply a separate fix PR was requested.
No shared fixture edit or successful rerun is claimed while that approval is pending.

Proposed owner verification after applying the patch (NOT run against an applied fix):

```sh
cd backend
mvn --batch-mode --no-transfer-progress -Dbuild.revision=<fix-candidate-sha> -DrunE2E -Dsurefire.runOrder=alphabetical verify
```

A second differing class order should be verified sequentially if the first confirms the
fix. Do not change authorization or accept 401/skip assertions merely to pass startup.
No merged commit SHA exists for these open PRs; GitHub's provisional merge_commit_sha on
an open PR is not an actual merged revision. Do not mark any stream COMPLETE.

## 14. PR links

- G17: https://github.com/pirathee587/Zentro/pull/371 (existing PR extended, not duplicated).
- G15 decision: https://github.com/pirathee587/Zentro/pull/372.
- G16 decision: https://github.com/pirathee587/Zentro/pull/373.
- X06 decision: https://github.com/pirathee587/Zentro/pull/374.

## 15. Review status

Primary remains @Deadsecnote1. Independent @Kiruthiyan review is requested on all four PRs;
no independent approval or merge is claimed. All PRs target develop and use Refs links;
none auto-closes a blocked requirement or historical issue. No self-merge is authorized.

## 16. Exact commit SHAs

| PR | Branch | Exact implementation/proposal commit |
|---|---|---|
| [#371](https://github.com/pirathee587/Zentro/pull/371) | feature/356-kafka-notifications-and-operational-audit | 580295a73c991bdd89f6aab70ce598fbc1505362 (subsequent report-only commit recorded in final handover) |
| [#372](https://github.com/pirathee587/Zentro/pull/372) | feature/348-operational-audit-log-api | f20eed6658bc8180232733b1e89a9072acdf277d |
| [#373](https://github.com/pirathee587/Zentro/pull/373) | feature/349-courier-assignment-notifications | bc45f7bc3ff86c755fc560e7c65b9679b3b6750a |
| [#374](https://github.com/pirathee587/Zentro/pull/374) | feature/24-notification-provider-readiness | 007e65f52a3c65ad8b7e8bf9b2f34a6f106354f1 |

## 17. Remaining approval blockers

G15: product/security/privacy/legal must approve sources, access/permissions, redaction,
query limits, retention duration, archive/deletion/restore/hold rules; domain and main 02
owners must approve exported event/API contracts before S5 ingestion/storage/read API.
Then implement all bounded query, immutability/idempotency, RBAC, privacy and retention tests.

G16: product/security/S1/S3/main 02 must approve release recipient matrix, service-authenticated
purpose-specific lookup, causal version/current assignment checks, channels, consent and
opt-out, templates and lookup/replay failure rules. Then owners implement their contracts;
S5 adds the approved consumer using durable dedup/outcomes, with wrong-recipient/S1-outage/
reorder/withdrawal/replay tests. Existing outboxes stay owned by S2/S3.

X06: product/security/privacy must accept mock-only release limitations or select a real
provider; deployment credential custodian must approve scoped sandbox/production secrets,
authorized sandbox recipient/budget and rollout. Then add only the approved adapter through
the existing abstraction, with safe typed errors/receipts if needed and authorized validation.
No credentials or real delivery are currently approved/available for this work.

## 18. Remaining implementation tasks

After the approvals above, domain owners implement their approved exports/lookups and
contracts, then S5 implements the approved audit API, courier consumer and selected provider
with their security, ordering, consent, retention and sandbox tests. These tasks are not
silently replaced by proposal documentation.

G17: independent review/required CI and explicit merge authorization remain. A future DLT
or finite ingestion retry strategy additionally needs a loss-free durable transition,
payload/retention/access and operator redrive policy. Real deployed recipient idempotency,
provider-specific guarantees and infrastructure-scale outage/alert acceptance remain separate
from disposable local proof. Existing mock/rejection recovery is preserved.
