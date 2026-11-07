# Merchant API key handling review

Review scope: S1 key lifecycle implementation, gateway and S2 merchant request authentication, S1/S2/S5 configuration and logging, migrations, relevant API docs, and the checked-in Keycloak realm export. No live Keycloak, production database, secret store, deployment configuration, or external logs were available. GitHub API access failed in this environment; issue #126 and #32 could not be independently read. Issue #23 and PR #114 were reviewed from their public pages.

## Lifecycle and authentication

S1 exposes `GET`, `POST`, and `DELETE /api/v1/merchants/api-key`. All require a `MERCHANT` JWT role. S1 resolves the owner from the JWT `sub` via its own `app_user` row and permits operations only when that account is `ACTIVE` and its merchant application is `APPROVED`. A merchant has one row, enforced by unique `app_user_id`; POST replaces the existing key atomically and invalidates its predecessor. DELETE marks it revoked; repeating DELETE leaves the timestamp unchanged. A missing key returns a null body with the current controller behavior.

S1 generates `zn_live_` plus 32 `SecureRandom` bytes encoded as unpadded Base64 URL-safe text: 256 bits of entropy, 43 encoded characters, 51 characters total. The first 8 encoded characters are included in the stored/displayed prefix. S1 stores SHA-256 as 64 lowercase hex characters, not the plaintext. Since the generated secret has 256 random bits, a slow password hash is unnecessary. The prefix has 48 random bits and is unique in the database. No pepper or key-related environment variable is used.

The full key is only returned by POST in the application code. GET returns prefix and metadata. The response record previously had the default record `toString()`, which included the full secret; that is now overridden to redact it. POST now includes `Cache-Control: no-store` and `Pragma: no-cache`.

**Issued keys are not currently accepted as authentication credentials.** No verifier, API-key request header, or key lookup path exists in S1, S2, or the gateway. S2 merchant orders use Keycloak bearer JWTs, derive the merchant from JWT `sub`, and call S1 to check approval and active status. Gateway and S2 enforce JWT roles independently. The checked-in realm export contains the `zentro-web` public client and no merchant API-key client. Do not describe the issued key as usable for merchant API requests until a separately reviewed contract and validating service are agreed.

The API-key lifecycle has no rotation overlap: POST replaces the one row in one database operation, so after success only the new key is valid in storage. There is no documented grace window. The merchant can also DELETE then POST, leaving a period with no active key. Since no key verifier exists, these lifecycle states do not currently affect merchant request authentication.

## Storage decision compared with PR #114

S5 webhook signing secrets use reversible AES-256-GCM encryption with `WEBHOOK_SECRET_KEY`, because S5 must recover the signing secret to calculate each HMAC. Merchant API keys are presented for identity verification only; when a verifier is implemented, the existing high-entropy key is appropriate for one-way SHA-256 lookup/comparison. Reusing S5 encryption would unnecessarily make recoverable credentials. No new secret store or competing crypto component is introduced here.

The API key hash currently uses plain SHA-256 without a pepper. This is appropriate for the existing 256-bit uniformly random values: offline guessing is infeasible, and there is no low-entropy human password to slow down. The code has no comparison path today, so constant-time comparison is not applicable yet.

## Logging, audits, limits, and repository hygiene

The gateway request logger records method, path, status, duration, and correlation ID; it does not record headers or body. S1 API-key lifecycle code has no logging. The search found no API-key values in logs, exception text, event/Kafka payloads, metrics, or audit records. No API-key audit events exist today, so creation/revocation/failure auditing remains a follow-up. S1/S2 errors do not receive an API key to echo because no validation path exists. S5 webhook delivery logging uses its separate signing-secret handling.

The gateway has an in-memory per-IP rate limit (default 300 requests/minute; 30 for tracking). It is not per merchant key and does not protect direct S1/S2 access. It is not a brute-force defense for merchant API keys. No S1-specific throttling exists.

The migration has no entity/ORM class; S1 uses JDBC. No API-key material is present in the inspected realm export or checked-in runtime config. Test fixtures use visibly fake `zn_live_...` values. A full history secret scan and live deployment scan were not available/validated; no credential values are reproduced here.

## Findings

| ID | Severity | Status | Evidence / action |
|---|---|---|---|
| K1 | Medium | fixed | POST response had no cache prevention. Added `no-store` and `no-cache`; response body unchanged. |
| K2 | Medium | fixed | `IssuedMerchantApiKey` record `toString()` included the raw secret. Override redacts it; regression assertion added. |
| K3 | High | follow-up | Issued key is not accepted by any request path; S2 uses JWT. No header contract, verifier, consistent failure shape, or constant-time comparison exists. Keep JWT behavior; agree a separate contract before enabling API-key auth. |
| K4 | Low | follow-up | Lifecycle events are not audit-logged. No secret-bearing audit path exists. Add safe create/revoke audit events if required by the owner. |
| K5 | Low | risk | Rate limiting is per client IP at the gateway, not per key; direct service traffic is not covered. |
| K6 | Low | needs decision | Rotation is immediate replacement; there is no overlap/grace period. Confirm whether the merchant workflow needs one before implementing it. |
| K7 | Medium | follow-up | No API-key endpoint documentation exists under `docs/api`. A historical, now-removed `docs/api/merchant-api-keys.md` described dispatcher key endpoints and a validation API that are absent from current code. S2's current merchant-order doc correctly describes JWT authentication. |
| K8 | Medium | needs verification | Historical commit `8512b698` contains long `zn_live_`-format values in API documentation examples. They appear in examples, not current files; their authenticity cannot be established from repository contents. Check whether they were ever live and rotate if so. No values are reproduced here. |

## Evidence checklist

| Area | Result | Evidence |
|---|---|---|
| Creation and ownership | OK | `MerchantApiKeyService.issueOrRotate`: SecureRandom, 32 bytes, URL-safe Base64; `approvedMerchant` checks account and application; owner comes from `UserProfileService.current(jwt)`. |
| Existing key policy | OK | Unique `app_user_id` and `ON CONFLICT` replace one row; no simultaneous valid rows per merchant. |
| Storage | OK | Migration stores `key_prefix` and `key_hash CHAR(64)`; service stores SHA-256 only. Unique prefix and owner constraints. |
| One-time display | Fixed | POST returns raw key, GET returns metadata only; no-store headers and safe `toString()` added. |
| Revocation | OK for lifecycle | Owner-scoped `UPDATE`, `COALESCE(revoked_at, now())`, no reactivation except explicit POST replacement. No request validator exists to test immediate rejection. |
| Authentication use | Follow-up | `MerchantOrderController` consumes JWT and S1 approval client; repository search found no API-key verifier/header handling. |
| Error uniformity / constant time | Not applicable yet | No API-key verification path. |
| Log redaction | OK for present paths | No key lifecycle logging; gateway request log omits headers/body. Safe `toString()` is now enforced for the only DTO holding the raw key. |
| Audit | Follow-up | No key create/revoke/failure audit event or record found. |
| Abuse throttling | Risk | Gateway IP bucket only; no per-key or direct-service protection. |
| Docs/contracts | Follow-up/verified | No key endpoint doc is in HEAD; historical docs described absent dispatcher endpoints and a validation API. S2 JWT docs match implementation. No API-key auth contract was added. |
| Repository secret hygiene | Risk | Current tree had no full-length key matches. Historical commit `8512b698` contains long `zn_live_`-format documentation examples whose authenticity cannot be established from code alone. Production and remote scans remain unverified. |

## Follow-up issue text

**Title:** Define whether merchant API keys authenticate S2 merchant requests  
**Owning service/maintainer:** S2 Parcel & Pricing, @Kiruthiyan (coordinate with S1 owner @14Jannan and gateway owner)  
**Gap:** S1 issues and hashes merchant API keys, but no service or gateway accepts them. S2 orders require Keycloak bearer JWTs and S1 approval.  
**Reproduction:** Create a key through S1; send it as a bearer token or candidate API-key header to `/api/v1/merchant/orders`; the gateway/S2 has no key lookup path and does not authenticate it.  
**Proposed fix:** Decide the supported authentication contract and validating owner first. If API keys are required, design a gateway-compatible validation path that does not read S1's database cross-service, preserves approval/ownership checks, uses constant-time hash comparison, uniform errors, revocation propagation, rate limits, and tests. Do not silently change S2's JWT contract.

**Title:** Add secret-free audit events for merchant API-key lifecycle  
**Owning service/maintainer:** S1 Identity & Partner, @14Jannan  
**Gap:** Key creation and revocation are not audit-recorded.  
**Reproduction:** Create or revoke a key and inspect S1 audit rows/logs; no API-key lifecycle event is written.  
**Proposed fix:** Add owner-scoped create/revoke audit events containing merchant subject, action, timestamp, and non-secret key identifier only; ensure raw key and hash are excluded.

**Title:** Verify and retire historical merchant-key examples  
**Owning service/maintainer:** S1 Identity & Partner, @14Jannan  
**Gap:** Historical commit `8512b698` includes long API-key-format values in documentation examples; the repository cannot establish whether these were ever real credentials.  
**Reproduction:** Inspect only the historical `docs/api/merchant-api-keys.md` blob from `8512b698`; do not copy or publish the values.  
**Proposed fix:** Confirm whether each example was synthetic. If any was real or reused, revoke it and rotate the affected merchant credential; remove or replace the historical examples through the repository's approved history-cleanup process if required.

## Reproduction commands

Run from `backend/`:

```sh
mvn -pl identity-partner-service -Dtest=MerchantApiKeyServiceTest,RoleBasedAuthorizationMatrixTests test
mvn --batch-mode test package
```

Current storage properties can also be checked in `identity-partner-service/src/main/resources/db/migration/V2__merchant_api_keys.sql` and `MerchantApiKeyService.issueOrRotate`. There is no API-key verification integration test because the application has no verifier. The key-specific tests use mocked JDBC to assert the generated value, distinct values, and hash passed for persistence; a Testcontainers database assertion of the stored row was not added.

## Verification results

Environment commands:

```text
java -version: openjdk version "21.0.12.1" (2026-08-18), OpenJDK Runtime Environment 21.0.12.1+1-24.04, 64-Bit Server VM
mvn -v: Apache Maven 3.8.7; Java 21.0.12.1; Linux 7.0.0-42-generic amd64
docker --version: Docker version 29.8.0, build 88096ef
uname -sr: Linux 7.0.0-42-generic
```

Focused S1 run (`mvn --batch-mode -pl identity-partner-service -am -Dtest=MerchantApiKeyServiceTest,RoleBasedAuthorizationMatrixTests -Dsurefire.failIfNoSpecifiedTests=false test`): 50 tests, 0 failures, 0 errors, 0 skipped.

Full requested run (`mvn --batch-mode test package`): reached S1, then failed because S1 has two version-4 migrations (`V4__courier_profiles.sql` and `V4__enforce_merchant_rejection_reason.sql`). The initial full-reactor S1 attempt reported 246 tests, 0 assertion failures, 10 errors, 0 skipped; a final standalone S1 rerun on the committed test set reported 250 tests, 0 assertion failures, 10 errors, 0 skipped. The 10 errors are one migration startup error and nine `UserAdminPostgresIntegrationTest` context errors caused by that migration collision. The following modules were not reached in the full-reactor invocation and were run separately with Docker available:

| Module | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| zentro-security-starter | 7 | 0 | 0 | 0 | pass |
| api-gateway | 183 | 0 | 0 | 1 | pass with existing skip |
| identity-partner-service | 250 | 0 | 10 | 0 | migration blocker |
| parcel-pricing-service | 558 | 0 | 0 | 0 | pass |
| dispatch-courier-service | 144 | 0 | 0 | 0 | pass |
| tracking-reporting-service | 31 | 0 | 0 | 0 | pass |
| notification-integration-service | 259 | 0 | 0 | 0 | pass |

The one skipped gateway integration test is `GatewayKeycloakIntegrationTests.upstreamAndGatewayFailuresUseTheSameProblemJsonShape`; its test report documents an existing 5xx `instance` shape mismatch. Docker-backed tests ran for the gateway and separately run S2–S5 modules. S1's container-backed database tests were attempted, but the existing migration collision prevented its database integration context from starting. No test groups were skipped for Docker unavailability.

This environment reported `gh auth status` as failed: the configured GitHub token is invalid. Issue #126 and #32 could not be fetched, and no push or PR was created. No merged commit SHA exists; record it after merge.

## Review limits

Issue #126 and issue #32 could not be retrieved due GitHub API/network failure during this run. PR #114 was read and confirms S5's AES-GCM storage, one-time display, and no-store response. Live Keycloak, production secret-store configuration, production database contents, deployed gateways/services, production logs/traces, and historical remote repository secret scanning were not audited.
