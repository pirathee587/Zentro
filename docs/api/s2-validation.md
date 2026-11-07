# S2 validation review — #124

Status: Todo / UNVERIFIED. Assisting @Deadsecnote1; reviewer @kirusigan03. Base: `1727c47291373adf1e3f89c453d185dc83b859e5`. No frontend changes, authentication changes, gateway changes, fee formula changes, or cross-service database access.

## Endpoint comparison and shared validation rules

B = POST `/api/v1/parcels`; M = POST `/api/v1/merchant/orders`; E = POST `/api/v1/parcels/estimate` and `/estimate-fee`.

| Field | Rule | Min | Max | Applies | Error key |
|---|---|---|---|---|---|
| sender/recipient | Required objects, nested validation | — | — | B/M | sender/recipient |
| contact address | Trim; Unicode code points; reject controls, FORMAT, unpaired surrogates before trim | 8 | 255 | B/M | sender.address / recipient.address |
| contact name | Required, trimmed code-point length | 1 | 100 | B/M | sender.name / recipient.name |
| contact phone | Remove ASCII regex whitespace and dashes; `^(\+94|0)\d{9}$`; same normalization as persistence | — | — | B/M | sender.phone / recipient.phone |
| city | Optional, trimmed code points | 0 | 100 | B/M/E | sender.city / recipient.city / pickupCity / deliveryCity |
| actual weight | Decimal JSON number; at most four fractional digits; shared ParcelWeight annotation | 0.1 | 20 kg | B/M/E | parcel.weightKg / weightKg |
| parcel category | Required B/M; optional E; code-point length | 1 B/M, 0 E | 40 B/M, 100 E | B/M/E | parcel.category / parcelCategory |
| parcel description | Optional code points | 0 | 255 | B/M | parcel.description |
| notes | Optional code points | 0 | 500 | B/M | notes |
| size / destination | Existing enums; size is a fallback, not a weight tier; actual weight drives fee | — | — | B/M/E | parcel.size / parcelSize / destinationType |
| dimensions | Optional, all three required together, decimal JSON numbers, four fractional digits | 0.01 | 500 cm | E | lengthCm / widthCm / heightCm |
| volumetric weight | Existing L×W×H/5000; exact ceiling check before pricing rounding | positive | 20 kg | E | all three dimension fields |
| distance | Optional decimal JSON number, four fractional digits; zero preserved | 0 | 2000 km | E | distanceKm |
| zones | Optional paired active codes; SAME_CITY cannot span distinct zones | — | 64 code points | E | originZone / destinationZone |
| currency | Optional supported pricing currency | — | 10 code points | E | currency |
| externalOrderRef | Required, trimmed code points; existing idempotency unchanged | 1 | 100 | M | externalOrderRef |

Before: E actual weight 0.01–100 and volumetric maximum 100; B/M 0.1–20. After: 0.1–20 actual weight everywhere; volumetric maximum 20. This is an explicitly approved, deliberate contract change. Other tightening: reject exponent/string numeric representations and >4 fractional digits; reject unsafe addresses and count Unicode code points. Four fractional digits preserve existing three-decimal idempotency regressions; storage still rounds to two decimals. No separate secret/configuration storage was introduced.

Before: address `@Size` counted UTF-16 and validated untrimmed length; multiline regex permitted control and format characters. After: shared AddressValidator inspects original content and counts trimmed code points. Merchant DTO continues to reuse booking Contact/ParcelDetails. Text fields use shared CodePointSize; phone uses a non-recursive validator. Numeric/text deserializers reject coercion without exposing values.

## Error comparison (preserved contracts)

| Endpoint | Constraint validation | Field keys | Other properties | Malformed/type/enum |
|---|---|---|---|---|
| B | 422, Validation failed, Some booking details are missing or invalid. | request paths | fieldErrors | 400, Malformed request, generic safe detail |
| M | 422, Validation failed, Some order details are missing or invalid. | request paths | fieldErrors | 400, Malformed request, generic safe detail |
| E aliases | 400, Bad Request, Validation failed for request. (domain errors use a field message) | flat | fieldErrors + invalidFields | 400; safe field message when field identified, otherwise generic detail |

All use problem+json and framework instance paths. Constraint error fields and duplicate-message choice are now sorted; domain error maps are sorted. Zone/currency errors no longer interpolate submitted values. Missing required fields remain constraint errors; optional nulls remain accepted. No 400/422 unification or response schema change was made. Unsupported content types retain the framework 415 behavior.

## Findings

| ID | Finding | Status / owner / next action |
|---|---|---|
| F1 | Quote/booking actual-weight and volumetric ceiling mismatch | Fixed with explicit user approval; shared range and docs |
| F2 | SMALL 15 kg accepted | Preserve by decision; test pins actual-weight charge; no tier-based underpricing found |
| F3 | Unsafe controls/format characters and UTF-16 address bounds | Fixed; AddressValidator |
| F4 | Numeric coercion, exponent syntax, unbounded scale | Fixed for actual weight, estimate dimensions/distance; strict decimal decoder |
| F5 | Zone/currency input echoed in errors | Fixed; static messages |
| D1 | Identical pickup/drop-off addresses accepted | Needs business decision; owner to be assigned; decide whether legitimate same-location delivery is permitted |
| D2 | SAME_CITY with different city strings accepted (unless zones conflict) | Needs business decision; owner to be assigned; city spelling/aliases make string inequality insufficient |
| R1 | Dimension quotes cannot transmit dimensions into bookings | Follow-up, owner to be assigned; define booking dimension contract before extending DTO/persistence |
| R2 | Requested exhaustive every-field HTTP and persistence boundary matrix | Remaining evidence gap; Draft until completed, owner @Deadsecnote1 |

## Tests and reproduction

Reuse #68 booking validation/lifecycle/security/persistence/outbox/idempotency coverage. New S2ValidationConsistencyTest feeds numeric failures to all four routes and unsafe addresses to both contact endpoints, preserving each status/key. S2ConstraintBoundaryTest covers code-point limits (including emoji, RTL, combining marks, 100k text), weight bounds/scale, dimension/distance limits, and SMALL 15 kg pricing. BookingPersistenceIntegrationTest checks invalid weights create no parcel/idempotency/outbox rows and exact 0.1/20 persistence; MerchantOrderIntegrationTest adds invalid weight/address no-write checks and exact 0.1/20 persistence. Existing role-negative tests are retained.

Existing tests changed: ParcelPricingAuthorizationAndEndpointTests boundaryValidWeightsSucceed now uses 0.1 and 20 (formerly 0.01/100); volumetricWeightExactlyAtCeiling100KgAllowed becomes ceiling20KgAllowed with 500×20×10 cm and LKR 2830; over-ceiling case now expects lengthCm rather than weightKg. Engine-only tests above HTTP bounds remain arithmetic tests, not endpoint acceptance evidence.

```bash
cd backend
mvn --batch-mode -pl parcel-pricing-service -am test
mvn --batch-mode test package
```

The full requested every-field wrong-type/missing/null/error-order HTTP matrix and remaining per-field persistence combinations are not yet exhaustive. This limitation is explicit rather than treating annotation-only tests as HTTP proof. Real gateway end-to-end, frontend runtime and production data were not verified.

## Frontend read-only review

Customer sendParcelForm.ts and dispatcher pricingValidation.ts already constrain quote weight to 0.1–20. No merchant estimate caller or dispatcher >20 workflow found. Dispatcher pricingApi.test.ts contains an old 100 kg message fixture; mock-only parsing still works, but fixture should be refreshed. Customer permits decimals without the new four-digit bound and does not reject unsafe address controls/format characters; follow-up must align these client checks and code-point length handling. Dispatcher already limits quote weight to two decimal places. Pricing-rule includedWeightKg maximum 100 is configuration, not parcel quote input, and is unchanged. No frontend tooling was run.

| Evidence | Value |
|---|---|
| Merged commit SHA | to be recorded after merge |

Follow-ups: [#300 frontend validation sync](https://github.com/pirathee587/Zentro/issues/300), owner @pirathee587, align client rules and refresh the old message fixture; [#301 business policy and dimension-booking contract](https://github.com/pirathee587/Zentro/issues/301), owner to be assigned, assign policy ownership and decide the unchanged questions before implementing new restrictions.

Additional controller inventory: PUT `/api/v1/admin/pricing-rules` accepts PricingRuleUpdateRequest, with six required nonnegative BigDecimal pricing coefficients (including includedWeightKg) and an unconstrained currency string. These are dispatcher configuration coefficients, not submitted parcel weight/dimensions; this task does not change them. The 100 kg configuration limit found in the dispatcher frontend is a client rule, not a backend annotation. GET zone/pricing/order reads and PATCH parcel status do not accept parcel address/weight/dimensions.

## Actual environment and verification results

Commands: `java -version`, `mvn -v`, `docker --version`, `uname -sr`.

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

`cd backend && mvn --batch-mode -pl parcel-pricing-service -am test`: BUILD SUCCESS, 02:19 min; S2 624 tests, 0 failures, 0 errors, 0 skipped; shared security starter 7 tests, 0 failures/errors/skips. Subsequent S2 evidence below supersedes this intermediate count.

`cd backend && mvn --batch-mode test package`: BUILD SUCCESS, 07:58 min. Actual per-module Surefire XML totals (not historical opt-in E2E reports):

| Module | Tests reported | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Root aggregator | 0 | 0 | 0 | 0 |
| Security starter | 7 | 0 | 0 | 0 |
| API gateway | 183 | 0 | 0 | 1 |
| S1 identity/partner | 336 | 0 | 0 | 0 |
| S2 parcel/pricing | 627 | 0 | 0 | 0 |
| S3 dispatch/courier | 145 | 0 | 0 | 0 |
| S4 tracking/reporting | 48 | 0 | 0 | 0 |
| S5 notification/integration | 380 | 0 | 0 | 0 |
| Total | 1726 | 0 | 0 | 1 |

The single gateway test `GatewayKeycloakIntegrationTests.upstreamAndGatewayFailuresUseTheSameProblemJsonShape` was **NOT run**: existing `@Disabled` reason is “Known shape mismatch: upstream 5xx sanitizer omits `instance`; see docs/testing/gateway-integration-tests.md#findings”. Its containing Keycloak/Docker suite did run. No Docker-unavailable skips occurred in S2; PostgreSQL/Kafka integration tests ran.

`merchant-order-webhook-e2e` was packaged but its two opt-in tests were **NOT run**: its POM sets `skipTests=true` unless the `e2e` / `runE2E` profile is enabled. These two are not counted as passing or as Surefire-reported skips. No real validation-through-gateway E2E was performed for #124.

Initial development runs failed old-range expectations and a missing IdentityMerchantClient mock in the new web slice; those failures were corrected. An early superseded S2 run was interrupted. Only completed runs above and the final rerun below are acceptance evidence. JSON syntax was separately checked with `jq empty docs/api/openapi/parcel.openapi.json`; `git diff --check` passed. No Python or Node tooling was used.

Focused reproduction (existing security/role suites are included by the full module command):

```bash
cd backend
mvn --batch-mode -pl parcel-pricing-service -am test -Dtest=S2ValidationConsistencyTest,S2ConstraintBoundaryTest,BookingPersistenceIntegrationTest,MerchantOrderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Final changed-module verification: `cd backend && mvn --batch-mode -pl parcel-pricing-service -am test package` completed **BUILD SUCCESS**, 02:13 min, with **628 S2 tests, 0 failures, 0 errors, 0 skipped**, plus 7 shared security starter tests with 0 failures/errors/skips. This run includes the latest HTTP boundary matrix and merchant strict-text annotations; PostgreSQL/Kafka tests ran. It supersedes earlier S2 counts. The preceding full-backend run reported S2 627 before the final HTTP matrix addition; the final changed module was tested and packaged again afterward.
