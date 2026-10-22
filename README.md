
# 🚚 Zentro — Parcel Delivery & Courier Platform

<div align="center">

![Zentro](https://img.shields.io/badge/Zentro-Parcel%20Delivery-E5B779?style=for-the-badge&labelColor=232323)
![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![Java](https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Keycloak](https://img.shields.io/badge/Keycloak-4D4D4D?style=for-the-badge&logo=keycloak&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-326CE5?style=for-the-badge&logo=kubernetes&logoColor=white)

**Customers book and track parcels, merchants send orders through an API, dispatchers assign couriers, and couriers update delivery status in real time.**

</div>

## Project overview

Zentro manages the full parcel delivery workflow: booking, fee estimation, dispatch, courier delivery, live tracking, notifications, merchant webhooks, and receipts. It is built as a microservices system with separate databases, event-driven communication through Kafka, and role-based access through Keycloak.

| Role | What they do |
|---|---|
| **Customer** | Estimates fees, books parcels, tracks their own parcels live, downloads receipts |
| **Merchant** | Registers a business (after approval), creates orders through the API, manages API clients and webhooks |
| **Courier** | Sees today's deliveries, moves each parcel to the next allowed status, uploads proof of delivery |
| **Dispatcher** | Assigns parcels to couriers, approves merchants, manages zones and pricing, views reports and audit logs |

Anyone can track a parcel with its tracking number on the public tracking page, without logging in.

## Architecture

```text
Browser (HTTPS)
   |
   v
Nginx (frontend container, TLS in deployment)
   |
   v
Spring Cloud API Gateway :8080
   |  - JWT validation (issuer, audience, signature, expiry)
   |  - rate limiting, CORS, correlation id
   |
   +-- S1 Identity & Partner      :8081  (gRPC :9081)  -> identity_db
   +-- S2 Parcel & Pricing        :8082  (gRPC :9082)  -> parcel_pricing_db
   +-- S3 Dispatch & Courier      :8083  (gRPC :9083)  -> dispatch_courier_db
   +-- S4 Tracking & Reporting    :8084                -> tracking_reporting_db
   +-- S5 Notification & Integration :8085             -> notification_integration_db

Keycloak (login, roles, Google sign-in) ---- validates identity for the gateway and services

Kafka topics: parcel.created · delivery.assigned · parcel.status.updated · delivery.failed (+ .DLT)
```

**Communication**

- **REST** (`/api/v1/...`): commands and client CRUD, through the gateway.
- **gRPC**: three internal synchronous calls (S2 → S1, S3 → S1, S3 → S2).
- **Kafka**: asynchronous events, published through the transactional outbox pattern and consumed idempotently.
- **SSE**: live tracking updates pushed to the browser.

## Tech stack

| Area | Technologies |
|---|---|
| Frontend | React 18, TypeScript (strict), Vite, Tailwind CSS, React Router, TanStack Query, react-oidc-context |
| Gateway & security | Spring Cloud Gateway, Spring Security (OAuth2 Resource Server), Keycloak, Bucket4j |
| Services | Java 21, Spring Boot 3, Spring Data JPA, Flyway, MapStruct, gRPC, OpenPDF |
| Messaging | Apache Kafka (KRaft), transactional outbox, dead-letter topics |
| Data | PostgreSQL, one database per service (Supabase when deployed) |
| Testing | JUnit 5, Mockito, Testcontainers, JaCoCo, Vitest, React Testing Library, MSW |
| Delivery & operations | Docker, docker-compose, GitHub Actions, Trivy, Kubernetes, Helm, Prometheus, Grafana, OpenTelemetry |

## Microservices

| Service | Port | Database | Responsibility |
|---|---|---|---|
| API Gateway | 8080 | — | Single entry point, JWT check, routing, rate limiting |
| S1 Identity & Partner | 8081 / 9081 | `identity_db` | Users, roles, merchant approval, API clients, courier profiles |
| S2 Parcel & Pricing | 8082 / 9082 | `parcel_pricing_db` | Zones, pricing rules, fee estimate, booking, merchant orders, receipts |
| S3 Dispatch & Courier | 8083 / 9083 | `dispatch_courier_db` | Unassigned parcels, assignment, workload, courier status updates, proof of delivery |
| S4 Tracking & Reporting | 8084 | `tracking_reporting_db` | Status history, public tracking, SSE live updates, daily reports |
| S5 Notification & Integration | 8085 | `notification_integration_db` | Email/SMS notifications, signed merchant webhooks with retry, audit log |

## Parcel lifecycle

```text
CREATED ──► PICKED_UP ──► IN_TRANSIT ──► OUT_FOR_DELIVERY ──► DELIVERED
                                                          └──► FAILED
```

- Each step is allowed only from the step before it.
- `FAILED` requires a reason. `DELIVERED` can include the recipient name, a note, and a photo.
- Every change is stored in status history and published as an event.

## Event flow (example: courier update)

1. Courier marks a parcel `IN_TRANSIT` in S3. S3 saves the change and an outbox row in one transaction.
2. The relay publishes `parcel.status.updated` to Kafka.
3. S2 updates the parcel status, S4 stores history and pushes an SSE update, S5 sends notifications and merchant webhooks.
4. The customer's open tracking page shows "In transit" without a refresh.

## Project structure

```text
zentro/
├── backend/
│   ├── pom.xml                              parent POM
│   ├── libs/zentro-security-starter/        shared JWT validation and authorization
│   ├── api-gateway/
│   ├── identity-partner-service/
│   ├── parcel-pricing-service/
│   ├── dispatch-courier-service/
│   ├── tracking-reporting-service/
│   └── notification-integration-service/
├── frontend/
│   └── zentro-web/
│       └── src/
│           ├── app/          router and providers
│           ├── portals/      public, customer, merchant, courier, admin
│           ├── shared/       ui, domain, auth, api, config, testing
│           └── styles/       design tokens
├── contracts/
│   ├── proto/                gRPC definitions
│   └── events/               Kafka event schemas
├── infra/
│   ├── keycloak/             realm export
│   ├── k8s/                  Kubernetes manifests
│   ├── helm/zentro/          Helm chart
│   └── observability/        Prometheus and Grafana
├── docs/                     architecture, API specs, test reports, team handbook
├── scripts/                  local setup helpers
├── docker-compose.yml
└── .github/workflows/        CI and CD pipelines
```

## Getting started

### Requirements

- JDK 21 and Maven 3.9
- Node.js 22
- Docker Desktop with Docker Compose v2
- Git

### Setup

```bash
git clone <repository-url> zentro
cd zentro
git checkout develop

bash scripts/init-local.sh        # creates .env files with generated local passwords
docker compose config             # validates the compose file
docker compose up -d --build      # starts PostgreSQL x5, Kafka, Keycloak, services, gateway, frontend
docker compose ps                 # all containers should be healthy
```

Environment rules:

- Only `.env.example` files are committed. Real `.env` files are git-ignored.
- `.env.common` holds shared values: `KAFKA_BOOTSTRAP`, `JWT_ISSUER_URI`, `JWT_AUDIENCE`.
- Each service has its own `.env` with `DB_URL`, `DB_USER`, `DB_PASSWORD`. A service receives only its own database credentials.

### Open the application

| Component | URL |
|---|---|
| Frontend | <http://localhost:3000> |
| API Gateway | <http://localhost:8080> |
| Keycloak | <http://localhost:8180> |

### Frontend development mode

```bash
cd frontend/zentro-web
cp .env.example .env
npm install
npm run dev                       # http://localhost:5173
```

### Run one backend service

```bash
cd backend
mvn -q -pl parcel-pricing-service -am spring-boot:run
```

## Development accounts

Local development users exist in the Keycloak realm `zentro`, one per role: `customer`, `merchant`, `courier`, `dispatcher`.

Passwords are not stored in this repository. They are provided in the local setup notes for the team.

> ⚠️ These accounts are for local development only. Disable or replace them before any public deployment.

## Application routes

### Public

| Page | Route |
|---|---|
| Tracking search | `/track` |
| Live tracking | `/track/{trackingNumber}` |
| Login / Signup | `/login`, `/signup` |

### Customer

| Page | Route |
|---|---|
| Home | `/customer` |
| Create delivery (with fee estimate) | `/customer/parcels/new` |
| My parcels | `/customer/parcels` |
| Parcel detail (live status) | `/customer/parcels/{id}` |
| Receipt | `/customer/parcels/{id}/receipt` |

### Merchant

| Page | Route |
|---|---|
| Register business | `/merchant/register` |
| Orders dashboard | `/merchant` |
| API clients | `/merchant/api-clients` |
| Webhooks and delivery log | `/merchant/webhooks` |

### Courier

| Page | Route |
|---|---|
| Today's deliveries | `/courier` |
| Delivery detail and status update | `/courier/deliveries/{id}` |

### Dispatcher

| Page | Route |
|---|---|
| Unassigned parcels | `/admin/unassigned` |
| Courier workload | `/admin/workload` |
| Merchant approvals | `/admin/merchant-approvals` |
| Users | `/admin/users` |
| Zones and pricing | `/admin/zones-pricing` |
| Reports | `/admin/reports` |

## API overview

All API paths start with `/api/v1`. Errors use RFC 7807 Problem Details.

| Area | Example endpoints | Roles |
|---|---|---|
| Users & merchants | `GET /users/me`, `POST /merchants`, `GET /merchants/me` | any logged-in user |
| Approvals | `PATCH /admin/merchants/{id}/approve` | DISPATCHER |
| Parcels | `POST /parcels/estimate`, `POST /parcels`, `GET /parcels/mine` | CUSTOMER |
| Merchant orders | `POST /merchant/orders` (idempotent) | MERCHANT |
| Dispatch | `GET /dispatch/unassigned`, `POST /dispatch/assignments` | DISPATCHER |
| Courier | `GET /courier/deliveries/today`, `PATCH /courier/deliveries/{id}/status` | COURIER |
| Tracking | `GET /tracking/{trackingNumber}`, `GET /tracking/{trackingNumber}/stream` | public |
| Reports | `GET /reports/daily`, `GET /reports/summary` | DISPATCHER |
| Webhooks | `POST /merchant/webhooks`, `GET /merchant/webhooks/deliveries` | MERCHANT |

## Database

- Five PostgreSQL databases, one per service. A service never reads another service's tables.
- Schema changes are made only through Flyway migrations (`V1__`, `V2__`, ...). Existing migrations are never edited.
- Locally, each database runs in its own container. For deployment, each one is a separate Supabase project.
- Hibernate runs in `validate` mode, so the entities must match the schema.

## Verification

| Scenario | Expected result |
|---|---|
| Customer books a parcel | Parcel appears in the dispatcher's unassigned list |
| Dispatcher assigns a courier | Courier sees the delivery; tracking shows the assignment |
| Courier updates status | Public tracking page updates live; customer sees the new status |
| Courier tries to update another courier's delivery | 403 Forbidden |
| Merchant sends the same `external_order_ref` twice | Only one parcel is created |
| Event consumed twice | Processed once (idempotent consumer) |
| Webhook endpoint returns an error | Delivery is retried with backoff |

## Testing

```bash
# backend: unit, security and integration tests with coverage
mvn -B -pl <service> -am verify

# frontend: lint, types, tests and production build
cd frontend/zentro-web
npm run lint && npm run typecheck && npm test && npm run build
```

Targets: at least 70% line coverage on service and security layers. Authorization tests are required for every protected endpoint.

## Useful commands

```bash
docker compose ps
docker compose logs --tail=200 parcel-pricing-service
docker compose up -d --build <service>
docker compose stop
docker compose down                # keeps data volumes
docker compose down --volumes      # also deletes local database volumes
```

After rebuilding the frontend, use a hard refresh (`Ctrl + Shift + R`) to clear cached files.

## Security

- JWTs are validated at the gateway and again in every service.
- Tokens are kept in memory in the browser, never in `localStorage`.
- Merchant webhooks are signed with HMAC-SHA256 and must use HTTPS. Private and loopback addresses are blocked.
- Tracking numbers are random and non-guessable. The public tracking response contains no personal data.
- Passwords are never stored in our databases; Keycloak manages credentials.

## Contributing

1. Branch from `develop`: `feature/<area>-<task>`.
2. Commit with Conventional Commits, for example `feat(parcel): add fee estimate`.
3. Open a Pull Request to `develop`. At least one reviewer must approve.
4. Never commit `.env` files, passwords, keys, `target/` or `node_modules/`.
5. Keep changes inside your own module. Changes to `contracts/` must be agreed with the security and gateway owner.

## Documentation

- [Team handbook](docs/team-handbook.md): roles, rules, Kafka and database guide, frontend style guide
- [System architecture](docs/architecture/)
- [API specifications](docs/api/)
- [Test reports](docs/test-reports/)

<div align="center">

### Zentro

**Smarter parcel delivery, from booking to doorstep.**

</div>
