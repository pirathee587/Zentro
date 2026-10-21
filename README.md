# Zentro

Zentro is the SwiftDrop parcel delivery and courier-management platform. It is a Java/Spring Boot microservice backend with a React web application.

## Phase 1 — repository foundation

This phase provides the Maven reactor, service and infrastructure directory layout, shared API contracts, environment templates, and the `zentro-security-starter` library. The starter validates OAuth2 JWT issuer/signature/expiry through Spring Security, validates the configured audience, maps Keycloak realm roles to Spring authorities, and supplies RFC 7807 authentication/authorization error responses.

### Local configuration

Copy `.env.common.example` to `.env.common`. Each backend service contains an `.env.example`; copy it to `.env` before running that service. Do not commit either generated environment file.

### Verification

Phase 1 is verified with `mvn -f backend/pom.xml verify`. This requires JDK 21 and Maven 3.9+.

## Planned phases

Database migrations, container orchestration, business endpoints, and the frontend are deliberately out of scope until their respective requested phases.

## Frontend foundation

`frontend/zentro-web` uses a portal boundary: public, customer, merchant, courier, and admin portals are lazy-loaded by `src/app/router.tsx`. Shared code may not depend on portals or app code, and portals may not depend on one another. ESLint enforces those boundaries and prohibits raw form controls outside `src/shared/ui`.

The token palette lives exclusively in `src/styles/tokens.css`, with Tailwind token mappings in `tailwind.config.ts`. Parcel state is always presented through `StatusBadge`. All portal screens are routing placeholders only; authentication and business API flows are intentionally deferred.

To run after installing Node.js 22+:

```sh
cd frontend/zentro-web
cp .env.example .env
npm install
npm run dev
```

Quality commands: `npm run lint`, `npm run type-check`, `npm test`, and `npm run build`. The production image listens on port 8080 and can be built with `docker build -t zentro-web .`.

## Docker

Each backend deployable has a multi-stage Dockerfile that builds from the `backend/` context and runs as UID `1001`. For example:

```sh
docker build -f backend/api-gateway/Dockerfile -t zentro/api-gateway:local --build-arg GIT_SHA=local backend
```

Images expose their service HTTP port (and gRPC port where applicable), use `/actuator/health/liveness` for health checks, and accept runtime configuration only through environment variables. Compose orchestration is intentionally not included yet.
