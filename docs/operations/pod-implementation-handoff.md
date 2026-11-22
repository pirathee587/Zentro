# POD implementation handoff

## Completed code

- `V8__pod_review_and_private_photo_metadata.sql`: prepared-only additive review, revision, audit, and staged-upload tables.
- `ProofImageValidator`: JPEG/PNG/WebP magic-byte validation and 5 MiB limit.
- `SupabaseProofStorage`: backend-only private upload adapter and five-minute signed dispatcher URL adapter.
- `ProofStageRepository`: courier-scoped idempotency lookup, durable upload-stage insert, and one-way finalization marker.
- `DispatcherProofReviewController`: dispatcher-only list, approve, reject, and signed-preview endpoints; approval is `COMPLETION_PENDING`, never `DELIVERED`.
- `ProofCompletionController`: dispatcher-only, explicitly targeted S3-to-S2 completion endpoint for `COMPLETION_PENDING`/failed reviews; no global worker is enabled.
- Recovery is stale-claim-only, reconciles S2 before any replay, fails closed when S2 status is uncertain, and excludes legacy proof `a4f5e154-0bea-4df3-b04b-7df25aecc0a1`.

## Safety state

V8 activation is separately approval-gated. This handoff does not authorize changes to any bucket/policy, Keycloak configuration, live service, proof, assignment, parcel, worker, Kafka consumer, or outbox.

## Required next coding action

Implement a `CourierProofSubmissionService` that performs these recoverable steps:

1. authenticate courier ownership using the existing `ProofOfDeliveryService`/assignment path;
2. validate image bytes and SHA-256;
3. create/reuse a courier-scoped idempotency stage;
4. upload only once for that stage;
5. in one local DB transaction, create the immutable revision + `PENDING_REVIEW` and mark the stage final;
6. if metadata finalization fails after storage upload, leave the stage `UPLOADED` for authenticated retry/cleanup; never create a photo-less proof;
7. add a scheduled cleanup only behind a disabled-by-default property, so it cannot affect shared S3 before activation.

## Tests run

### Latest isolated V7 verification

- `CourierWorkflowIntegrationTest` now has 16 passing Testcontainers PostgreSQL cases. It exercises real multipart courier upload, courier ownership denial, same-key retry without a duplicate proof/revision or second private-storage upload, storage failure without a proof/revision, dispatcher HTTP authorization, optimistic review conflicts, concurrent completion claims (one S2 call), stale delivered reconciliation, uncertain-state blocking and exclusion of the historical proof ID.
- The focused S3 POD suite (`CourierWorkflowIntegrationTest`, completion/review/controller-validator/worker/client tests) passed **31 tests, 0 failures, 0 errors**.
- S4 isolated public tracking contract tests (`TrackingServiceTest`, `PublicTrackingControllerTest`) passed **21 tests, 0 failures, 0 errors**. They verify delivered status/timeline projection and public response redaction; they do not exercise live S2/S4 networking.
- `frontend/zentro-web`: `npm run typecheck` and production `npm run build` passed. Vite reports only its existing large-chunk warning.

### Defect fixed during verification

`ProofCompletionController` previously moved an approved review to `COMPLETION_PROCESSING` while retaining reviewer fields, which violates the V7 review-state constraint. The transition now clears those fields while processing and restores them only when recording `COMPLETED`, `COMPLETION_FAILED`, or `BLOCKED`. Its completion audit now records the correct source state (`COMPLETION_PROCESSING`).

- `mvn -q -DskipTests compile` — passed after current backend additions.
- `mvn -q -Dtest=ProofImageValidatorTest test` — passed.
- `mvn -q -Dtest=ProofCompletionWorkerTest,ParcelStatusClientResilienceTest test` — passed.
- `frontend/zentro-web: npm run typecheck && npm run build` — passed.
- `DispatcherProofReviewControllerTest, ProofImageValidatorTest` — passed; direct validation coverage confirms blank dispatcher rejection reasons fail before any storage update.

## Remaining dependency order

1. Courier stage/finalize/status endpoints and unit tests.
2. Dispatcher detail/audit endpoints and review tests.
3. Courier and dispatcher React API clients/pages/routes/tests.
4. Isolated mocked storage/S2/S4 integration tests; frontend typecheck/build.
6. Approval-gated live activation only after all code/test work passes.
