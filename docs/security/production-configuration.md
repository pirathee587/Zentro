# Production configuration and secret handling

This is a source configuration audit, not certification of a deployment. Local real environment files, the ignored realm backup and deployed systems were not read. Development Compose files are not production deployment examples. Credential incident details and rotation evidence are held privately pending owner confirmation.

## Configuration boundaries

Each service receives its own `DB_URL`, `DB_USER` and `DB_PASSWORD`. S1 no longer imports Keycloak's environment file or falls back to its database settings; S4 no longer supplies database defaults. Existing explicit configurations keep their meaning. Missing datasource properties now prevent configuration resolution. Tests cover resolution with each property missing and all supplied; they do not establish database reachability or deployed role permissions.

| Service | Owned database | Required runtime configuration |
|---|---|---|
| S1 Identity & Partner | identity_db | DB_URL, DB_USER, DB_PASSWORD |
| S2 Parcel & Pricing | parcel_pricing_db | DB_URL, DB_USER, DB_PASSWORD |
| S3 Dispatch & Courier | dispatch_courier_db | DB_URL, DB_USER, DB_PASSWORD |
| S4 Tracking & Reporting | tracking_reporting_db | DB_URL, DB_USER, DB_PASSWORD |
| S5 Notification & Integration | notification_integration_db | DB_URL, DB_USER, DB_PASSWORD |

Provision independent least-privilege runtime roles and databases. Example URLs are deliberately nonworking; replace them privately. Flyway/runtime role separation, non-superuser grants, actual database names and TLS must be verified by the database owner. Environment variable names repeat across services because each process has a separate environment; do not share credential values.

S1 shared-token gRPC authentication is optional and enabled only by an explicitly nonblank `INTERNAL_SERVICE_TOKEN`. Blank headers cannot authenticate. JWT authentication remains available. Legacy callers must receive a newly provisioned token from the owner. Disabling shared-token authentication does not disable JWT checks.

Select the `prod` Spring profile explicitly for Gateway and S4 to expose only Actuator health. Their existing default profile still exposes health and Prometheus; selecting prod disables scraping unless the deployment owner deliberately restores it. Other services expose health. No diagnostic endpoints are deliberately exposed. Health exposure is not proof of production network isolation.

S5 webhook encryption requires a valid master key when webhooks are enabled; keep it stable until an explicit ciphertext migration is planned. Changing the key without re-encryption can make stored secrets unreadable. S3's POD worker accepts missing OAuth credentials at startup and blocks work when used: deployment readiness validation remains a follow-up.

## Development ports

All published development ports default to loopback using `${BIND_ADDRESS:-127.0.0.1}`. `BIND_ADDRESS` is documented in root `.env.example`; export it consistently when invoking different Compose projects. No production port publication is prescribed here: the deployment owner decides gateway/proxy exposure and internal network isolation.

| Compose file | Component | Bind default | Host → container |
|---|---|---|---|
| docker-compose.yml | Frontend | 127.0.0.1 | 80 → 8080 |
| docker-compose.yml | Gateway | 127.0.0.1 | 8080 → 8080 |
| docker-compose.yml | S1 | 127.0.0.1 | 8081 → 8081 |
| docker-compose.yml | S2 | 127.0.0.1 | 8082 → 8082 |
| docker-compose.yml | S3 | 127.0.0.1 | 8083 → 8083 |
| docker-compose.yml | S4 | 127.0.0.1 | 8084 → 8084 |
| docker-compose.yml | S5 | 127.0.0.1 | 8085 → 8085 |
| backend/docker-compose.yml | Gateway | 127.0.0.1 | 8080 → 8080 |
| backend/docker-compose.yml | S1 | 127.0.0.1 | 8081 → 8081 |
| infra/keycloak/docker-compose.keycloak.yml | Keycloak HTTP | 127.0.0.1 | 8180 → 8080 |
| infra/keycloak/docker-compose.keycloak.yml | Keycloak management | 127.0.0.1 | 9000 → 9000 |

To reach the stack from another device, an operator can explicitly set `BIND_ADDRESS=0.0.0.0` for trusted private LAN/phone testing, with matching frontend, issuer, CORS and redirect settings; **never use this on a shared or public network**. This exposes every published port in that project, including internal services. Prefer a controlled development tunnel where appropriate.

Before changing bindings, the tracked tree was checked for host.docker.internal, host-gateway, extra_hosts, container-to-host bridge routes, Vite proxies, LAN URLs and Keycloak assumptions. No dependency on host-published ports was found; committed URLs/redirects use loopback. Local environment values were intentionally not inspected. A custom local configuration may need operator adjustment.

Validate without displaying resolved credentials or starting containers:

```sh
docker compose config -q
docker compose -f backend/docker-compose.yml config -q
docker compose -f infra/keycloak/docker-compose.keycloak.yml config -q
# After an operator starts a development stack, confirm loopback prefixes:
docker ps --format '{{.Names}}\t{{.Ports}}'
```

## CORS and identity

Gateway reads `CORS_ALLOWED_ORIGINS`; credentials are disabled and allowed methods are GET, POST, PUT, PATCH, DELETE, OPTIONS. Allowed headers include Authorization, Content-Type, X-Correlation-Id and Idempotency-Key; exposed headers include X-Correlation-Id and Location. Preflight max-age is unset. Tests reject unapproved origins and headers. No service-level permissive CORS override was found. Operators must configure explicit HTTPS production origins; source defaults are development origins and do not prove a safe deployment.

The committed Keycloak export is for development: public client, no client secret, no seeded users/credential fields, loopback HTTP redirects with path patterns, no wildcard webOrigins. It is not a production realm template. Production redirects must be exact approved HTTPS URLs; use the same externally visible issuer across Keycloak, Gateway and frontend. Do not apply an export blindly to a live realm. Live issuer, redirects, hostname/proxy headers, cookie flags and TLS settings are NOT VERIFIED.

## TLS and deployment assumptions

The architecture assumes Nginx terminates TLS, but the repository only contains a static frontend HTTP Nginx configuration. Production certificate termination, HTTPS redirects and HSTS are NOT VERIFIED. The static server config sets nosniff, frame denial, referrer policy and CSP; its HTTP/HTTPS connect policy is not proof of a production CSP/TLS policy. Frontend tokens are managed by the existing authentication implementation, not a new cookie session introduced here; deployment cookies and identity-provider cookies require separate review.

Development Keycloak uses start-dev and HTTP. Its bootstrap password is environment-required. An initialization script requesting database TLS does not establish runtime database TLS. Service HTTP/gRPC and Kafka defaults assume a trusted internal network; transport encryption, Kafka SASL, Postgres TLS and network policies are NOT VERIFIED. Kubernetes/Helm directories contain placeholders rather than deployment manifests, so Service/Ingress TLS, NetworkPolicy, securityContext and external exposure require deployment-owner work. No manifests or live realms were changed.

## Developer secret rules and incident response

Never commit real `.env` files, key material, dumps, backups or credential examples. Use obviously synthetic placeholders such as `zn_example_xxx`. Generate strong independent credentials using an approved secret manager or `openssl rand -base64 32`, directly into a protected destination; do not paste values into logs, issues, shell history or screenshots. All `VITE_*` variables are public build inputs: never place secrets in them. Avoid request/body/property debug logging and raw Kafka payloads. Logs and CI artifacts require the same review as code.

When a credential may have reached history, treat it as compromised even after removal from the current tree. Notify its owner privately using metadata only. In order: revoke the exposed credential, provision a replacement, redeploy all authorized consumers, verify the old credential fails and the replacement succeeds without logging either. Maintain a private incident record. For key material encrypting existing data, plan migration before revocation that would destroy access. Rotation is an owner operation; source changes alone do not rotate deployed credentials.

History cleanup is optional and separate from revocation. Coordinate contributors, forks, tags and open branches before considering a rewrite; signatures and commit links change and old copies/caches can persist. No history rewrite or force-push is part of this change. Repository owners must verify GitHub secret scanning and push protection settings; they cannot be established from source.

## Automated guards and limits

The existing Maven security-starter tests inspect tracked files only. They reject real-format API key literals (including docs/fixtures), private-key headers, nonempty secret defaults, unsafe environment examples, shared datasource fallbacks, sensitive Actuator exposure and development ports without loopback/BIND_ADDRESS prefixes. Negative tests generate synthetic rejected patterns in memory. Gateway tests exercise configured CORS, and S1 tests cover empty/wrong tokens, explicit tokens and JWT authentication. No new scanner dependency was added. These checks run with the backend test suite; they are not a complete secret detector or proof of historical revocation.

The audit used local Java/shell checks over tracked current files and unique historical blobs, plus metadata inspection of deleted paths and the tracked PDF. Generic credential candidates include code identifiers and ephemeral fixtures; a match alone is not evidence of a live credential. Gitleaks and trufflehog were unavailable and were not installed. Remote history outside this clone, deployed secrets and external repository settings remain NOT VERIFIED.

## Findings and owner actions

| ID | Classification / severity | Status | Evidence / next action | Owner |
|---|---|---|---|---|
| SEC-01 | RISK / blocker | owner action | Credential incident remediation is pending private owner confirmation; source sanitation does not prove revocation. | Credential owners, to be confirmed privately |
| CFG-01 | PROVEN GAP / major | fixed | S1 Keycloak DB fallback/import and S4 DB defaults removed; explicit datasource tests. | @pirathee587 (backend CODEOWNERS) |
| CFG-02 | PROVEN GAP / major | fixed | Development Compose publication now defaults to loopback; 11 bindings validated. | @pirathee587; deployment owner to be assigned |
| CFG-03 | PROVEN GAP / minor | fixed | Gateway CORS now permits Idempotency-Key and exposes Location, with negative tests. | @pirathee587 |
| CFG-04 | RISK / major | fixed | Explicit Gateway/S4 prod profiles expose health only; production profile activation remains operator responsibility. | Deployment owner to be assigned |
| CFG-05 | PROVEN GAP / major | fixed | Build context and ignore rules exclude environment/key/backup files; guards added. | @pirathee587 |
| LOG-01 | PROVEN GAP / major | follow-up | S4 TrackingKafkaConsumer logs raw Kafka payloads and exceptions; redact/replace with safe structured metadata under #143. | @pirathee587 |
| JOB-01 | RISK / major | needs decision | S3 worker credential readiness is not startup-enforced; decide feature-specific validation. | @pirathee587; S3 maintainer to confirm |
| INF-01 | RISK / major | owner action | Verify production TLS, issuer/proxy settings, cookies, internal encryption and network isolation. | Deployment owner to be assigned |
| DB-01 | RISK / major | owner action | Verify separate least-privilege deployed DB roles and migration/runtime separation. | Database owner to be assigned |
| CI-01 | RISK / minor | follow-up | Images/actions use version tags in places; assess digest/SHA policy and artifact retention. | @pirathee587 (CI CODEOWNERS) |
| REPO-01 | RISK / major | owner action | Verify secret scanning/push protection and decide coordinated history cleanup after rotation. | Repository owner |
| AUTH-01 | OK / minor | reviewed | Realm export has no users/client-secret fields; JWT path retained; live identity configuration NOT VERIFIED. | Identity/deployment owners |

Source ownership uses `.github/CODEOWNERS`; deployment ownership is not established there. Follow-up descriptions above are prepared text, not published issues. Routes, role model, event contracts and five-service ownership are preserved. Browser-visible CORS headers and the explicitly approved configuration/developer workflow changes are listed above.

## Environment variable inventory

Names only are listed below. “Configured” means supply a deployment-specific value; “conditional” means required when the associated feature/provider is enabled. Non-secret defaults still require production review. No k8s Secret wiring is present in this repository. Source paths identify templates and/or environment references; template placeholders are not runtime defaults.
| Source / service | Name | Purpose | Secret | Committed default | Production requirement |
|---|---|---|---|---|---|
| `.env.common.example` | `FRONTEND_ORIGIN` | Browser origin policy | no | template only; not a runtime default | configured / feature-dependent |
| `.env.common.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `.env.common.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `.env.common.example` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `.env.example` | `BIND_ADDRESS` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `CORS_ALLOWED_ORIGINS` | Browser origin policy | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `GATEWAY_CONNECT_TIMEOUT_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `GATEWAY_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `GATEWAY_RESPONSE_TIMEOUT` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `GATEWAY_TRACKING_RESPONSE_TIMEOUT_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `JWT_JWKS_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `RATE_LIMIT_DEFAULT_PER_MINUTE` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `RATE_LIMIT_TRACKING_PER_MINUTE` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `S1_URI` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `S2_URI` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `S3_URI` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `S4_URI` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `S5_URI` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/.env.example` | `TRUST_X_FORWARDED_FOR` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `CORS_ALLOWED_ORIGINS` | Browser origin policy | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `GATEWAY_CONNECT_TIMEOUT_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `GATEWAY_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `GATEWAY_RESPONSE_TIMEOUT` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `GATEWAY_TRACKING_RESPONSE_TIMEOUT_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `RATE_LIMIT_DEFAULT_PER_MINUTE` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `RATE_LIMIT_TRACKING_PER_MINUTE` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `S1_URI` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `S2_URI` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `S3_URI` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `S4_URI` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `S5_URI` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/api-gateway/src/main/resources/application.yml` | `TRUST_X_FORWARDED_FOR` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `DB_PASSWORD` | Service datasource | yes | template only; not a runtime default | required; service-specific |
| `backend/dispatch-courier-service/.env.example` | `DB_URL` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/dispatch-courier-service/.env.example` | `DB_USER` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/dispatch-courier-service/.env.example` | `IDENTITY_BASE_URL` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `PARCEL_BASE_URL` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `POD_CLIENT_ID` | Proof completion worker | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `POD_CLIENT_SECRET` | Proof completion worker | yes | template only; not a runtime default | conditional; provision privately |
| `backend/dispatch-courier-service/.env.example` | `POD_MAX_ATTEMPTS` | Proof completion worker | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `POD_POLL_INTERVAL_MS` | Proof completion worker | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `POD_TOKEN_URI` | Proof completion worker | yes | template only; not a runtime default | conditional; provision privately |
| `backend/dispatch-courier-service/.env.example` | `POD_WORKER_ENABLED` | Proof completion worker | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/.env.example` | `SERVER_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/dispatch-courier-service/Dockerfile` | `GIT_SHA` | Service feature / integration configuration | no | none (required reference) | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `DB_PASSWORD` | Service datasource | yes | none (required reference) | required; service-specific |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `DB_URL` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `DB_USER` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `IDENTITY_BASE_URL` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `PARCEL_BASE_URL` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_CLIENT_ID` | Proof completion worker | no | empty | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_CLIENT_SECRET` | Proof completion worker | yes | empty | conditional; provision privately |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_MAX_ATTEMPTS` | Proof completion worker | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_POLL_INTERVAL_MS` | Proof completion worker | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_TOKEN_URI` | Proof completion worker | yes | empty | conditional; provision privately |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `POD_WORKER_ENABLED` | Proof completion worker | no | nonempty; review for production | configured / feature-dependent |
| `backend/dispatch-courier-service/src/main/resources/application.yml` | `SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/docker-compose.yml` | `BIND_ADDRESS` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/identity-partner-service/.env.example` | `DB_PASSWORD` | Service datasource | yes | template only; not a runtime default | required; service-specific |
| `backend/identity-partner-service/.env.example` | `DB_URL` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/identity-partner-service/.env.example` | `DB_USER` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/identity-partner-service/.env.example` | `INTERNAL_SERVICE_TOKEN` | Service feature / integration configuration | yes | template only; not a runtime default | conditional; provision privately |
| `backend/identity-partner-service/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/identity-partner-service/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/identity-partner-service/.env.example` | `SERVER_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/identity-partner-service/Dockerfile` | `GIT_SHA` | Service feature / integration configuration | no | none (required reference) | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `DB_PASSWORD` | Service datasource | yes | none (required reference) | required; service-specific |
| `backend/identity-partner-service/src/main/resources/application.yml` | `DB_URL` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/identity-partner-service/src/main/resources/application.yml` | `DB_USER` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/identity-partner-service/src/main/resources/application.yml` | `GRPC_SERVER_ENABLED` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `GRPC_SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `INTERNAL_SERVICE_TOKEN` | Service feature / integration configuration | yes | empty | conditional; provision privately |
| `backend/identity-partner-service/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/identity-partner-service/src/main/resources/application.yml` | `SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `DB_PASSWORD` | Service datasource | yes | template only; not a runtime default | required; service-specific |
| `backend/notification-integration-service/.env.example` | `DB_URL` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/notification-integration-service/.env.example` | `DB_USER` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/notification-integration-service/.env.example` | `IDENTITY_BASE_URL` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `KAFKA_CONSUMER_GROUP` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATIONS_CONSUMER_ENABLED` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_HEALTH_FAILED_WINDOW_MINUTES` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_HEALTH_STUCK_PENDING_MINUTES` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_MAX_ATTEMPTS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_MOCK_FAIL_MODE` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_RETRY_BASE_DELAY_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_RETRY_MAX_DELAY_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `NOTIFICATION_RETRY_POLL_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `SERVER_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOKS_ALLOW_INSECURE_LOCAL` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOKS_ENABLED` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_CONNECT_TIMEOUT_MS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_CONSUMER_GROUP` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_HEALTH_FAILED_WINDOW_MINUTES` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_HEALTH_STUCK_MINUTES` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_MAX_ATTEMPTS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_READ_TIMEOUT_MS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_RETRY_BASE_DELAY_MS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_RETRY_MAX_DELAY_MS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_RETRY_POLL_MS` | Webhook delivery / encryption | no | template only; not a runtime default | configured / feature-dependent |
| `backend/notification-integration-service/.env.example` | `WEBHOOK_SECRET_KEY` | Webhook delivery / encryption | yes | template only; not a runtime default | conditional; provision privately |
| `backend/notification-integration-service/Dockerfile` | `GIT_SHA` | Service feature / integration configuration | no | none (required reference) | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `DB_PASSWORD` | Service datasource | yes | none (required reference) | required; service-specific |
| `backend/notification-integration-service/src/main/resources/application.yml` | `DB_URL` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/notification-integration-service/src/main/resources/application.yml` | `DB_USER` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/notification-integration-service/src/main/resources/application.yml` | `IDENTITY_BASE_URL` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `KAFKA_CONSUMER_GROUP` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATIONS_CONSUMER_ENABLED` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_HEALTH_FAILED_WINDOW_MINUTES` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_HEALTH_STUCK_PENDING_MINUTES` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_MAX_ATTEMPTS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_MOCK_FAIL_MODE` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_RETRY_BASE_DELAY_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_RETRY_MAX_DELAY_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `NOTIFICATION_RETRY_POLL_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOKS_ALLOW_INSECURE_LOCAL` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOKS_ENABLED` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_CONNECT_TIMEOUT_MS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_CONSUMER_GROUP` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_HEALTH_FAILED_WINDOW_MINUTES` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_HEALTH_STUCK_MINUTES` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_MAX_ATTEMPTS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_READ_TIMEOUT_MS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_RETRY_BASE_DELAY_MS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_RETRY_MAX_DELAY_MS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_RETRY_POLL_MS` | Webhook delivery / encryption | no | nonempty; review for production | configured / feature-dependent |
| `backend/notification-integration-service/src/main/resources/application.yml` | `WEBHOOK_SECRET_KEY` | Webhook delivery / encryption | yes | empty | conditional; provision privately |
| `backend/parcel-pricing-service/.env.example` | `DB_PASSWORD` | Service datasource | yes | template only; not a runtime default | required; service-specific |
| `backend/parcel-pricing-service/.env.example` | `DB_URL` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/parcel-pricing-service/.env.example` | `DB_USER` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/parcel-pricing-service/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_BATCH_SIZE` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_MAX_ATTEMPTS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_POLL_INTERVAL_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_PUBLISHER_ENABLED` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_RETRY_BASE_DELAY_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_RETRY_MAX_DELAY_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_SEND_TIMEOUT_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_STUCK_THRESHOLD_MS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_TOPIC_PARTITIONS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `OUTBOX_TOPIC_REPLICAS` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/.env.example` | `SERVER_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/parcel-pricing-service/Dockerfile` | `GIT_SHA` | Service feature / integration configuration | no | none (required reference) | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `DB_PASSWORD` | Service datasource | yes | none (required reference) | required; service-specific |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `DB_URL` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `DB_USER` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `KAFKA_DELIVERY_TIMEOUT_MS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `KAFKA_MAX_BLOCK_MS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `KAFKA_REQUEST_TIMEOUT_MS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_BATCH_SIZE` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_MAX_ATTEMPTS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_POLL_INTERVAL_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_PUBLISHER_ENABLED` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_RETRY_BASE_DELAY_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_RETRY_MAX_DELAY_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_SEND_TIMEOUT_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_STUCK_THRESHOLD_MS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_TOPIC_PARTITIONS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `OUTBOX_TOPIC_REPLICAS` | Service feature / integration configuration | no | nonempty; review for production | configured / feature-dependent |
| `backend/parcel-pricing-service/src/main/resources/application.yml` | `SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `backend/tracking-reporting-service/.env.example` | `DB_PASSWORD` | Service datasource | yes | template only; not a runtime default | required; service-specific |
| `backend/tracking-reporting-service/.env.example` | `DB_URL` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/tracking-reporting-service/.env.example` | `DB_USER` | Service datasource | no | template only; not a runtime default | required; service-specific |
| `backend/tracking-reporting-service/.env.example` | `JWT_AUDIENCE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/tracking-reporting-service/.env.example` | `JWT_ISSUER_URI` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `backend/tracking-reporting-service/.env.example` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `backend/tracking-reporting-service/.env.example` | `KAFKA_CONSUMER_ENABLED` | Broker connection / consumer | no | template only; not a runtime default | configured / feature-dependent |
| `backend/tracking-reporting-service/.env.example` | `SERVER_PORT` | Listener / development binding | no | template only; not a runtime default | configured / feature-dependent |
| `backend/tracking-reporting-service/Dockerfile` | `GIT_SHA` | Service feature / integration configuration | no | none (required reference) | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `DB_PASSWORD` | Service datasource | yes | none (required reference) | required; service-specific |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `DB_URL` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `DB_USER` | Service datasource | no | none (required reference) | required; service-specific |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `JWT_AUDIENCE` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `JWT_ISSUER_URI` | Identity provider / token validation | no | none (required reference) | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `JWT_JWKS_URI` | Identity provider / token validation | no | empty | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `KAFKA_BOOTSTRAP_SERVERS` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `KAFKA_CONSUMER_ENABLED` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `KAFKA_CONSUMER_GROUP` | Broker connection / consumer | no | nonempty; review for production | configured / feature-dependent |
| `backend/tracking-reporting-service/src/main/resources/application.yml` | `SERVER_PORT` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `docker-compose.yml` | `BIND_ADDRESS` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `frontend/zentro-web/.env.example` | `VITE_API_BASE_URL` | Public frontend build configuration | no | template only; not a runtime default | public only; configure per deployment |
| `frontend/zentro-web/.env.example` | `VITE_KEYCLOAK_CLIENT_ID` | Identity provider / token validation | no | template only; not a runtime default | public only; configure per deployment |
| `frontend/zentro-web/.env.example` | `VITE_KEYCLOAK_REALM` | Identity provider / token validation | no | template only; not a runtime default | public only; configure per deployment |
| `frontend/zentro-web/.env.example` | `VITE_KEYCLOAK_URL` | Identity provider / token validation | no | template only; not a runtime default | public only; configure per deployment |
| `infra/keycloak/.env.example` | `GOOGLE_CLIENT_ID` | Service feature / integration configuration | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `GOOGLE_CLIENT_SECRET` | Service feature / integration configuration | yes | template only; not a runtime default | conditional; provision privately |
| `infra/keycloak/.env.example` | `KC_BOOTSTRAP_ADMIN_PASSWORD` | Identity provider / token validation | yes | template only; not a runtime default | conditional; provision privately |
| `infra/keycloak/.env.example` | `KC_BOOTSTRAP_ADMIN_USERNAME` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_DB` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_DB_PASSWORD` | Identity provider / token validation | yes | template only; not a runtime default | conditional; provision privately |
| `infra/keycloak/.env.example` | `KC_DB_POOL_MAX_SIZE` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_DB_SCHEMA` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_DB_URL` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_DB_USERNAME` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KC_HTTP_PORT` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KEYCLOAK_REALM` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `KEYCLOAK_URL` | Identity provider / token validation | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `SMTP_FROM` | Mail provider | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `SMTP_HOST` | Mail provider | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `SMTP_PASSWORD` | Mail provider | yes | template only; not a runtime default | conditional; provision privately |
| `infra/keycloak/.env.example` | `SMTP_PORT` | Mail provider | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/.env.example` | `SMTP_USER` | Mail provider | no | template only; not a runtime default | configured / feature-dependent |
| `infra/keycloak/docker-compose.keycloak.yml` | `BIND_ADDRESS` | Listener / development binding | no | nonempty; review for production | configured / feature-dependent |
| `infra/keycloak/docker-compose.keycloak.yml` | `KC_BOOTSTRAP_ADMIN_PASSWORD` | Identity provider / token validation | yes | none (required reference) | conditional; provision privately |

Additional runtime controls (not credentials): `SPRING_PROFILES_ACTIVE` selects profiles and must include prod where the health-only policy is required; `JAVA_OPTS` and Keycloak's `JAVA_OPTS_APPEND` control JVM tuning. Keycloak Compose sets `KC_HTTP_ENABLED`, `KC_HEALTH_ENABLED`, `KC_SPI_THEME_CACHE_THEMES`, `KC_SPI_THEME_CACHE_TEMPLATES`, and `KC_SPI_THEME_STATIC_MAX_AGE` for development only; production values require owner review. `GIT_SHA` is public build metadata. Keycloak DB and mail/provider names in its environment example are not automatically wired to every Compose setting; configure the intended runtime explicitly.

S1 merchant API keys are stored as SHA-256 digests (`MerchantApiKeyService`); no hashing pepper configuration was found. Kafka credentials/TLS are not supplied by the committed application defaults. Docker runtime images use non-root users and multistage builds, while version tags do not guarantee immutable base images. CI uses version-pinned actions in places, a SHA-pinned github-script, and no pull_request_target workflow was found; repository settings and the contents of externally generated artifacts remain owner checks.
