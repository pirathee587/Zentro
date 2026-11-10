# Performance and pagination smoke tests (#176)

**Draft evidence: pagination acceptance is only partly met. A nonnumeric S4 page-size parameter returns 500 in the service test. Reports are not testable, no endpoint. Successful dispatcher queue/assignment testing is blocked by the production `UnavailableParcelSource` (upstream 503, gateway 502).** This work does not establish production capacity or an SLA. Measurements on this developer machine are not comparable across machines.

## Safety and commands

Java 21, Maven and Docker with `/var/run/docker.sock` are required. No Node, Python, external load-testing tool or new external library is used. The existing #166 test module supplies Testcontainers PostgreSQL, Kafka and Keycloak, plain `java.net.http.HttpClient` and JUnit. All business requests target a fixture-owned real gateway on `127.0.0.1:18080`. There is no configurable remote target. Ports 18080–18085 must be free. Applications bind to loopback. Each service uses its own database and login; sharing one physical PostgreSQL container is a test-environment optimisation only. SQL fixture setup and diagnostics use the owning service's login. No service receives another service's database credentials.

From `backend/`, the full reference run is one Maven command:

```sh
mvn --batch-mode -Pperf -pl merchant-order-webhook-e2e -am \
  -Dtest=PerformanceSmokeIT -Dsurefire.failIfNoSpecifiedTests=false verify
```

A single measured scenario, with the same fixture setup, inventory, growth and pagination checks:

```sh
mvn --batch-mode -Pperf -pl merchant-order-webhook-e2e -am \
  -Dtest=PerformanceSmokeIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dperf.scenario=tracking verify
```

Scenario selectors: `all`, `booking`, `estimate`, `tracking`, `workload`, `mixed`, `failure`, `lists` (growth/pagination checks without the request ramps). The default fast build skips this module's integration tests. `-Pe2e` continues to select the pre-existing merchant E2E tests and explicitly excludes the performance test. No CI performance job is introduced; running this profile in CI requires separate approval.

```sh
mvn --batch-mode test package
```

Runs the normal backend tests, including small booking duplicate-submission and assignment-race tests already in S2/S3. The new S4 PostgreSQL test walks a small dataset and counts Hibernate prepared statements. S5 tests extend the existing controller and PostgreSQL-backed webhook suites rather than duplicating them.

## Fixture and bounds

Thresholds live in `backend/merchant-order-webhook-e2e/src/test/resources/performance-smoke.properties`; explicit `-Dperf.<key>=<value>` overrides are recorded with results. Default: 3 discarded warm-up requests; 50 measured requests at each of 1, 5, 10 and 25 concurrent clients; 5-second HTTP timeout; 30-second limit per level; p95 sanity ceiling 1000 ms. The ramp aborts after repeated failures or a missed bound, and stops if p95 degrades more than threefold. There is a 15-minute test timeout and a 20-minute fork timeout. These are deliberately short smoke samples, not a sustained-duration benchmark. Keycloak token acquisition/refresh is outside measured HTTP samples. Rate limits are raised only in the disposable gateway fixture to avoid measuring its default per-minute budget.

Seed 10,000 S4 parcels and 10,000 history events with an explicit dense owner scope of 6,000 rows; the remainder span synthetic customer and merchant subjects. Initial tracking seeding time is reported separately; other setup occurs outside timed requests. Whole-list growth uses 100, 1,000, 5,000 and 10,000 synthetic users/courier profiles/merchant applications, assignments for the fixture courier, orders for the fixture merchant and additional active zones. A separate history parcel grows from 100 to 10,000 events: final S4 seed totals are 10,001 parcels and 20,000 events before API bookings. Existing five zones and small identity fixtures explain counts slightly above the growth volume. Each owning database is ANALYZEd after incremental setup. S2/S3 setup writes only to their own databases; synthetic assignment linkage is fixture data, not evidence of a functioning S2→S3 claim protocol. Phones and addresses are marked synthetic; notifications use the existing mock sender and webhook receivers are loopback stubs.

Measured flows: new bookings and estimates; five concurrent submissions sharing one booking key; hot/cold/unknown tracking; first/deep tracking pages; workload; available-flow mixed reads/bookings with Kafka consumers; a failing webhook receiver; and a slow stub upstream behind the real gateway while tracking stays real. The isolation fixture adds five bounded control clients while the foreground ramps, so its combined upper bound is 30 in-flight client requests (25 foreground + 5 control); other ramps use at most 25. The mixed scenario substitutes workload reads for unavailable queue reads and omits unavailable assignment/status flows. It is not the complete originally requested mix.

CSV reports request counts, expected-status successes, status classes, transport/integrity errors, req/s, nearest-rank p50/p95/p99/max over every sample, error rate, response bytes and host CPU/memory snapshots. Expected 404/504 responses count as scenario successes while retaining their actual status class. `pg_stat_statements` execution-time/call deltas cover owning-database SELECTs during growth requests; they exclude JDBC/network/serialization time and may include background activity. They are diagnostic signals, not exact isolated request spans. `EXPLAIN (ANALYZE, BUFFERS)` plans use actual repository predicates and projections. GC logs are test-process artifacts; heap maxima are 256 MiB per application. Host memory snapshots do not measure per-request allocations or peak RSS. Unexposed Hikari wait/usage and database lock waits remain unverified.

Artifacts appear under `backend/merchant-order-webhook-e2e/target/`: `performance-results.csv`, `performance-growth.csv`, plans, lag evidence, findings and `gc-*.log`. Service logs and authentication material are not committed. Reference CSV and safe excerpts are under [performance-smoke-evidence](performance-smoke-evidence/).

## Controller and pagination inventory

The inventory reflects controller/service/repository code at base `144f0cc121cd36ab3a92ffded5f7f810963bd29b`, not reserved gateway prefixes. No routes, roles, defaults, ordering semantics, authorization or response contracts change in this PR.

| Service / endpoint | Shape, default / max | Ordering / unique tie | Count / unbounded behavior | Invalid parameters |
|---|---|---|---|---|
| S2 POST `/parcels`, `/parcels/estimate` (`/api/v1` prefix throughout) | Booking/quotation exist | n/a | n/a | Existing validation retained |
| S2 GET `/parcels` | No controller | n/a | Not testable | Not applicable |
| S2 GET `/merchant/orders` | Array; `limit=50`, max 100; no page traversal | `created_at DESC`; no unique tie | No total; **bounded**, older records inaccessible through list | `limit` clamped 1–100; numeric conversion handled by existing advice |
| S3 GET `/dispatch/queue`, `/dispatch/unassigned` | Whole `items` snapshot; no page/default/max | created time + parcel ID; deterministic | In-memory total/matchingTotal only if complete; source 503 is sanitized to gateway 502 | No page/size contract; sort restricted to newest/oldest, invalid filters 400 |
| S3 GET `/dispatch/workload` | Whole `items`; no page/default/max | name + courier ID; deterministic | In-memory filtered total; all S1 couriers and grouped lifetime assignments loaded | Unknown page/size ignored; invalid availability/search rejected |
| S3 GET `/dispatch/eligible-couriers` | Whole array; no page/default/max | Upstream S1 order | No count; source blocks successful flow | Parcel ID conversion and source checks apply |
| S3 GET `/courier/deliveries` | Whole array; no page/default/max | assigned time DESC + parcel ID; deterministic | No total; unbounded for authenticated courier | Unknown page/size ignored |
| S4 GET `/tracking/mine` | Page, 1-based; size 10/max 100; `limit` overrides `size` | created time DESC; **no unique tie** | Spring Data Page count (may omit count on a provably partial page); content and parcels duplicate the same page | Negative page clamps to first; size ≤0 clamps to 1; huge size caps at 100; nonnumeric size returns **500**, known gap |
| S4 GET `/tracking/{ref}`, `/events` | Public summary/history; history arrays unpaged | occurred time ASC; no unique event tie | No total; history unbounded per ref | Unknown reference 404 |
| S4 GET `/tracking/{ref}/stream` (also events with SSE Accept) | Authenticated SSE exists | Event stream, not list pagination | Requires ownership/assignment; no load claim | Existing stream rules retained |
| S4 `/reports/**` | **Not testable, no endpoint** | n/a | Gateway prefix exists without reports controller | No fake endpoint/test added |
| S1 GET `/admin/users` | Whole array; no page/default/max | user created time DESC; no unique tie | No count; unbounded | No page/size contract; existing status/search validation |
| S1 GET `/admin/merchants` | Whole array; no page/default/max | created time ASC; no unique tie | No count; unbounded | No page/size contract; invalid status 400 |
| S1 GET `/admin/couriers` | Whole array; no page/default/max | profile created time DESC; no unique tie | No count; unbounded | No page/size contract; existing filters retained |
| S2 GET `/zones` | Whole active-zone array | Repository order | No count; configuration-sized, no enforced page maximum | No page/size contract |
| S5 GET `/merchant/webhooks/deliveries` | Page, 0-based; size 20/max 100 | created time DESC + ID ASC; deterministic | Merchant-scoped joined COUNT(*) | Negative page clamps to 0; size clamps 1–100; nonnumeric 400; long offset multiplication |
| S5 GET `/admin/notification-attempts`, `/admin/webhook-deliveries` | Page, 0-based; size 25/max 100 **per array** | created time DESC + ID ASC; deterministic | Both routes return both arrays and two COUNT(*) totals | page <0, size outside 1–100 and nonnumeric values rejected; long offset multiplication |

No paged response is assumed for a whole-list endpoint. Repeated reads and page walks do not prove a missing SQL tiebreaker safe: equal timestamps remain a deterministic-order gap. Offset pages can skip/duplicate under concurrent inserts even with a tiebreaker; cursor design belongs in a separately approved contract. No ordering fix is made, as explicitly requested. Filtering/injection-looking sort values cannot become SQL identifiers in the inspected tracking and S5 queries; full fuzz coverage remains limited.

## Configuration and query audit

No explicit Hikari maximum-pool or connection-timeout override was found in the service application YAML: library defaults apply, not evidence of exhaustion. No service-specific Tomcat pool override, gateway client-pool override or list cache was found. Gateway connect timeout is 1000 ms and response timeout 30 s; tracking route metadata extends the response timeout to 3,600,000 ms for streaming and also covers ordinary tracking reads (risk). S2 Kafka uses `acks=all`, idempotence, max block 5000 ms, request timeout 10000 ms and delivery timeout 30000 ms. Outbox defaults: polling 1000 ms, batches 50, max attempts 10, synchronous send timeout 5000 ms. S5 notification/webhook retry polling defaults to 5000 ms; fixture webhook polling is 100 ms. S4 consumer retry defaults to 1000 ms and three attempts. No committed SQL-verbosity change is made.

Relevant existing indexes: S2 customer/created, merchant/created partial index, tracking-reference unique, merchant/external-reference unique, outbox unpublished/occurred and aggregate/occurred partial indexes; S3 assignment courier and courier-user, assignment parcel primary key, proof parcel unique; S4 customer/created, status, merchant and courier subjects, tracking-ref unique and event ref/time; S1 merchant status/created, courier user/active and merchant-key hash; S5 due-status/time, webhook subscription/created and subscription/parcel/created. An index scan is not inherently better for a whole-table administrative list. No new migration is justified by the current evidence; therefore no migration-version collision is introduced.

## Proven change and serious findings

* **PROVEN BOTTLENECK — fixed:** S4 paged history performed a separate history SELECT for every parcel. Same machine, same 21-row seed, page size 20: 22 statements / 41.764 ms before; 3 statements / 19.734 ms after. Single-request elapsed times include framework noise; the statement reduction is the robust evidence. Batch histories only for the existing bounded page and group by tracking reference, retaining chronological order. A normal PostgreSQL regression test enforces ≤3 statements and verifies event content/order and owner isolation. No migration, cache, pool, event or contract change.
* **PROVEN DEFECT — follow-up:** authenticated GET `/api/v1/tracking/mine?size=abc` returns 500 instead of safe 400. `TrackingPaginationIntegrationTest` characterizes the current response explicitly; a green characterization test does not make this behavior acceptable. S4 error advice catches conversion failures generically. No API error-contract change is included.
* **PROVEN INTEGRATION GAP:** real queue/assignment source returns `503 DISPATCH_SOURCE_UNAVAILABLE`, deliberately sanitized to 502 by the gateway; successful queue growth, queue deep pages and gateway assignment races cannot be measured. Existing small S3 concurrency tests use a test source and are not end-to-end proof.
* **GAP:** reports are not testable, no endpoint. A gateway route is not an implementation. #74 is the reporting dashboard context, not proof that a reports API contract exists.
* **RISK:** unbounded S1/courier/workload responses and missing timestamp tiebreakers; measured growth results determine prioritisation. Merchant orders are already bounded. Per-request S2→S1 approval checking is retained; its database/call cost is included in growth evidence. No caching proposal is implemented.
* **OK at the tested scope:** new booking row/outbox correspondence, duplicate-key single creation, public lookup/404 handling, existing page-size bounds, page walks and S5 timestamp-tie/ownership checks passed their executed assertions. These do not establish full release acceptance or a safe capacity.

## Reference execution evidence

The two completed final-code reference runs and full tables follow below. An interrupted startup is not a completed run. Earlier attempts included an interrupted startup, a Kafka startup failure (cause unconfirmed), an incorrect fixture expectation of gateway 503 instead of its deliberate 502 mapping, and exploratory/preliminary runs with missed sanity bounds. They are excluded from the final reference comparison. Reference thresholds were not loosened. Supplemental failure/list checks were validated before the final source freeze; both final full runs include those checks.

Base SHA: `144f0cc121cd36ab3a92ffded5f7f810963bd29b`. Execution date: 2026-11-10, Asia/Colombo. Initial host: 16 logical CPUs, 7.5 GiB RAM, about 2.2 GiB available, 4.0 GiB swap; JVM/container memory pressure can make results inconclusive. Full `mvn --batch-mode test package` completed successfully in 9m41s. Per-module counts: security 62, gateway 210, S1 354, S2 675, S3 259, S4 91, S5 422; zero failures/errors/skips in that execution. The E2E module was intentionally skipped by its default configuration, not counted as a Testcontainers pass. Subsequent focused results and final reference artifacts supplement this build.

## Acceptance evidence and limitations

| #176 acceptance criterion | Evidence / status |
|---|---|
| Inspect implementation/issues and reuse infrastructure | Controller inventory; WARP; issues #18/#30/#28/#31/#74/#124/#134/#144/#166; #66 gateway fixture and #166 module reused |
| Five-service and database ownership | Separate processes, five databases/logins; owning-service SQL setup/diagnostics only |
| Authentication/authorization/ownership | Real Keycloak tokens through real gateway/services; existing normal security suites; added per-page and per-volume checks; coverage limits stated |
| Unit/integration negative cases | S4 PostgreSQL pagination/query-count test; extended S5 negatives; reused booking and assignment concurrency suites |
| API/event docs if contracts change | No approved contract changes; API/event specifications unchanged |
| Relevant tests/build and actual results | Build evidence above; completed reference results below; no fabricated timings |
| Pagination correctness | **Partly met:** existing page bounds/page walk; timestamp ties and malformed S4 params remain gaps; whole-list contracts unchanged by explicit user decision |
| Booking/tracking/queue/reporting smoke | **Partly met:** available flows measured; queue/assignment integration and reports unavailable |
| Focused PR to develop | Draft PR, Refs #176; independent reviewer requested in GitHub field |
| Independent approval | Pending @14Jannan; no approval claimed |
| Merged commit SHA | to be recorded after merge |

Not verified: production-like environment, real network, production data volume/distribution, long-duration soak, Kubernetes limits, TLS overhead, reports, successful production queue/assignment integration, browser/SSE load performance, peak per-request allocations, full SQL-count attribution for remote calls, all S4 absurd-page overflow cases, dense merchant-subject tracking-page completeness, field-by-field minimisation of authorised list responses, and the requested complete mixed status-update flow. Synthetic assumptions for prioritisation are not product forecasts: 100 courier daily assignments is a planning scenario, 1,000–10,000 monthly merchant orders and 1,000–10,000 open queue items are sensitivity cases. Real operating volumes require product/operations evidence. Courier API currently returns lifetime assignments, not a daily window, which increases its growth risk.

Follow-up issue text is prepared in [performance-pagination-follow-ups.md](performance-pagination-follow-ups.md); no follow-up issues are created automatically.

## Completed reference runs

Both final reference runs use source snapshot `4eec084a22bc39cae901194ad5898c36d27fa61c`, the same seed sizes, 50 requests per level, warm-up 3, concurrency 1/5/10/25, 1000 ms p95 ceiling and factor-3 growth guard. They include the final contrasting foreign-owner rows, repeat-read checks and actively concurrent slow-upstream controls. Earlier exploratory/reference snapshots remain outside these final tables. Settings hashes and actual JUnit/Maven outcomes are included per run below. No threshold was loosened.

Expected timeout 504s and unknown-reference 404s count as correct scenario responses while retaining actual status classes. Both booking row/outbox checks and the five-submission/one-created duplicate assertion are required before a run reaches its final findings assertion. Kafka groups for tracking, notifications and webhooks must drain to zero numeric lag after mixed load; idle empty partitions without committed offsets are not counted as negative lag. Successful production queue, assignment and reporting flows remain unverified.

Whole-list growth uses one bounded request per volume, not a percentile. No new API limit is enforced on whole lists. `maxRows=10000` and `maxPayloadBytes=16777216` are interpretation markers. Small identity fixtures and five pre-existing zones explain response counts above nominal volume. No larger-volume capacity is inferred. Query execution time is only part of end-to-end cost; a whole-table scan alone is not proof of a missing index. The measured S4 N+1 is the only production fix. Bulk remote transfer/mapping/serialization and heap pressure are risks; request-level causal attribution remains inconclusive. Absolute-bound misses and the factor-3 growth guard are reported separately: short samples can trip the guard well below 1000 ms.

Host CPU/memory snapshots for every level/volume are retained in CSV. `totalMemory-freeMemory` includes reclaimable OS cache and is not active working set or proof of exhaustion. This host has 7.5 GiB RAM and existing swap use; these observations do not prove production memory exhaustion. Actual required command output is in [environment.txt](performance-smoke-evidence/environment.txt). The snap Docker wrapper produced an empty redirected file; its observed unredirected output is transcribed explicitly. GC summaries cover each service process; gateway summary covers its final process after the timeout-fixture restart, not earlier gateway GC. No request-level GC attribution is claimed.

Final focused validation: **61 tests passed, zero skips** (13 S4, 48 S5), including the 3-statement regression and S5 timestamp-tie/full-union checks. Reused merchant E2E fixture: **2 passed, zero skips**. See [backend build](performance-smoke-evidence/backend-build.txt), [focused tests](performance-smoke-evidence/focused-tests.txt) and [E2E regression](performance-smoke-evidence/e2e-regression.txt). No Testcontainers test in these executed selections was skipped. The default full build intentionally did not execute the opt-in merchant/perf module.

### Reference run 1: execution and findings

```text
PERF_SEED trackingParcels=10000 events=10000 elapsedMs=356.861
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 181.2 s <<< FAILURE! -- in com.zentro.e2e.PerformanceSmokeIT
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] Total time:  03:06 min
4eec084a22bc39cae901194ad5898c36d27fa61c
35dd9732d2026c58955dfb9affb925b9a332de1db8d5d53a83f00db4412cadb5  backend/merchant-order-webhook-e2e/src/test/resources/performance-smoke.properties

tracking-cold ramp stopped: p95 degraded beyond configured factor
workload concurrency=25 missed sanity bound; see CSV
failing-webhook-booking ramp stopped: p95 degraded beyond configured factor
```

Measured requests: **2600**; unexpected/integrity failures: **0**; largest p95: **1718.516 ms**.

Latency columns are milliseconds. Status classes are 2xx / 4xx / 5xx. [Raw CPU/memory and byte samples](performance-smoke-evidence/run-1/performance-results.csv).

| Scenario | Clients | Requests | req/s | p50 | p95 | p99 | Max | Error % | Status classes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| booking | 1 | 50 | 26.533 | 30.783 | 65.570 | 69.069 | 69.069 | 0.00 | 50 / 0 / 0 |
| booking | 5 | 50 | 120.623 | 38.209 | 51.774 | 74.209 | 74.209 | 0.00 | 50 / 0 / 0 |
| booking | 10 | 50 | 132.999 | 62.480 | 101.123 | 106.960 | 106.960 | 0.00 | 50 / 0 / 0 |
| booking | 25 | 50 | 157.318 | 126.253 | 199.355 | 223.389 | 223.389 | 0.00 | 50 / 0 / 0 |
| estimate | 1 | 50 | 56.994 | 17.390 | 20.937 | 24.474 | 24.474 | 0.00 | 50 / 0 / 0 |
| estimate | 5 | 50 | 168.041 | 24.271 | 52.501 | 54.288 | 54.288 | 0.00 | 50 / 0 / 0 |
| estimate | 10 | 50 | 185.212 | 38.003 | 99.771 | 119.180 | 119.180 | 0.00 | 50 / 0 / 0 |
| estimate | 25 | 50 | 312.640 | 65.433 | 128.869 | 143.506 | 143.506 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 1 | 50 | 55.530 | 17.848 | 21.887 | 22.521 | 22.521 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 5 | 50 | 232.962 | 18.889 | 35.649 | 39.136 | 39.136 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 10 | 50 | 306.918 | 25.736 | 51.983 | 61.389 | 61.389 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 25 | 50 | 447.557 | 40.553 | 75.307 | 77.862 | 77.862 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 1 | 50 | 68.399 | 14.111 | 16.748 | 19.999 | 19.999 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 5 | 50 | 351.734 | 13.077 | 15.742 | 15.983 | 15.983 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 10 | 50 | 499.109 | 17.330 | 21.311 | 26.826 | 26.826 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 25 | 50 | 485.331 | 37.635 | 81.359 | 89.732 | 89.732 | 0.00 | 50 / 0 / 0 |
| tracking-unknown | 1 | 50 | 79.153 | 12.146 | 16.201 | 19.912 | 19.912 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 5 | 50 | 493.159 | 9.277 | 11.282 | 12.050 | 12.050 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 10 | 50 | 614.989 | 14.377 | 22.555 | 26.107 | 26.107 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 25 | 50 | 599.640 | 32.874 | 54.129 | 59.822 | 59.822 | 0.00 | 0 / 50 / 0 |
| mine-first | 1 | 50 | 45.781 | 20.685 | 26.517 | 33.028 | 33.028 | 0.00 | 50 / 0 / 0 |
| mine-first | 5 | 50 | 219.446 | 16.995 | 34.059 | 38.722 | 38.722 | 0.00 | 50 / 0 / 0 |
| mine-first | 10 | 50 | 340.213 | 26.684 | 36.367 | 40.662 | 40.662 | 0.00 | 50 / 0 / 0 |
| mine-first | 25 | 50 | 286.652 | 64.159 | 106.634 | 114.363 | 114.363 | 0.00 | 50 / 0 / 0 |
| mine-deep | 1 | 50 | 45.995 | 21.308 | 27.350 | 30.875 | 30.875 | 0.00 | 50 / 0 / 0 |
| mine-deep | 5 | 50 | 252.212 | 17.916 | 21.024 | 21.497 | 21.497 | 0.00 | 50 / 0 / 0 |
| mine-deep | 10 | 50 | 306.149 | 27.908 | 43.498 | 44.881 | 44.881 | 0.00 | 50 / 0 / 0 |
| mine-deep | 25 | 50 | 307.997 | 64.974 | 103.429 | 114.514 | 114.514 | 0.00 | 50 / 0 / 0 |
| workload | 1 | 50 | 9.447 | 106.610 | 177.783 | 198.801 | 198.801 | 0.00 | 50 / 0 / 0 |
| workload | 5 | 50 | 35.084 | 115.797 | 273.369 | 282.292 | 282.292 | 0.00 | 50 / 0 / 0 |
| workload | 10 | 50 | 25.463 | 331.254 | 696.521 | 701.936 | 701.936 | 0.00 | 50 / 0 / 0 |
| workload | 25 | 50 | 23.669 | 949.228 | 1718.516 | 1755.466 | 1755.466 | 0.00 | 50 / 0 / 0 |
| mixed-available | 1 | 50 | 21.401 | 15.517 | 139.327 | 178.505 | 178.505 | 0.00 | 50 / 0 / 0 |
| mixed-available | 5 | 50 | 86.841 | 15.125 | 160.956 | 172.038 | 172.038 | 0.00 | 50 / 0 / 0 |
| mixed-available | 10 | 50 | 72.680 | 30.366 | 324.560 | 335.572 | 335.572 | 0.00 | 50 / 0 / 0 |
| mixed-available | 25 | 50 | 80.165 | 60.839 | 512.496 | 549.036 | 549.036 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 1 | 50 | 122.276 | 8.020 | 9.391 | 12.386 | 12.386 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 5 | 50 | 445.681 | 10.738 | 14.294 | 15.510 | 15.510 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 10 | 50 | 932.507 | 8.851 | 12.327 | 13.039 | 13.039 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 25 | 50 | 933.778 | 22.288 | 34.809 | 36.240 | 36.240 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 1 | 50 | 57.585 | 15.379 | 27.107 | 30.772 | 30.772 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 5 | 50 | 262.246 | 17.721 | 23.199 | 24.774 | 24.774 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 10 | 50 | 471.747 | 18.759 | 22.986 | 25.992 | 25.992 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 25 | 50 | 272.899 | 71.942 | 107.455 | 109.046 | 109.046 | 0.00 | 50 / 0 / 0 |
| slow-upstream-504 | 1 | 50 | 4.556 | 218.245 | 230.253 | 230.821 | 230.821 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 5 | 50 | 22.781 | 218.117 | 225.621 | 229.518 | 229.518 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 10 | 50 | 44.291 | 221.207 | 238.435 | 247.576 | 247.576 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 25 | 50 | 100.926 | 223.205 | 246.179 | 255.212 | 255.212 | 0.00 | 0 / 0 / 50 |
| other-route-responsive | 1 | 50 | 52.601 | 17.786 | 27.445 | 73.922 | 73.922 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 5 | 50 | 253.496 | 18.114 | 28.743 | 56.293 | 56.293 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 10 | 50 | 558.539 | 15.214 | 22.199 | 26.437 | 26.437 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 25 | 50 | 554.172 | 33.569 | 61.184 | 67.422 | 67.422 | 0.00 | 50 / 0 / 0 |

### Reference run 1: whole-list growth

[Owning-database plans](performance-smoke-evidence/run-1/performance-growth-plans.txt); [raw growth samples](performance-smoke-evidence/run-1/performance-growth.csv).

| Endpoint | Seed scope | Rows | Bytes | HTTP ms | DB ms | DB calls |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/admin/users | 100 | 102 | 17509 | 45.420 | 0.246 | 1 |
| /api/v1/admin/merchants | 100 | 101 | 30243 | 34.870 | 0.248 | 1 |
| /api/v1/admin/couriers | 100 | 101 | 30685 | 37.444 | 0.202 | 1 |
| /api/v1/dispatch/workload | 100 | 101 | 21141 | 140.159 | 0.613 | 2 |
| /api/v1/courier/deliveries | 100 | 100 | 18401 | 73.716 | 0.366 | 4 |
| /api/v1/merchant/orders | 100 | 50 | 26683 | 433.876 | 1.445 | 3 |
| /api/v1/zones | 100 | 105 | 13643 | 61.365 | 0.475 | 1 |
| /api/v1/tracking/PERF-HISTORY | 100 | 100 | 40841 | 388.997 | 0.321 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 100 | 100 | 22701 | 51.498 | 0.359 | 2 |
| /api/v1/admin/users | 1000 | 1002 | 173210 | 84.027 | 2.325 | 1 |
| /api/v1/admin/merchants | 1000 | 1001 | 299343 | 49.185 | 2.134 | 1 |
| /api/v1/admin/couriers | 1000 | 1001 | 305186 | 53.525 | 2.661 | 1 |
| /api/v1/dispatch/workload | 1000 | 1001 | 210144 | 88.708 | 3.057 | 2 |
| /api/v1/courier/deliveries | 1000 | 1000 | 184001 | 164.835 | 5.471 | 4 |
| /api/v1/merchant/orders | 1000 | 50 | 26801 | 133.600 | 0.694 | 3 |
| /api/v1/zones | 1000 | 1005 | 130645 | 73.686 | 2.509 | 1 |
| /api/v1/tracking/PERF-HISTORY | 1000 | 1000 | 405341 | 120.145 | 2.095 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 1000 | 1000 | 227001 | 64.704 | 1.687 | 2 |
| /api/v1/admin/users | 5000 | 5002 | 869210 | 155.893 | 18.024 | 1 |
| /api/v1/admin/merchants | 5000 | 5001 | 1495343 | 63.786 | 9.014 | 1 |
| /api/v1/admin/couriers | 5000 | 5001 | 1529186 | 109.337 | 10.373 | 1 |
| /api/v1/dispatch/workload | 5000 | 5001 | 1054144 | 136.566 | 9.473 | 2 |
| /api/v1/courier/deliveries | 5000 | 5000 | 920001 | 120.002 | 15.441 | 4 |
| /api/v1/merchant/orders | 5000 | 50 | 26901 | 103.798 | 0.576 | 2 |
| /api/v1/zones | 5000 | 5005 | 658645 | 97.906 | 10.396 | 1 |
| /api/v1/tracking/PERF-HISTORY | 5000 | 5000 | 2025341 | 233.710 | 11.236 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 5000 | 5000 | 1135001 | 73.177 | 5.478 | 2 |
| /api/v1/admin/users | 10000 | 10002 | 1739211 | 83.286 | 27.392 | 1 |
| /api/v1/admin/merchants | 10000 | 10001 | 2990343 | 70.159 | 16.911 | 1 |
| /api/v1/admin/couriers | 10000 | 10001 | 3059187 | 98.716 | 11.632 | 1 |
| /api/v1/dispatch/workload | 10000 | 10001 | 2109147 | 113.346 | 18.391 | 2 |
| /api/v1/courier/deliveries | 10000 | 10000 | 1840001 | 145.757 | 24.535 | 4 |
| /api/v1/merchant/orders | 10000 | 50 | 26901 | 54.669 | 0.711 | 2 |
| /api/v1/zones | 10000 | 10005 | 1318647 | 118.941 | 29.839 | 1 |
| /api/v1/tracking/PERF-HISTORY | 10000 | 10000 | 4050341 | 166.268 | 9.836 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 10000 | 10000 | 2270001 | 75.029 | 7.055 | 2 |

### Reference run 2: execution and findings

```text
PERF_SEED trackingParcels=10000 events=10000 elapsedMs=297.092
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 178.7 s <<< FAILURE! -- in com.zentro.e2e.PerformanceSmokeIT
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] Total time:  03:05 min
4eec084a22bc39cae901194ad5898c36d27fa61c
35dd9732d2026c58955dfb9affb925b9a332de1db8d5d53a83f00db4412cadb5  backend/merchant-order-webhook-e2e/src/test/resources/performance-smoke.properties

booking ramp stopped: p95 degraded beyond configured factor
workload concurrency=25 missed sanity bound; see CSV
failing-webhook-tracking ramp stopped: p95 degraded beyond configured factor
failing-webhook-booking ramp stopped: p95 degraded beyond configured factor
```

Measured requests: **2500**; unexpected/integrity failures: **0**; largest p95: **1051.589 ms**.

Latency columns are milliseconds. Status classes are 2xx / 4xx / 5xx. [Raw CPU/memory and byte samples](performance-smoke-evidence/run-2/performance-results.csv).

| Scenario | Clients | Requests | req/s | p50 | p95 | p99 | Max | Error % | Status classes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| booking | 1 | 50 | 34.220 | 27.942 | 33.221 | 50.595 | 50.595 | 0.00 | 50 / 0 / 0 |
| booking | 5 | 50 | 87.903 | 47.114 | 118.025 | 140.816 | 140.816 | 0.00 | 50 / 0 / 0 |
| estimate | 1 | 50 | 55.004 | 18.098 | 21.647 | 22.737 | 22.737 | 0.00 | 50 / 0 / 0 |
| estimate | 5 | 50 | 191.456 | 21.824 | 55.349 | 59.802 | 59.802 | 0.00 | 50 / 0 / 0 |
| estimate | 10 | 50 | 231.071 | 34.975 | 70.598 | 93.031 | 93.031 | 0.00 | 50 / 0 / 0 |
| estimate | 25 | 50 | 283.862 | 68.813 | 115.555 | 120.361 | 120.361 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 1 | 50 | 55.009 | 17.629 | 24.504 | 25.413 | 25.413 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 5 | 50 | 221.182 | 18.889 | 50.144 | 51.315 | 51.315 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 10 | 50 | 337.703 | 26.807 | 46.168 | 48.682 | 48.682 | 0.00 | 50 / 0 / 0 |
| tracking-hot | 25 | 50 | 341.799 | 55.288 | 105.541 | 120.075 | 120.075 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 1 | 50 | 61.252 | 15.381 | 26.266 | 29.859 | 29.859 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 5 | 50 | 300.709 | 14.681 | 24.871 | 27.015 | 27.015 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 10 | 50 | 504.697 | 18.683 | 23.867 | 27.069 | 27.069 | 0.00 | 50 / 0 / 0 |
| tracking-cold | 25 | 50 | 505.898 | 39.395 | 55.216 | 61.153 | 61.153 | 0.00 | 50 / 0 / 0 |
| tracking-unknown | 1 | 50 | 80.269 | 12.265 | 14.110 | 14.834 | 14.834 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 5 | 50 | 411.632 | 12.006 | 14.553 | 16.379 | 16.379 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 10 | 50 | 407.857 | 19.642 | 43.265 | 49.568 | 49.568 | 0.00 | 0 / 50 / 0 |
| tracking-unknown | 25 | 50 | 480.912 | 38.058 | 70.855 | 74.482 | 74.482 | 0.00 | 0 / 50 / 0 |
| mine-first | 1 | 50 | 44.672 | 21.251 | 28.865 | 43.043 | 43.043 | 0.00 | 50 / 0 / 0 |
| mine-first | 5 | 50 | 225.568 | 19.953 | 26.420 | 26.715 | 26.715 | 0.00 | 50 / 0 / 0 |
| mine-first | 10 | 50 | 295.369 | 31.621 | 42.146 | 52.626 | 52.626 | 0.00 | 50 / 0 / 0 |
| mine-first | 25 | 50 | 317.845 | 61.927 | 92.674 | 100.361 | 100.361 | 0.00 | 50 / 0 / 0 |
| mine-deep | 1 | 50 | 51.176 | 18.682 | 21.945 | 31.370 | 31.370 | 0.00 | 50 / 0 / 0 |
| mine-deep | 5 | 50 | 247.885 | 18.482 | 24.742 | 25.390 | 25.390 | 0.00 | 50 / 0 / 0 |
| mine-deep | 10 | 50 | 350.593 | 26.511 | 32.009 | 33.350 | 33.350 | 0.00 | 50 / 0 / 0 |
| mine-deep | 25 | 50 | 360.574 | 52.076 | 82.404 | 98.668 | 98.668 | 0.00 | 50 / 0 / 0 |
| workload | 1 | 50 | 10.484 | 93.740 | 137.035 | 146.795 | 146.795 | 0.00 | 50 / 0 / 0 |
| workload | 5 | 50 | 25.878 | 191.750 | 239.617 | 241.690 | 241.690 | 0.00 | 50 / 0 / 0 |
| workload | 10 | 50 | 20.498 | 424.586 | 691.791 | 733.349 | 733.349 | 0.00 | 50 / 0 / 0 |
| workload | 25 | 50 | 29.253 | 736.551 | 1051.589 | 1082.212 | 1082.212 | 0.00 | 50 / 0 / 0 |
| mixed-available | 1 | 50 | 29.670 | 13.301 | 100.809 | 131.509 | 131.509 | 0.00 | 50 / 0 / 0 |
| mixed-available | 5 | 50 | 91.044 | 12.780 | 179.528 | 202.067 | 202.067 | 0.00 | 50 / 0 / 0 |
| mixed-available | 10 | 50 | 71.001 | 22.654 | 405.502 | 427.508 | 427.508 | 0.00 | 50 / 0 / 0 |
| mixed-available | 25 | 50 | 67.538 | 64.931 | 645.342 | 654.136 | 654.136 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 1 | 50 | 89.141 | 10.342 | 15.709 | 17.584 | 17.584 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 5 | 50 | 668.798 | 7.058 | 8.290 | 8.904 | 8.904 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 10 | 50 | 1109.936 | 7.579 | 9.799 | 10.109 | 10.109 | 0.00 | 50 / 0 / 0 |
| failing-webhook-tracking | 25 | 50 | 1104.747 | 18.185 | 32.437 | 37.850 | 37.850 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 1 | 50 | 50.924 | 17.241 | 31.671 | 35.983 | 35.983 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 5 | 50 | 258.032 | 17.577 | 30.304 | 31.905 | 31.905 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 10 | 50 | 420.422 | 19.599 | 25.483 | 116.842 | 116.842 | 0.00 | 50 / 0 / 0 |
| failing-webhook-booking | 25 | 50 | 416.477 | 46.531 | 86.905 | 91.330 | 91.330 | 0.00 | 50 / 0 / 0 |
| slow-upstream-504 | 1 | 50 | 4.553 | 219.606 | 229.871 | 250.227 | 250.227 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 5 | 50 | 23.087 | 214.271 | 221.861 | 223.283 | 223.283 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 10 | 50 | 44.626 | 218.270 | 227.368 | 232.575 | 232.575 | 0.00 | 0 / 0 / 50 |
| slow-upstream-504 | 25 | 50 | 100.542 | 226.660 | 254.239 | 268.016 | 268.016 | 0.00 | 0 / 0 / 50 |
| other-route-responsive | 1 | 50 | 56.685 | 16.810 | 25.515 | 28.057 | 28.057 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 5 | 50 | 280.420 | 17.773 | 22.869 | 24.277 | 24.277 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 10 | 50 | 685.835 | 12.660 | 17.979 | 18.524 | 18.524 | 0.00 | 50 / 0 / 0 |
| other-route-responsive | 25 | 50 | 744.228 | 27.665 | 44.514 | 55.281 | 55.281 | 0.00 | 50 / 0 / 0 |

### Reference run 2: whole-list growth

[Owning-database plans](performance-smoke-evidence/run-2/performance-growth-plans.txt); [raw growth samples](performance-smoke-evidence/run-2/performance-growth.csv).

| Endpoint | Seed scope | Rows | Bytes | HTTP ms | DB ms | DB calls |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/admin/users | 100 | 102 | 17509 | 63.959 | 0.286 | 1 |
| /api/v1/admin/merchants | 100 | 101 | 30243 | 69.305 | 0.467 | 1 |
| /api/v1/admin/couriers | 100 | 101 | 30685 | 60.678 | 0.482 | 1 |
| /api/v1/dispatch/workload | 100 | 101 | 21141 | 144.827 | 0.281 | 2 |
| /api/v1/courier/deliveries | 100 | 100 | 18401 | 64.399 | 0.349 | 4 |
| /api/v1/merchant/orders | 100 | 50 | 26683 | 888.872 | 0.395 | 3 |
| /api/v1/zones | 100 | 105 | 13643 | 35.859 | 0.258 | 1 |
| /api/v1/tracking/PERF-HISTORY | 100 | 100 | 40841 | 332.533 | 0.366 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 100 | 100 | 22701 | 41.639 | 0.278 | 2 |
| /api/v1/admin/users | 1000 | 1002 | 173210 | 37.932 | 1.572 | 1 |
| /api/v1/admin/merchants | 1000 | 1001 | 299343 | 39.413 | 1.535 | 1 |
| /api/v1/admin/couriers | 1000 | 1001 | 305186 | 41.004 | 1.900 | 1 |
| /api/v1/dispatch/workload | 1000 | 1001 | 210144 | 84.821 | 1.976 | 2 |
| /api/v1/courier/deliveries | 1000 | 1000 | 184001 | 102.082 | 3.445 | 4 |
| /api/v1/merchant/orders | 1000 | 50 | 26801 | 77.847 | 0.361 | 2 |
| /api/v1/zones | 1000 | 1005 | 130645 | 39.580 | 2.206 | 1 |
| /api/v1/tracking/PERF-HISTORY | 1000 | 1000 | 405341 | 121.338 | 2.428 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 1000 | 1000 | 227001 | 51.026 | 1.896 | 2 |
| /api/v1/admin/users | 5000 | 5002 | 869210 | 56.665 | 8.174 | 1 |
| /api/v1/admin/merchants | 5000 | 5001 | 1495343 | 72.887 | 7.500 | 1 |
| /api/v1/admin/couriers | 5000 | 5001 | 1529186 | 65.917 | 9.045 | 1 |
| /api/v1/dispatch/workload | 5000 | 5001 | 1054144 | 152.980 | 16.066 | 2 |
| /api/v1/courier/deliveries | 5000 | 5000 | 920001 | 71.862 | 8.617 | 4 |
| /api/v1/merchant/orders | 5000 | 50 | 26901 | 65.818 | 0.300 | 2 |
| /api/v1/zones | 5000 | 5005 | 658645 | 65.009 | 13.352 | 1 |
| /api/v1/tracking/PERF-HISTORY | 5000 | 5000 | 2025341 | 143.709 | 7.141 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 5000 | 5000 | 1135001 | 105.655 | 6.436 | 2 |
| /api/v1/admin/users | 10000 | 10002 | 1739211 | 80.489 | 24.495 | 1 |
| /api/v1/admin/merchants | 10000 | 10001 | 2990343 | 57.226 | 13.843 | 1 |
| /api/v1/admin/couriers | 10000 | 10001 | 3059187 | 69.823 | 12.398 | 1 |
| /api/v1/dispatch/workload | 10000 | 10001 | 2109147 | 127.998 | 26.784 | 2 |
| /api/v1/courier/deliveries | 10000 | 10000 | 1840001 | 97.222 | 17.230 | 4 |
| /api/v1/merchant/orders | 10000 | 50 | 26901 | 44.581 | 0.385 | 2 |
| /api/v1/zones | 10000 | 10005 | 1318647 | 87.718 | 34.138 | 1 |
| /api/v1/tracking/PERF-HISTORY | 10000 | 10000 | 4050341 | 101.479 | 8.634 | 2 |
| /api/v1/tracking/PERF-HISTORY/events | 10000 | 10000 | 2270001 | 83.937 | 9.654 | 2 |

### Run-to-run variance at 25 clients

| Scenario | Run 1 req/s | Run 2 req/s | Run 1 p95 ms | Run 2 p95 ms | p95 change % |
|---|---:|---:|---:|---:|---:|
| estimate | 312.640 | 283.862 | 128.869 | 115.555 | -10.3 |
| tracking-hot | 447.557 | 341.799 | 75.307 | 105.541 | +40.1 |
| tracking-cold | 485.331 | 505.898 | 81.359 | 55.216 | -32.1 |
| tracking-unknown | 599.640 | 480.912 | 54.129 | 70.855 | +30.9 |
| mine-first | 286.652 | 317.845 | 106.634 | 92.674 | -13.1 |
| mine-deep | 307.997 | 360.574 | 103.429 | 82.404 | -20.3 |
| workload | 23.669 | 29.253 | 1718.516 | 1051.589 | -38.8 |
| mixed-available | 80.165 | 67.538 | 512.496 | 645.342 | +25.9 |
| failing-webhook-tracking | 933.778 | 1104.747 | 34.809 | 32.437 | -6.8 |
| failing-webhook-booking | 272.899 | 416.477 | 107.455 | 86.905 | -19.1 |
| slow-upstream-504 | 100.926 | 100.542 | 246.179 | 254.239 | +3.3 |
| other-route-responsive | 554.172 | 744.228 | 61.184 | 44.514 | -27.2 |
## Supplement: actively concurrent upstream isolation

Before the final source freeze, `-Dperf.scenario=failure` passed (one JUnit test, zero skips; 2m18s Maven). It adds exactly 50 background estimate requests with five clients to the slow upstream while measuring the real tracking route. All 50 control requests returned the expected 504. It measured 800 requests across the four failure/isolation scenarios, zero unexpected responses/integrity failures. Tracking p95 at 25 clients while the slow route was active was 64.263 ms. The response-timeout fixture remains 200 ms, with a 2-second stub header delay. These measurements are separate and are not substituted into the final full reference tables, which now also execute this check. [Raw results](performance-smoke-evidence/failure-supplement/performance-results.csv) and [execution](performance-smoke-evidence/failure-supplement/execution.txt).

Command executed from `backend/`:

```sh
mvn --batch-mode -Pperf -pl merchant-order-webhook-e2e -am \
  -Dtest=PerformanceSmokeIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dperf.scenario=failure verify
```

The separate `-Dperf.scenario=lists` validation passed (one JUnit test, zero skips; 1m46s Maven). It checks 100/1k/5k/10k owner scopes against contrasting foreign merchant/courier rows and repeats each whole-list query without intervening writes. Those checks also execute in both final full references. [List-only execution](performance-smoke-evidence/lists-supplement/execution.txt).

In final run 2, the booking ramp stopped after 5 clients: p95 increased from 33.221 to 118.025 ms, exceeding the unchanged factor-3 guard despite staying below the absolute 1000 ms ceiling. **Booking at 10 and 25 clients was NOT run in that reference**; the comparison table therefore has no booking row at 25. This explains 2500 versus 2600 measured requests. These are unexecuted load levels, not skipped JUnit tests. Both full profiles failed their sanity assertions; neither is presented as a passing benchmark.
