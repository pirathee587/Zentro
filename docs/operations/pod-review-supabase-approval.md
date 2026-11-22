# POD review and private photo activation

**Status: prepared only. Do not run against the shared Supabase project without explicit approval.**

## Scope and rollback boundary

- One private bucket: `swiftdrop-delivery-proofs`.
- One S3 schema migration: `V8__pod_review_and_private_photo_metadata.sql`.
- No change to existing proofs, parcel history, assignments, Keycloak roles, Kafka, outbox, or S3 worker state.
- The migration is additive. Rollback is application rollback; do not drop the new tables after records exist. Disable new upload/review routes and retain evidence/audit data instead.

## Preflight

1. Take a schema-only backup of `dispatch` and record the current Flyway history/checksum.
2. Confirm `swiftdrop-delivery-proofs` does not already exist. If it exists, verify it is private and owned by the approved project; do not recreate it.
3. Store the Supabase URL and service-role key only in the S3 deployment secret store as `POD_STORAGE_URL` and `POD_STORAGE_SERVICE_KEY`. Never put either value in frontend configuration, source control, browser storage, logs, or signed URL responses.
4. Confirm the S3 deployment remains `SPRING_FLYWAY_ENABLED=false`, `POD_WORKER_ENABLED=false`, `OUTBOX_ENABLED=false`, restart policy `no`; S4 Kafka remains disabled.

## One-time approved operations

1. Create bucket `swiftdrop-delivery-proofs` with public access disabled.
2. Apply the additive V8 migration through the approved schema-migration process, not application startup.
3. Apply storage policy: no anonymous access; only S3 service credentials may create/read/delete objects. Browser clients have no storage policy access.
4. Deploy the reviewed S3 image during an approved maintenance window. The app must upload after courier ownership validation, persist only object metadata/path, and create signed dispatcher URLs valid for at most five minutes.
5. Smoke test with an isolated fixture: JPEG upload, dispatcher review, S2 confirmed completion, then verify S4 public tracking. Delete the isolated object only through the approved retention job.

## Retention and orphan handling

- A failed metadata transaction must schedule best-effort deletion of the just-uploaded object; never expose its path to the client.
- Uploads first create an `UPLOADED` stage with a courier-scoped idempotency key. Only a subsequent database transaction may turn that stage into an immutable revision plus `PENDING_REVIEW`; an expired unfinalized stage is inaccessible and eligible for orphan cleanup.
- A daily privileged job may list only the bucket prefix for objects older than 24 hours with no `dispatch.proof_revision.object_path` reference, log aggregate counts only, and delete after a 7-day quarantine/report window.
- Evidence linked to a proof follows the approved legal/operations retention policy; it is never overwritten by a re-submission.

## Legacy compatibility

Historical `proof_of_delivery` rows, including `a4f5e154-0bea-4df3-b04b-7df25aecc0a1`, have no revision/photo and remain valid legacy evidence. They are not auto-approved, replayed, or made invalid for lacking a photo.

## Live recovery exclusion

This rollout does not recover BLOCKED proofs. Any recovery must be a separately approved, proof-ID-targeted S3-to-S2 operation after reviewing all PENDING/BLOCKED counts.
