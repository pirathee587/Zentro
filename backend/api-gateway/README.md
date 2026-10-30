# Zentro API Gateway

Reactive Spring Cloud Gateway for browser and merchant API traffic. The gateway validates Keycloak access tokens, applies the edge role policy, adds correlation IDs, rate limits by client IP, and routes requests to S1–S5. It has no database. Internal service calls, Kafka, and gRPC traffic do not pass through it.

```text
Browser / merchant client -> Gateway :8080 -> S1 identity | S2 parcel | S3 dispatch | S4 tracking | S5 notification
                                      \-> Keycloak verifies JWT issuer, signature, expiry, and audience
```

## Routes and roles

Role claims come from `realm_access.roles`; only `CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, and `SERVICE` are recognized. `SERVICE` is technical and cannot access routes by itself. No `ADMIN` role exists; administrative work belongs to `DISPATCHER`. Requests with a token but no recognized human role are denied on human routes.

| Route and method | Access | Upstream |
|---|---|---|
| `GET /api/v1/tracking/mine` | CUSTOMER, MERCHANT | S4 |
| `GET /api/v1/tracking/{trackingNumber}` and `/events` or `/stream` | Public lookup and SSE stream | S4 |
| Other methods under `/api/v1/tracking/**` | Denied | — |
| `GET /actuator/health/**` | Public | Gateway |
| `/api/v1/admin/**` | DISPATCHER | S1/S2/S5 per path |
| `/api/v1/dispatch/**`, `/api/v1/reports/**` | DISPATCHER | S3/S4 |
| `/api/v1/courier/**` | COURIER | S3 |
| `/api/v1/merchant/**` | MERCHANT | S2 orders; S5 webhooks |
| `/api/v1/parcels/**` | CUSTOMER, DISPATCHER | S2 |
| `/api/v1/users/**`, `/api/v1/merchants/**`, `/api/v1/zones/**` | CUSTOMER, MERCHANT, COURIER, DISPATCHER | S1/S2 |
| Unknown `/api/v1/**` | Safe 404 problem+json | — |
| Non-API paths | 404 problem+json | — |

| Route ID | Path predicates | Service |
|---|---|---|
| identity | `/api/v1/users/**`, `/api/v1/merchants/**`, `/api/v1/admin/merchants/**`, `/api/v1/admin/users/**`, `/api/v1/admin/couriers/**` | S1 |
| parcel | `/api/v1/zones/**`, `/api/v1/parcels/**`, `/api/v1/merchant/orders/**`, `/api/v1/admin/zones/**`, `/api/v1/admin/pricing-rules/**` | S2 |
| dispatch | `/api/v1/dispatch/**`, `/api/v1/courier/**` | S3 |
| tracking | `/api/v1/tracking/**`, `/api/v1/reports/**` | S4 |
| notification | `/api/v1/merchant/webhooks/**`, `/api/v1/admin/audit-logs/**` | S5 |

## Environment

Copy `.env.example` to `.env` and set values for your environment. `.env` is ignored by Git. The defaults support running the gateway from an IDE; Docker deployments should set service hostnames such as `http://identity-partner-service:8081`.

| Variable | Default | Purpose |
|---|---|---|
| `GATEWAY_PORT` | `8080` | HTTP port |
| `JWT_ISSUER_URI` | `http://localhost:8180/realms/zentro` | Keycloak issuer |
| `JWT_AUDIENCE` | `zentro-api` | Required JWT audience |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Explicit browser origins |
| `S1_URI` … `S5_URI` | localhost ports 8081–8085 | Upstream service URLs |
| `JWT_JWKS_URI` | issuer discovery | Optional explicit Keycloak JWKS URL; issuer and token timestamps are still validated |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `1000` | Upstream connection timeout in milliseconds |
| `GATEWAY_RESPONSE_TIMEOUT` | `30s` | Default upstream response timeout |
| `GATEWAY_TRACKING_RESPONSE_TIMEOUT_MS` | `3600000` | S4 tracking/report response timeout in milliseconds, preserving long-lived event streams |
| `RATE_LIMIT_TRACKING_PER_MINUTE` | `30` | Tracking endpoint limit per IP |
| `RATE_LIMIT_DEFAULT_PER_MINUTE` | `300` | Other API endpoint limit per IP |
| `TRUST_X_FORWARDED_FOR` | `false` | Trust first forwarded IP only behind a trusted proxy |

## Run locally

From the repository root, run `mvn -f backend/api-gateway/pom.xml spring-boot:run` after setting the environment variables. Check `http://localhost:8080/actuator/health`. Prometheus metrics are exposed at `/actuator/prometheus` and require authentication.

## Run in Docker

Build from the `backend` directory with `docker build -f api-gateway/Dockerfile -t zentro-api-gateway .`. Run the image with environment variables for Keycloak, CORS, and S1–S5 service URLs. The container uses Java 21 and a non-root user.

Rate limiting uses in-memory Bucket4j buckets with idle expiry and a size cap. It is local to each gateway instance; use Redis-backed rate limiting for production deployments with multiple replicas.

SSE responses from `/api/v1/tracking/*/stream` have a long upstream response timeout. Compression is disabled so events are not buffered; S4 sends the heartbeat that keeps the connection open.

## Tests

Run `mvn -B -f backend/pom.xml -pl api-gateway -am verify` from the repository root.
