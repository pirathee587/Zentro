# Dispatcher user administration API (ZD-042)

Service: S1 Identity & Partner (`identity-partner-service`, port 8081), reached through the API gateway on port 8080.
Issue: #117. Related: #25 (roles), #116 / [permission-matrix.md](../security/permission-matrix.md) (role matrix, unchanged).

Dispatchers manage platform users here: they list users, view one user, and change a user's name, phone or account status. There is no ADMIN role; administration belongs to DISPATCHER (WARP.md 2.4).

## Endpoints

| Method and path | Roles | What it does |
|---|---|---|
| `GET /api/v1/admin/users?status=&search=` | DISPATCHER | List users, newest first |
| `GET /api/v1/admin/users/{userId}` | DISPATCHER | One user |
| `PATCH /api/v1/admin/users/{userId}` | DISPATCHER | Change name, phone and/or account status |
| `PATCH /api/v1/users/{userId}` | owner, or DISPATCHER for another user | Change name/phone. When a dispatcher changes **another** user, the same rules and audit as the admin PATCH apply. |

Authorization is checked in three places:
- **Gateway:** `/api/v1/admin/**` requires DISPATCHER.
- **Controller:** `@PreAuthorize("hasRole('DISPATCHER')")`.
- **Service:** `UserProfileService.adminUpdate` checks again, so the rule holds even if the service is called from elsewhere.

Other roles get `403`; a missing or invalid token gets `401` (problem+json from the security starter).

### Response (unchanged)

```json
{
  "id": "6d2e…",
  "email": "kamal@zentro.test",
  "name": "Kamal Silva",
  "phone": "0772223333",
  "enabled": false,
  "accountStatus": "DISABLED",
  "merchantStatus": null,
  "roles": []
}
```

The response never contains the Keycloak subject or any other internal identifier. `roles` is always empty because S1 does not store roles; Keycloak does.

## List filters

| Parameter | Rule |
|---|---|
| `status` | Optional. `ACTIVE`, `DISABLED` or `SUSPENDED`, any letter case. Any other value returns `400` (previously it silently returned an empty list). |
| `search` | Optional. At most 100 characters, no control characters. Matches name or email, case-insensitive, as a "contains" search. `%` and `_` match literally, not as wildcards. |

## Update request

`PATCH /api/v1/admin/users/{userId}`

```json
{ "name": "Kamal Silva", "phone": "+94 77 123 4567", "accountStatus": "disabled", "reason": "Repeated chargebacks" }
```

| Field | Rule |
|---|---|
| `name` | Optional. Trimmed, 1-200 characters, no control characters. |
| `phone` | Optional. Sri Lankan number: `+94` or `0` followed by 9 digits; spaces and dashes are ignored and removed before saving (the same rule as S2 bookings). |
| `accountStatus` | Optional. `ACTIVE`, `DISABLED` or `SUSPENDED`, any letter case; saved in upper case. |
| `reason` | Optional, **new**. At most 500 characters, no control characters except new lines and tabs. Stored only in the audit record. |

**Rules:**
- **At least one change:** at least one of `name`, `phone`, `accountStatus` must be present, otherwise `400 EMPTY_UPDATE`.
- **Only changed values are checked:** `name` and `phone` are validated only when they differ from the stored values. The Users page always sends the current name and phone, so users with older phone formats stay editable.
- **Nothing changed, nothing written:** a request whose values equal the stored ones returns `200` with the user and writes nothing, not even an audit row.
- **Protected and unknown fields (new):** any field other than the four above is rejected with `400`:
  - `PROTECTED_FIELD` for identity, role and system fields: `roles`, `role`, `realmRoles`, `realm_access`, `email`, `keycloakSubject`, `keycloak_subject`, `subject`, `sub`, `id`, `userId`, `accountType`, `enabled`, `merchantStatus`, `password`, `createdAt`, `updatedAt`.
  - `UNKNOWN_FIELD` for anything else.

  This applies to this request only. Email, Keycloak subject and roles are never editable through S1; roles are managed in Keycloak.
- **Own account status:** a dispatcher cannot change their own account status, in either direction (`400 SELF_STATUS_CHANGE`). Sending their current status is allowed and does nothing, which is what the Users page does for the signed-in dispatcher.
- **Concurrent changes:** the target row is locked (`SELECT … FOR UPDATE`), so two dispatchers changing the same user run one after another and each audit row records the status it really replaced.

## Errors

All errors are `application/problem+json` with a `code`. Validation errors add `fieldErrors` keyed by request field. Messages name the rule; they never repeat submitted names or phone numbers, user ids, Keycloak subjects, SQL or stack traces. `instance` is the request path, as in every problem+json response.

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | A field breaks a rule (`fieldErrors.<field>`), or an invalid list `status` (`fieldErrors.status`) |
| 400 | `EMPTY_UPDATE` | No name, phone or status in the body |
| 400 | `PROTECTED_FIELD` / `UNKNOWN_FIELD` | A field outside name, phone, accountStatus and reason (`fieldErrors.<field>`) |
| 400 | `SELF_STATUS_CHANGE` | A dispatcher changing their own status |
| 400 | `INVALID_USER_ID` | `{userId}` is not a UUID |
| 400 | `MALFORMED_REQUEST` | Body missing or not valid JSON |
| 401 | (security) | Missing or invalid token |
| 403 | (security) | Not a DISPATCHER |
| 404 | `USER_NOT_FOUND` | No such user (`"detail": "User not found."`) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Body is not JSON |
| 503 | `USERS_UNAVAILABLE` | The S1 database is unavailable |

Example:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Field 'roles' cannot be changed through this API.",
  "instance": "/api/v1/admin/users/6d2e…",
  "code": "PROTECTED_FIELD",
  "fieldErrors": { "roles": "Field 'roles' cannot be changed through this API." }
}
```

## Audit record

Migration `V6__user_admin_audit.sql` creates `identity.user_admin_audit`. Every real change by a dispatcher (both PATCH paths above) writes **one** row in the same transaction as the change: both or neither are saved.

| Column | Content |
|---|---|
| `actor_user_id`, `actor_subject` | The dispatcher (S1 user id and Keycloak subject) |
| `target_user_id` | The changed user |
| `action` | `STATUS_CHANGED` when the status changed, otherwise `PROFILE_UPDATED` |
| `changed_fields` | Field **names** only, for example `{name,accountStatus}`. Never old or new names, phones or emails. |
| `old_status`, `new_status` | Status before and after |
| `reason` | Optional reason from the request |
| `occurred_at` | Time of the change |

- **Append-only:** a trigger rejects every `UPDATE`, `DELETE` and `TRUNCATE`. There are no foreign keys, so rows outlive the users they describe.
- **Logs:** each change also writes one INFO log line with the actor and target ids, action, field names and statuses. It never contains names, phone numbers, emails or the reason.
- **No read API in this task (D4):** dispatchers do not query the database directly (WARP.md 2.4). Reading the records is done by the S1 owner with SQL, for example:

```sql
SELECT action, changed_fields, old_status, new_status, reason, occurred_at
  FROM identity.user_admin_audit
 WHERE target_user_id = '<user id>'
 ORDER BY occurred_at DESC;
```

## Compatibility

- **Unchanged:** the response shape; the existing Users page requests (current name and phone plus status).
- **New:** the optional `reason`; `400` for unknown or protected fields, which were silently ignored before; `400` for an invalid list status, which returned an empty list before; problem+json error bodies with `code` and `fieldErrors`, where 400s had no details before.
- The Users page now sends the optional audit `reason` (up to 500 characters) with status and profile updates, displays server `detail` and inline `fieldErrors` on failure, displays roles as read-only governed by Keycloak, and presents an explicit confirmation modal before status changes (completed in ZD-084).

## Known limits and follow-ups

- **Account status is not enforced everywhere:** it is checked for couriers (S3 eligibility), merchant API keys and merchant checks in S2/S5, but not at Keycloak login or for customer endpoints in S2/S4. gRPC `GetMerchantStatus` returns a fixed `ACTIVE`. Owner: @14Jannan (S1) with the S2/S4 owners.
- **Users page follow-ups (completed in ZD-084):** Dispatcher Users page sends optional `reason`, prompts for confirmation on account status changes, displays inline `fieldErrors` from ProblemDetails, and enforces read-only role governance.
- **Permission matrix D1/D2** (default CUSTOMER role, role assignment and SERVICE client): @pirathee587.
