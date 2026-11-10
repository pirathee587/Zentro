# Dispatcher workload — metric verification (#134)

Status: assignment-based metric verified by the tests described below; lifecycle metrics remain a separate design follow-up. The user explicitly approved verifying/documenting the current assignmentCount rather than inventing active/completed/failed/reassigned metrics. No production query or behavior change is included.

## Workload metric definitions

`GET /api/v1/dispatch/workload` returns `{items,total,overloadConfigured}`. The gateway requires DISPATCHER; S3 additionally requires a signed `permissions` claim containing DISPATCH_VIEW. Courier endpoints remain COURIER-only with assignment ownership checks. No ADMIN role exists.

| Field | Exact definition before → after this audit |
|---|---|
| items[].courierId / userId | Canonical S1 courier profile/app-user UUIDs; unchanged |
| items[].name | S1 display name, nullable; unchanged runtime behavior. OpenAPI corrected from string-only to string-or-null, matching frontend Zod |
| items[].assignmentCount | Lifetime count of rows in S3 dispatch.delivery_assignment grouped by courier_profile_id. Includes assignments with no proof and all proof states, including COMPLETED. No parcel-status or date filter; unchanged |
| items[].active | S1 courier active flag; not a delivery count; unchanged |
| items[].accountStatus | S1 account status string; unchanged |
| items[].availability | AVAILABLE iff active=true and accountStatus equals ACTIVE ignoring case; otherwise UNAVAILABLE. This is eligibility, not remaining capacity; unchanged |
| total | Number of courier items remaining after name/availability filters; not number of deliveries; unchanged |
| overloadConfigured | Always false; no threshold, load percentage or overloaded status exists; unchanged |
| active/completed/failed/reassigned/returned delivery counts | Not fields in this API and not derived by this query; no new fields added |

Optional `search` is trimmed and matched case-insensitively against names using Locale.ROOT; maximum raw length 200. A null name behaves as empty text for searching. Optional availability accepts empty/blank, available or unavailable (lowercase input); invalid values return the existing 400 INVALID_WORKLOAD_FILTER. Results sort by case-insensitive name, nulls last, then courier UUID. No pagination, from/to filter or “today” window exists.

The existing S1 directory call is retained: one S3 aggregate query plus one `IdentityCourierClient.listAll` HTTP request. Names and eligibility come from S1; counts come only from S3's database. No new cross-service call, cross-service database access or N+1 per-courier lookup is added. Zero-assignment and inactive couriers returned by S1 are included (inactive means UNAVAILABLE), unless filters exclude them. S1 lookup failures fail closed rather than returning invented totals.

With a complete, unfiltered, unique S1 directory covering all assignment courier IDs, the sum of assignmentCount equals the distinct assigned parcel count. Filters deliberately reduce that sum. A missing/deleted courier profile in the directory would omit its row although its S3 assignment count remains stored; no synthetic directory entry is invented. Cross-service directory/assignment snapshots are not atomic. Completeness under missing profiles or concurrent S1 availability changes is a risk/limitation, not a claim established by the aggregation query.

## S3 source data and supported state changes

- `V1__dispatch_assignments.sql:5`: delivery_assignment has parcel_id UUID primary key, courier_profile_id and courier_user_id non-null UUIDs, nonblank assigned_by_subject and non-null TIMESTAMPTZ assigned_at default now(). One assignment row per parcel, without assignment status, history, soft delete, unassigned_at or completed_at. Index ix_delivery_assignment_courier supports courier lookup.
- `AssignmentRepository.java:55`: COUNT(*) grouped by courier_profile_id; no joins, CASE/status comparisons, timestamp predicates or nullable group key. The parcel primary key makes COUNT(*) equivalent to COUNT(DISTINCT parcel_id). A full aggregate scan need not use the courier index.
- `AssignmentRepository.java:66`: insertion holds a parcel-scoped transaction advisory lock, then INSERT ON CONFLICT DO NOTHING; duplicate conflict becomes 409 DELIVERY_ALREADY_ASSIGNED. DB uniqueness is the final arbiter across service instances. Existing assignments are never overwritten by the public service.
- `DispatchService.java:103`: an existing parcel assignment is rejected before parcel/courier dependency calls, including after proof completion or rejected/blocked attempts. There is no reassignment, release or unassignment command. DISPATCH_REASSIGN is reserved, not implemented.
- `V2__proof_of_delivery.sql:1`: a proof has a unique parcel foreign key to the assignment. Evidence is immutable; completion bookkeeping may change. Proof states are PENDING, COMPLETED, REJECTED and BLOCKED. COMPLETED iff completed_at is non-null. That timestamp is local S3 acknowledgment time, not the authoritative S2 transition time. Proof retry leases/attempts are not delivery-attempt history.
- `ProofCompletionWorker.java:41` and `ProofRepository.java:57`: a worker claims PENDING work with row locking, calls the existing S2 status client outside the claim transaction, and records success or recoverable/terminal completion failure. Retry exhaustion is BLOCKED. Retrying this completion acknowledgment does not create another assignment.

S2 is the only parcel lifecycle writer: CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED and FAILED. RETURNED is not a current S2 enum value. S3 has no complete local parcel lifecycle projection, no FAILED assignment state, no general status-event consumer, and no assignment-event publisher. This agrees with [#136 event verification](../events/courier-notification-verification.md). A successful S3 proof acknowledgment provides limited evidence of one delivery completion; it does not make S3 authoritative for all terminal states. Independent dispatcher S2 changes are not reflected in this workload. Active workload and per-attempt attribution cannot be inferred reliably by subtracting local proof completions.

No failed-delivery retry/redelivery or reassignment lifecycle is implemented. Accordingly the tests assert rejection/preservation rather than fabricating reassigned or failed rows. S3 cannot implement the originally requested lifecycle metrics solely by changing this query. That would need a separately approved contract and local state/event design; S2 DB reads or new status-lookup calls are not a shortcut.

## Time and concurrency semantics

Both workload and courier GET /api/v1/courier/deliveries include all assignments, across midnight and regardless of proof completion. The frontend TodayDeliveriesPage name does not imply server-side today filtering. The dispatch queue's separate from/to UTC filtering is not used by workload.

S3 configuration fixes no JVM or database session timezone for this metric; TIMESTAMPTZ preserves instants and the count does not inspect timestamps. The boundary test sets the JVM to Asia/Colombo and a held PostgreSQL session explicitly to UTC; it asserts both settings. PostgreSQL JDBC startup otherwise follows the JVM timezone and can override a database default. Counts are unchanged around Colombo midnight (18:30 UTC), UTC midnight, a next-day boundary, and a proof completion on a later day. No start/end inclusivity is invented for an endpoint with no time window.

Default PostgreSQL READ COMMITTED gives one statement-consistent aggregate snapshot. Tests verify uncommitted inserts are invisible and become countable after commit. Counts and S1 roster/availability are separate snapshots, not a globally atomic fleet view. Proof state changes cannot duplicate an assignment count because the aggregate never joins proofs. There are no concurrent reassignments/status updates to an assignment lifecycle in the current implementation; proof workers use unique evidence and leased bookkeeping separately.

## Deterministic verification fixtures

| Courier fixture | Assignment/proof composition | Expected lifetime assignmentCount | Availability |
|---|---|---:|---|
| Synthetic A | No proof + PENDING + COMPLETED | 3 | AVAILABLE |
| Synthetic B | REJECTED + BLOCKED | 2 | AVAILABLE |
| Synthetic inactive | COMPLETED | 1 | UNAVAILABLE |
| Synthetic zero | No assignments | 0 | AVAILABLE |
| Sum | Six distinct assigned parcels | 6 | response total is 4 couriers |

An independent recount reads raw parcel/courier IDs and counts in Java, without copying the production COUNT/GROUP BY. It asserts uniqueness and per-courier totals. Additional cases cover empty data/directory, one courier, 10,000 assignments, forbidden duplicate rows, rejected invalid proof status, null assigned_at, orphan proof foreign key, five before/after-proof reassignment rejection cases, UTC/Colombo midnight, and assignment reads while a transaction holds a competing insertion.

The existing two-service assignment race now uses different couriers: exactly one request commits, the losing request gets DELIVERY_ALREADY_ASSIGNED, audit subject/courier attribution matches the winner, the aggregate contains exactly that courier's single count, and the parcel leaves the unassigned queue. Existing advisory-lock tests cover both commit and rollback release.

A real S3 HTTP/PostgreSQL test exercises an acknowledged COMPLETED proof and verifies workload still reports A=2, B=1, a zero courier=0 and an inactive courier=0; total=4. It checks response fields/types/enums/nullability against the relevant OpenAPI schemas and fields consumed by frontend WorkloadPage/workloadApi.ts (read-only inspection). These are targeted schema checks, not a general-purpose JSON Schema validator. Existing S2 Maven Swagger Parser tests validate the OpenAPI artifacts in the full build. No browser/Node tooling runs here.

Existing signed-JWT tests were extended to reject SERVICE/CUSTOMER/MERCHANT/COURIER on workload, using the real signature/issuer/audience/permission security chain. Existing tests cover anonymous denial, DISPATCH_VIEW enforcement and cross-courier delivery/proof ownership. The HTTP/PostgreSQL fixture uses a JWT decoder double and S1/S2 doubles; it is not a real gateway or live Keycloak test.

## Findings

| ID | Classification / severity | Evidence | Status |
|---|---|---|---|
| W1 | RISK / high if interpreted as active capacity | Completed proofs stay counted; failed and reassigned lifecycle data are absent. UI correctly says Assigned deliveries. | User approved current metric verification; lifecycle design separate. No false active-count claim |
| W2 | PROVEN GAP / low, documentation | Runtime and frontend allow a null courier name; OpenAPI declared only string. HTTP contract test failed before correction. | Fixed documentation/schema artifact and declarative generator source; no runtime body change |
| W3 | RISK / medium | Workload uses S1 roster after S3 count snapshot; omitted profiles and changing availability prevent an atomic fleet view. | Limitation documented; no new dependency or inferred courier entry |
| W4 | OK | No supported-operation double count, wrong courier attribution or arithmetic mismatch found. Parcel PK, non-null group key and no joins support accurate lifetime counts. | PostgreSQL recount and competing-courier assertions |
| W5 | OK | Reassignment is rejected in all five no-proof/proof-state cases with audit and proof preserved. | Tests pin existing 409 behavior; no lifecycle invented |
| W6 | OK | Workload total means courier count; eligibility is not capacity; all-time list and workload agree. | Definitions + real HTTP fixture |
| W7 | OK | Gateway DISPATCHER and S3 DISPATCH_VIEW; own-courier reads require both profile and user IDs. | Existing gateway/security/ownership tests reused/extended |
| W8 | OK | Workload response contains defined IDs, name, account/availability flags and count, without phone/address/proof evidence or actor subject. | HTTP response assertion; no workload payload logging found |

No serious supported-operation misattribution, duplicate count, active-count bug or courier access to another courier's workload was demonstrated. No production query fix is justified by this evidence.

## Follow-up issue text — lifecycle workload design

**Title:** Define S3 lifecycle workload metrics and reassignment/retry attribution

**Owning service:** S3 Dispatch & Courier, coordinating S2 lifecycle/event ownership.

**Owner:** @Nilakshana23 (#33/#63 and DispatchService/AssignmentRepository git history); S2 lifecycle/events owner @14Jannan (#28/#58); coordinator/product @pirathee587. Recent S3-wide history also includes @pavi3alnx. #134 implementation owner remains @Deadsecnote1 and independent reviewer @kirusigan03.

**Gap/reproduction:** Assign one parcel, acknowledge its proof as COMPLETED, then read workload: assignmentCount remains one, as designed. S2 FAILED changes do not enter an S3 lifecycle projection. Trying to assign the parcel to another courier returns 409; no reassignment history/failed-delivery retry model exists.

**Proposed next action:** Approve definitions and attribution for active/completed/failed/reassigned states and failed-delivery retries, time windows/timezone, historical courier identity, capacity thresholds and roster omissions. Decide an approved S3-owned local lifecycle projection/events and any migration only in that separate design task. Preserve event identity/order/idempotency, and the five-service boundary. Do not read S2's database or add per-parcel status lookups to force totals.

**Acceptance:** explicit metric/response contract, immutable attribution/attempt semantics, event and migration approval, gateway/frontend/docs rollout and boundary/concurrency tests. No such feature is implemented in #134.

## Evidence and actual runs

Base SHA: `8231dcd0adc54243504a5342cffdd8e210ad7659`. Merged commit SHA: **to be recorded after merge**. Independent acceptance: pending. This is tests/documentation work; routes, roles, database schema, query results and runtime API response contract are unchanged. OpenAPI definitions were corrected to describe existing behavior. WARP.md contains no workload metric definition to amend.

Initial focused repository run: 15 cases, one failed test setup assertion because the JDBC session timezone followed the JVM; explicit held UTC session fixed the fixture. Focused rerun: **15 tests, 0 failures/errors/skips**, BUILD SUCCESS in **10.071 seconds**. Contract-before run: **10 cases, one failure** for nullable name versus the old spec; after documentation correction the module passed.

`mvn --batch-mode --no-transfer-progress -f backend/pom.xml -pl dispatch-courier-service -am test package`: **BUILD SUCCESS, 22.858 seconds**. S3 **192 tests**, security starter **7 tests**, zero failures/errors/skips. PostgreSQL/Testcontainers ran. No test was disabled to obtain a pass.

The OpenAPI JSON and docs/api/build_openapi.py declarative definitions were edited as text; Python was not executed. No frontend, Node tooling, production data or cross-service database was used. Raw logs stay local under /tmp/134-*.log; committed evidence contains synthetic fixture definitions and aggregate results only.

### Query plan observation (synthetic Testcontainers data)

The S3 module run recorded the following EXPLAIN (ANALYZE, BUFFERS) after ANALYZE on the 10,000-row synthetic single-courier cardinality fixture. This is a safe local probe, not a realistic multi-courier production benchmark or performance acceptance result. Existing indexes were retained and no migration is proposed.

```text
HashAggregate  (cost=284.00..284.01 rows=1 width=24) (actual time=1.753..1.754 rows=1 loops=1)
  Group Key: courier_profile_id
  Batches: 1  Memory Usage: 24kB
  Buffers: shared hit=134
  ->  Seq Scan on delivery_assignment  (cost=0.00..234.00 rows=10000 width=16) (actual time=0.004..0.682 rows=10000 loops=1)
        Buffers: shared hit=134
Planning:
  Buffers: shared hit=89
Planning Time: 0.569 ms
Execution Time: 1.859 ms
```

### Actual environment

```text
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-1-24.04.4-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.12.1+1-1-24.04.4-Ubuntu, mixed mode, sharing)
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.12.1, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-42-generic", arch: "amd64", family: "unix"
Docker version 29.8.0, build 88096ef
Linux 7.0.0-42-generic
```

These are real java -version, mvn -v, docker --version and uname -sr outputs, with Maven ANSI decoration removed. PostgreSQL/Testcontainers connected to Docker. Production PostgreSQL/JVM timezone was not inspected; explicit UTC/Asia-Colombo settings in the test are fixture evidence only.

### Acceptance/checklist evidence

| Criterion | Proof / limitation |
|---|---|
| Inspect/reuse current implementation and related work | WARP.md, #134/#33/#63/#28/#58/#136 and existing docs; original assignment and workflow fixtures extended |
| Preserve service/database boundaries | Counts query S3 dispatch.delivery_assignment only; existing S1 directory and completion client unchanged; no production Java change |
| Lifetime count accuracy | Independent Java recount, six-parcel/four-courier fixture, 10,000-row case, proof-state parameter cases |
| Active/completed/failed/reassigned metrics | User explicitly moved these to a separate lifecycle design; not fields in current API and not fabricated |
| Reassignment semantics | Existing 409 before/after each supported proof state; DB/audit/evidence unchanged |
| Totals/zero/inactive couriers | Recount sum equals distinct parcels for complete unfiltered fixture; zero/inactive directory rows returned; total=filtered couriers |
| Windows/timezone | No window in query or courier list; explicit UTC DB session versus Colombo JVM and midnight/later-completion fixtures |
| SQL/constraints/concurrency | No joins; parcel PK + non-null key; different-courier assignment race; uncommitted read/lock release tests; local synthetic plan above |
| Authorization and ownership | Existing RS256/JWKS signed-token chain extended for workload wrong roles; dispatcher view permission and own-courier tests reused |
| Contract and privacy | Real S3 HTTP+DB fixture; fields/types/nullability/enums compared with OpenAPI; no evidence/contact/actor fields in workload |
| Tests/build | Actual module/full-reactor results; skip/NOT RUN groups explicitly separated below |
| PR / independent acceptance | Focused develop Draft linked from #134; kirusigan03 reviewer requested; acceptance pending |
| Base SHA | 8231dcd0adc54243504a5342cffdd8e210ad7659 |
| Merged commit SHA | to be recorded after merge |

Not verified: real gateway end-to-end, frontend rendering, production data volume/query performance, live Keycloak, concurrent S1 roster/eligibility changes or missing-profile reconciliation. Failed delivery/reassignment/redelivery counts cannot be validated against an absent S3 lifecycle model; that is the explicitly approved separate design scope. No production data was queried.

### Full backend build

`cd backend && mvn --batch-mode test package` completed **BUILD SUCCESS in 6:25**; every module packaged successfully.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 7 | 0 | 0 | 0 |
| api-gateway | 193 | 0 | 0 | 0 |
| identity-partner-service | 336 | 0 | 0 | 0 |
| parcel-pricing-service | 628 | 0 | 0 | 0 |
| dispatch-courier-service | 192 | 0 | 0 | 0 |
| tracking-reporting-service | 48 | 0 | 0 | 0 |
| notification-integration-service | 380 | 0 | 0 | 0 |
| Total | 1784 | 0 | 0 | 0 |

Root aggregator: no tests. merchant-order-webhook-e2e packaged, but its **two MerchantApprovalGapIT cases NOT RUN** because the module POM sets skipTests=true unless e2e/runE2E is selected. The two lifecycle “Tests are skipped” log entries refer to the same opt-in group, not four test cases; no stale E2E reports are counted. The ordinary modules' Testcontainers cases ran, including S3 PostgreSQL and gateway disposable Keycloak. No gateway skip remains in this merged baseline. Existing S2 Swagger Parser validated all five OpenAPI artifacts. No test was disabled or bypassed for this task.

Full log: /tmp/134-backend-full.log. Module log: /tmp/134-s3-module.log. `git diff --check` passed. PR remains Draft/Refs #134 pending independent acceptance; lifecycle metrics are the separately approved design follow-up, not an implemented feature here. No merge performed.
