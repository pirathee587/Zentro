# POD V8 controlled deployment checklist

Status: prepared only. These commands are not approval to run them against the shared environment.

## Preconditions and rollback

1. Record the current S3 image ID and tag as the rollback image. Do not remove it.
2. Take the approved `dispatch` schema backup and record Flyway history/checksums.
3. Confirm S3 remains `restart: no`, `SPRING_FLYWAY_ENABLED=false`,
   `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true`, `POD_WORKER_ENABLED=false`, and
   `OUTBOX_ENABLED=false`; confirm S4 has `KAFKA_CONSUMER_ENABLED=false`.
4. Build and inspect `zentro-dispatch-courier-service:pod-v8` before the maintenance window.
5. Store `POD_STORAGE_URL` and `POD_STORAGE_SERVICE_KEY` only in the deployment secret store.

## Approval-gated activation sequence

1. Obtain approval for the additive `V8__pod_review_and_private_photo_metadata.sql` migration.
2. Create or validate the private `swiftdrop-delivery-proofs` bucket. Deny anonymous and browser-client access; allow only the S3 backend credential.
3. Apply V8 through the approved database migration process, never through S3 startup.
4. Inject the backend-only storage settings and set `S3_IMAGE_TAG=zentro-dispatch-courier-service:pod-v8`.
5. Recreate only S3 with no dependency operation during the approved window. Do not enable Flyway, the POD worker, outbox, or S4 Kafka.
6. Wait for S3 liveness, then run a synthetic isolated courier JPEG upload, dispatcher approve/reject, and explicit completion test.
7. Confirm S2 reports `DELIVERED`, then refresh the matching S4 public tracking page and verify the delivered milestone.

## Rollback

1. Stop further synthetic POD activity.
2. Recreate only S3 using the recorded prior image and the same safety settings.
3. Keep V8 tables, evidence objects, and audit records intact; the migration is additive and must not be reversed by dropping data.
4. Record failed proof-review/completion IDs for approved, targeted operations. Never replay historical proof `a4f5e154-0bea-4df3-b04b-7df25aecc0a1`.
