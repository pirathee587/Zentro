# Dispatch assignment lifecycle

S3 owns assignment state in its own `dispatch` database. All lifecycle mutations
require an authenticated `DISPATCHER`; the actor is always taken from the JWT
subject and cannot be supplied by the request.

* `POST /api/v1/dispatch/assignments/{parcelId}/reassign` with
  `{"courierId":"..."}` replaces the assignment after fresh S1 eligibility
  verification.
* `DELETE /api/v1/dispatch/assignments/{parcelId}` cancels the active
  assignment and returns `204`.

Each successful assignment, reassignment, or cancellation writes one audit row
and one outbox envelope in the same S3 transaction. The outbox publisher emits
`delivery.assigned`, `delivery.reassigned`, or `delivery.cancelled` with the
parcel ID as the Kafka key. Events contain courier profile IDs and the
dispatcher subject, but no cross-service database data.
