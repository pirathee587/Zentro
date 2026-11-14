# Reports API acceptance — Refs #358

## Approval and scope

The #358 owner approved D1–D6 in the working session on 2026-11-14 and authorized
local commits 2–5. The public [contract proposal](https://github.com/pirathee587/Zentro/issues/358#issuecomment-6876729691)
is awaiting approval from @hephzibah-peries and @pavi3alnx. It is not an approval
comment. **Do not push or create a PR until both approvals are present.** Re-read
#358, quote the approvals, and stop for owner review if either reviewer requests
contract changes. Record the approval links here when available.

Scope: G05 and the seeded-data/filter part of G32. CSV export is explicitly
deferred; there is no export endpoint or status filter. Live
`ReportsAcceptanceIT` remains a separate follow-up under main 10's E2E support
rules. Its #357/#352 dependencies are now closed (#357 merged through #377;
#352 through #362), but real-service readiness still needs verification.
No live scenario class is added by this change.

## Contract D1–D6

1. **Endpoints:** `GET /api/v1/reports/deliveries/daily` returns
   `{from,to,timezone,days:[{day,created,delivered,failed,active,successRate}]}`.
   `GET /api/v1/reports/deliveries/summary` returns
   `{from,to,timezone,created,delivered,failed,successRate}`. `timezone` is always
   `Asia/Colombo`. Daily rows are ascending, with every requested day included.
   Preserve rows supplied by the existing view, including intervening days with
   nonzero active parcels. Days absent from the view have zero counts and null rate.
2. **Boundaries:** required `from` and `to`, formatted `YYYY-MM-DD`, are inclusive
   Asia/Colombo calendar days. `tracking.reporting_day(event.occurred_at)` uses
   business time, not ingestion time. `18:29:59Z` is `23:59:59` in Colombo;
   `18:30:00Z` starts the next calendar day.
3. **Bounds:** `from <= to`, at most 92 inclusive days, and `to` cannot exceed
   today's Asia/Colombo date. No paging; at most 92 daily rows.
4. **Errors/security:** missing or invalid dates, reversed ranges, 93+ days and
   future `to` return 400 with `fieldErrors.from` or `fieldErrors.to`.
   The existing ProblemDetail shape is preserved: `type`, `title`, `status`,
   `detail`, `instance`, plus `code: INVALID_ARGUMENT` and `fieldErrors`.
   Missing/invalid bearer tokens return 401; CUSTOMER, MERCHANT, COURIER,
   SERVICE-only and roleless tokens return 403. DISPATCHER is enforced at the
   gateway and by the report controller using S4's existing method security.
   An empty range returns 200 with zero counts and null rate. Scoped advice
   handles report validation; the global tracking handler is unchanged.
5. **Filters/export:** inclusive date filters only. Created/delivered/failed are
   metric columns, not status filters. CSV export is deferred explicitly.
6. **Timeout:** propose a 30-second reports budget. The current whole-S4 route
   overrides the global 30-second default with 3,600,000 ms. The architecture
   decision remains pending approval; any gateway change belongs to @pavi3alnx
   under #351/main 02 and is outside this PR.

## Metric definitions and independent reconciliation

The read-only query repository uses S4's existing `tracking.delivery_daily_metrics`
view. There is no cross-service SQL or projection-writer change.

- `created`: one creation per parcel, on its earliest CREATED business-time day.
- `delivered` / `failed`: one chosen terminal contribution per parcel, on its
  earliest terminal business-time day; event UUID orders equal-time terminals.
- `active`: known-created parcels whose creation day is on/before the row's day
  and whose terminal day is absent or later than that day. This is a parcel
  population at day-end, not a sum of daily created minus daily terminal counts.
- Daily `successRate`: `delivered::numeric / NULLIF(delivered + failed, 0)`;
  null when the denominator is zero. It is a ratio in [0,1], not a percentage.
- Summary totals sum the daily created/delivered/failed columns. Summary rate
  divides total delivered by total delivered plus failed, using DECIMAL128
  precision; it does not average daily rates. Active is not summed or included
  in the summary.

`ExpectedDeliveryFacts` is a plain ledger of synthetic parcel events. Its Java
reference calculator reads that ledger only, deduplicates event IDs, groups by
parcel, selects creation/terminal facts and converts Instants using Java's
Asia/Colombo ZoneId. It never reads production projections or calls production
calculation code. Explicit midnight expectations anchor the calculator itself.
After each ingested fact, the tests compare every stored daily count to this
reference. The full-history SQL recount reuses the existing
`src/test/resources/sql/delivery-recount.sql`, compares to the incremental view,
and is independently anchored by the Java ledger comparison.

Cases cover normal flow, duplicate IDs, reversed arrival order (terminal before
creation/milestones), both terminal statuses, an earlier terminal arriving late,
late milestones, the two seconds around Colombo midnight, zero-filled ranges,
date-filter subsets, a 93-day rejection, summary reconciliation and null rates.
No projection-writer defect was found; no test is disabled.

## Automated evidence

Run Maven from uppercase `D:\Sem_5\EAD\Project\Zentro\backend`:

```bash
cd 'D:/Sem_5/EAD/Project/Zentro/backend'
mvn --batch-mode --no-transfer-progress -pl tracking-reporting-service -am verify
```

Executed on 2026-11-14 using Java 21, Maven 3.9.16 and real local
`postgres:16-alpine` Testcontainers databases:

| Check | Exit | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| After commit 1 | 0 | 262 | 0 | 0 | 0 |
| After commit 2 | 0 | 267 | 0 | 0 | 0 |
| After commit 3 | 0 | 267 | 0 | 0 | 0 |
| Focused report tests before commit 4 | 0 | 47 | 0 | 0 | 0 |
| After commit 4 | 0 | 303 | 0 | 0 | 0 |

Full counts include 62 security-starter tests. S4 counts are respectively
200, 205, 205 and 241. The focused command was:

```bash
mvn --batch-mode --no-transfer-progress -pl tracking-reporting-service -am \
  -Dtest=ReportApiAcceptanceTest,ExpectedFactProjectionIntegrationTest,ReportServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Mocked seams: `JwtDecoder` in the reporting Spring tests (synthetic decoded
realm-role claims and invalid-token exceptions); the query repository and fixed
clock in service unit tests. MockMvc invokes the actual S4 filter chain,
controller, advice, service and JDBC repository. Database tests call the existing
consumer inside a real transaction; they bypass Kafka transport. PostgreSQL,
Flyway and projection writes are real. Shared setup is confined to
`ReportingPostgresTestSupport`; missing Docker fails these reporting tests.

The existing gateway tests route the reports prefix to S4 and include a
Dispatcher-only role matrix in `GatewayKeycloakIntegrationTests`.
`GatewayBehaviorTests` checks prefix routing with query preservation. These use
a representative `/api/v1/reports/summary` path, not the two new concrete report
GET paths. No gateway test was edited or newly executed for this change.
Suggested follow-up for @pavi3alnx: add both concrete GET paths to the route
matrix and update the reserved-controller inventory after contract approval.

## OpenAPI evidence and baseline blocker

Author report content only in the tracking entries of `docs/api/build_openapi.py`.
The generator requires `--generate`; invocation without a flag prints usage.

```bash
python3 docs/api/build_openapi.py --generate
python3 docs/api/build_openapi.py --check
```

Generation succeeded (exit 0). The complete `--check` fails (exit 1) on existing
S2 mappings missing from the parcel spec:

```text
GET /api/v1/parcels/{id}/receipt
GET /api/v1/parcels/{id}/receipt/pdf
```

The same omissions were verified against the committed generator inventory;
they predate this report change. Owner: @Jathuja, #353/main 04. The #358 owner
authorized continuing and documenting this blocker without editing S2 content.
The tracking controller/spec inventory matches exactly, with no missing or
extra paths. Generator-only line-ending changes to other service artifacts
were restored. Do not report the repository-wide check as passing.

## Manual local verification

Executed on 2026-11-14 against the current packaged gateway and S4, with real
Keycloak authorization-code/PKCE logins and a disposable PostgreSQL database.
The successful manual helper run exited 0. These checks do not establish live
Kafka acceptance.

| Manual check | Result |
|---|---|
| Keycloak readiness and realm discovery | HTTP 200 with localhost proxy bypass |
| Dispatcher and Courier authorization-code logins | Passed; no auth settings changed |
| Flyway | Latest successful migration V3 |
| Dispatcher daily and summary through gateway | HTTP 200; SQL counts and summary ratio match |
| Absent middle day | Zero counts; null success rate |
| Missing token, both endpoints | HTTP 401 |
| Invalid token, both endpoints | HTTP 401 |
| Courier token, both endpoints | HTTP 403 |
| Reversed range, 93 days, malformed date, both endpoints | HTTP 400; correct field named |
| Customer token through gateway | Not run: no Customer credentials in seed file; automated S4 test passes |
| SQL cross-check | Three expected metrics-view days, including the zero-filled middle day |
| Cleanup | Both launched Java processes stopped; `zd358-pg` removed |

All 14 HTTP assertions passed. Response bodies/statuses, seed SQL and query
results are in `%TEMP%/zentro-358-manual-evidence/`, outside the repository.
Tokens were held in memory, not saved. Initial helper failures (local secure
cookie handling and a shared environment port collision) were corrected before
the successful run; they were not report code defects. The gateway packaging
command `mvn --batch-mode --no-transfer-progress -pl api-gateway -am -DskipTests package`
exited 0; this packaging run deliberately did not execute gateway tests.

Outstanding manual acceptance: obtain a provisioned Customer's credentials and
verify its 403 response. Do not substitute a Courier result for Customer evidence.

For a repeat run, use Git Bash. Do not reuse a shared database. Keep response
evidence outside the repository, and do not save or print bearer tokens.

```bash
cd 'D:/Sem_5/EAD/Project/Zentro'
docker run -d --name zd358-pg -e POSTGRES_DB=zentro -e POSTGRES_USER=zentro \
  -e POSTGRES_PASSWORD=zentro -p 5433:5432 postgres:16-alpine
curl --noproxy '*' -f http://localhost:9000/health/ready
```

The committed web client disables password grants. Use its existing
authorization-code flow with PKCE: sign into the local web app as the Dispatcher
using credentials from the ignored `infra/keycloak/seed-credentials.local.txt`.
Obtain the access token from that local login's token response. Repeat for a
Customer test user if one has been provisioned; the current seed file contains
only Dispatcher/Courier accounts. Do not enable password grants to obtain tokens.
Read tokens into the shell without echoing:

```bash
read -rsp 'Dispatcher access token: ' DISPATCHER_TOKEN; printf '\n'
read -rsp 'Customer access token: ' CUSTOMER_TOKEN; printf '\n'
```

Start S4 and gateway in separate Git Bash terminals after packaging them from
current source. The empty config import avoids local environment-file loading:

```bash
DB_URL=jdbc:postgresql://localhost:5433/zentro DB_USER=zentro DB_PASSWORD=zentro \
  KAFKA_CONSUMER_ENABLED=false JWT_ISSUER_URI=http://localhost:8180/realms/zentro \
  java -jar backend/tracking-reporting-service/target/tracking-reporting-service-0.1.0-SNAPSHOT.jar \
  --spring.config.import=
# Wait for S4 health and confirm Flyway V3 before seeding.
docker exec zd358-pg psql -U zentro -d zentro -c \
  'SELECT version, success FROM tracking.flyway_schema_history ORDER BY installed_rank;'
JWT_ISSUER_URI=http://localhost:8180/realms/zentro S4_URI=http://localhost:8084 \
  java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar --spring.config.import=
```

Seed two daily rows across a three-day range with an absent middle day:

```bash
docker exec -i zd358-pg psql -v ON_ERROR_STOP=1 -U zentro -d zentro <<'SQL'
INSERT INTO tracking.delivery_daily(day, created, delivered, failed)
VALUES ('2026-11-01', 3, 1, 0), ('2026-11-03', 2, 1, 1);
SQL
```

Expected counts: daily `(3,1,0)`, `(0,0,0)`, `(2,1,1)` for
created/delivered/failed; summary `(5,2,1)` and rate `2/3`. The middle rate is null.
This daily-only seed has no projection rows, so active is zero throughout.
The automated ledger tests additionally verify active from actual projection facts.

```bash
REPORT_URL='http://localhost:8080/api/v1/reports/deliveries'
for endpoint in daily summary; do
  curl --noproxy '*' -sS -w '\nHTTP %{http_code}\n' \
    -H "Authorization: Bearer $DISPATCHER_TOKEN" \
    "$REPORT_URL/$endpoint?from=2026-11-01&to=2026-11-03"
done
curl --noproxy '*' -sS -w '\nHTTP %{http_code}\n' \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  "$REPORT_URL/daily?from=2026-11-01&to=2026-11-03"
curl --noproxy '*' -sS -w '\nHTTP %{http_code}\n' \
  "$REPORT_URL/daily?from=2026-11-01&to=2026-11-03"
for query in 'from=2026-11-03&to=2026-11-01' \
  'from=2026-01-01&to=2026-04-03' 'from=bad&to=2026-11-03'; do
  curl --noproxy '*' -sS -w '\nHTTP %{http_code}\n' \
    -H "Authorization: Bearer $DISPATCHER_TOKEN" "$REPORT_URL/daily?$query"
done
docker exec -it zd358-pg psql -U zentro -d zentro -c \
  'SELECT * FROM tracking.delivery_daily_metrics ORDER BY day;'
unset DISPATCHER_TOKEN CUSTOMER_TOKEN
# Stop only the S4 and gateway processes started for this check, then:
docker rm -f zd358-pg
git status --short
```

Save HTTP response bodies/statuses and SQL results outside the repository.
Never use verbose curl or save request headers containing tokens.

## Remaining gates

- Independent contract/route approvals in #358 before push or PR creation.
- S2 owner correction before repository-wide OpenAPI validation can pass.
- Deferred live `ReportsAcceptanceIT`; no claim of live Kafka acceptance.
- CI executes when a PR targets develop. This feature branch has not been pushed
  and no PR or branch CI run exists yet. Existing workflows build/test/package
  and scan dependencies; this task does not deploy anything.

Only reporting source/tests and report documentation/spec content are changed.
Gateway, tracking consumer, global handler, POMs, migrations, CI, shared E2E
fixtures and deployment configuration remain outside this change.
