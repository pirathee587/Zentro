# POD Service-to-Service JWT Authentication over gRPC

## Service-to-Service Authentication Matrix

| Calling Service | Receiving Service | gRPC Method | Required Permission | Token Issuer | Token Audience | Authentication Mechanism |
|---|---|---|---|---|---|---|
| Dispatch & Courier Service (S3) | Parcel & Pricing Service (S2) | `zentro.parcel.v1.ParcelPricingService/GetParcel` | `SERVICE` role + `pod:read` | `http://localhost:8180/realms/zentro` | `zentro-api` | OAuth2 `client_credentials` (`zentro-dispatch-service`) attached via `authorization: Bearer <jwt>` gRPC metadata |
| Dispatch & Courier Service (S3) | Parcel & Pricing Service (S2) | `zentro.parcel.v1.ParcelPricingService/CompleteParcelDelivery` | `SERVICE` role + `pod:update` | `http://localhost:8180/realms/zentro` | `zentro-api` | OAuth2 `client_credentials` (`zentro-dispatch-service`) attached via `authorization: Bearer <jwt>` gRPC metadata |
| Dispatch & Courier Service (S3) | Parcel & Pricing Service (S2) | `zentro.parcel.v1.ParcelPricingService/UpdateCourierParcelStatus` | `SERVICE` role + `pod:update` | `http://localhost:8180/realms/zentro` | `zentro-api` | OAuth2 `client_credentials` (`zentro-dispatch-service`) attached via `authorization: Bearer <jwt>` gRPC metadata |

## Architecture Summary
1. **S3 (`ServiceTokenProvider` + `PodGrpcClientInterceptor`)**:
   - Acquires a short-lived `SERVICE` JWT from Keycloak using `grant_type=client_credentials` (`POD_TOKEN_URI`, `POD_CLIENT_ID`, `POD_CLIENT_SECRET`).
   - Caches the token in memory (`synchronized`) and refreshes it 30s before expiry.
   - Attaches `authorization: Bearer <jwt>` to outgoing gRPC metadata in `PodGrpcClientInterceptor` without logging tokens or storing them in a database/browser storage.
2. **S2 (`PodGrpcSecurityInterceptor` + `ParcelPricingGrpcService` + `ParcelPricingGrpcServer`)**:
   - Listens on gRPC port `9082`.
   - Validates the JWT signature, issuer, and `zentro-api` audience via `JwtDecoder`.
   - Enforces `SERVICE` realm role and method-level permission (`pod:read` / `pod:update`), returning `UNAUTHENTICATED` for missing/invalid tokens and `PERMISSION_DENIED` for human user tokens (`CUSTOMER`, `COURIER`, `MERCHANT`, `DISPATCHER`) or insufficient scopes.

## Service Client Identities and Scope Boundaries

The other backend gRPC callers use separate client identities: S2 uses `zentro-parcel-service` for S1 lookups, S4 uses `zentro-tracking-service` for S2/S3 read operations, and S5 uses `zentro-notification-service` for S1/S2 operations. They do not use S3's POD client credentials. `SERVICE_TOKEN_URI`, `SERVICE_CLIENT_ID`, and `SERVICE_CLIENT_SECRET` are injected per service; S3's POD credential uses `POD_TOKEN_URI`, `POD_CLIENT_ID`, and `POD_CLIENT_SECRET`. Keep secrets only in deployment secret stores or ignored service `.env` files. Compose preserves the canonical development JWT issuer `http://localhost:8180/realms/zentro`, while using `keycloak:8080` for internal token and JWKS requests.

S1 also checks the SERVICE token's `azp` for each RPC: `zentro-dispatch-service` may call courier lookup/list methods; `zentro-parcel-service` may call merchant status/verification methods; `zentro-notification-service` may call merchant verification only. `zentro-tracking-service` is restricted to S2 customer/public read methods and S3 assignment summary. S2 applies an additional exact client/RPC allowlist; only `zentro-dispatch-service` receives `pod:read pod:update`, and only its POD RPC methods use those claims. No production secret is committed.

### Reproducible Keycloak client provisioning

`infra/keycloak/service-clients.json` is the client and permission manifest. After the `zentro` realm and SERVICE realm role exist, inject `KC_URL`, `KC_ADMIN_USERNAME`, `KC_ADMIN_PASSWORD`, `ZENTRO_PARCEL_SERVICE_CLIENT_SECRET`, `ZENTRO_DISPATCH_SERVICE_CLIENT_SECRET`, `ZENTRO_TRACKING_SERVICE_CLIENT_SECRET`, and `ZENTRO_NOTIFICATION_SERVICE_CLIENT_SECRET`, then run:

```sh
python3 infra/keycloak/provision_service_clients.py
```

Each client secret must contain at least 32 characters and must come from the deployment secret manager or a local ignored `.env` file. The script idempotently configures confidential client credentials, service accounts, `fullScopeAllowed=false`, the `zentro-api` audience, and only the SERVICE role. It verifies issued token claims without printing secrets or tokens. S2 consumes `SERVICE_CLIENT_ID=zentro-parcel-service` and `SERVICE_CLIENT_SECRET`; S3 consumes `POD_CLIENT_ID=zentro-dispatch-service` and `POD_CLIENT_SECRET`; S4/S5 use their respective `SERVICE_CLIENT_ID` and `SERVICE_CLIENT_SECRET`. In Compose, those values are supplied through the four `ZENTRO_*_CLIENT_SECRET` environment variables. Keep the public issuer `http://localhost:8180/realms/zentro` consistent with the issuer in issued tokens; containers use Keycloak's internal DNS name only for token/JWKS requests.

The RPC allowlists are enforced in the receiving services:

| Client | S1 Identity RPCs | S2 Parcel RPCs | S3 Dispatch RPCs |
|---|---|---|---|
| `zentro-dispatch-service` | `GetCourierProfile`, `ListCouriers`, `GetCourierById`, `GetCourierByUserSubject` | `GetParcel` (`pod:read`), `CompleteParcelDelivery` and `UpdateCourierParcelStatus` (`pod:update`) | — |
| `zentro-parcel-service` | `GetMerchantStatus`, `VerifyMerchantAccount` | — | — |
| `zentro-tracking-service` | — | Customer/public read and estimate methods only | `GetParcelAssignmentSummary` only |
| `zentro-notification-service` | `VerifyMerchantAccount` | Customer parcel reads and estimate only | — |

S1 also accepts authorized DISPATCHER user JWTs on its dispatcher operations. Those user tokens are evaluated by realm role and are not converted to SERVICE identity. S2 and S3 service RPCs require a SERVICE token; missing/invalid JWTs are `UNAUTHENTICATED`, while a valid identity calling the wrong RPC is `PERMISSION_DENIED`.

The POD completion path starts with the courier's authenticated request to S3. S3 verifies the assignment's courier profile and user subject, requires the assignment to be `ACCEPTED`, and persists the proof before its completion worker asks Keycloak for an S3 service token. It calls S2's `CompleteParcelDelivery` over gRPC; S2 checks signature, issuer, audience, `SERVICE`, and `pod:update`, applies the `DELIVERED` transition idempotently, then writes its outbox event in the same transaction. Kafka delivers the status event to S4 tracking and S5 notification projections. A courier JWT cannot satisfy S2's service-role check, and duplicate completion is treated idempotently by S2. S3 owns retries for transient failures; it does not blindly retry non-idempotent gRPC calls.

S1/S2/S3 expose gRPC on 9081/9082/9083. S4/S5 are outbound-only gRPC clients and have no gRPC server ports. Contract generation is performed by the Maven service modules from `contracts/proto/`. See [the distributed POD E2E instructions](../testing/distributed-pod-e2e.md) for packaging, isolated execution, coverage, and Docker requirements.
