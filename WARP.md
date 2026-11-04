# WARP.md
# SwiftDrop — AI Engineering & Development Guidelines

**Architecture Baseline:** v1.3  
**Project Type:** Parcel Delivery Management Platform  
**Architecture:** Domain-Oriented Microservices  
**Frontend:** React + TypeScript  
**Backend:** Spring Boot Microservices  
**Communication:** REST + GraphQL + gRPC + Apache Kafka

---

# 1. PROJECT OVERVIEW

## 1.1 Project Description

SwiftDrop is a parcel delivery management platform connecting customers, merchants, couriers and dispatchers.

The platform provides:

- Parcel booking and management.
- Merchant order management.
- Courier assignment and delivery scheduling.
- Delivery tracking and reporting.
- Pricing and fee calculation.
- Notification and external integrations.
- Administrative and operational management.
- AI-powered delivery assistance.

## 1.2 Technology Stack

### Frontend
- React
- TypeScript
- Vite
- Tailwind CSS
- React Router
- TanStack Query

### Backend
- Java
- Spring Boot
- Spring Cloud API Gateway
- Spring Security

### Communication
- REST APIs
- GraphQL (read-only)
- gRPC (synchronous internal communication)
- Apache Kafka (asynchronous events)

### Database
- PostgreSQL
- Database-per-Service architecture

### Authentication and Security
- Keycloak
- Google OpenID Connect
- OAuth 2.0
- JWT

### Infrastructure
- Docker
- Docker Compose
- Kubernetes
- Helm
- NGINX
- GitHub Actions
- OpenTelemetry
- Prometheus
- Grafana

### AI
- Spring AI
- Spring AI ChatClient
- MCP Server

---

# 2. USER ROLES AND RESPONSIBILITIES

SwiftDrop has FOUR primary user roles.

IMPORTANT: There is NO separate Admin role. All administrative and operational management responsibilities are assigned to the Dispatcher role.

## 2.1 Customer

Responsibilities:

- Register and authenticate.
- Manage personal profile.
- Create parcel bookings.
- View parcel details.
- Calculate delivery fees.
- Track parcels.
- View delivery history.
- Receive notifications.
- Submit relevant delivery feedback.

Access restrictions:

- Can access only their own customer information.
- Can view only parcels and deliveries they are authorized to access.
- Cannot manage merchants or couriers.
- Cannot assign deliveries.
- Cannot access administrative functions.

## 2.2 Merchant

Responsibilities:

- Manage merchant profile.
- Create and manage orders.
- Submit parcel booking requests.
- View parcel and delivery information.
- Track merchant deliveries.
- Receive delivery updates.
- Manage authorized merchant webhook integrations where supported.

Access restrictions:

- Can access only their own merchant account and authorized orders.
- Cannot access other merchants' private information.
- Cannot assign couriers.
- Cannot access dispatcher-only administrative operations.

## 2.3 Courier

Responsibilities:

- Manage courier profile.
- View assigned deliveries.
- Receive delivery assignments.
- Accept or reject assignments according to business rules.
- Update delivery progress.
- Submit delivery confirmation.
- Upload proof-of-delivery information where supported.
- Receive relevant notifications.

Access restrictions:

- Can access only their own courier profile.
- Can view deliveries assigned to them.
- Cannot assign deliveries to other couriers.
- Cannot manage platform-wide operations.
- Cannot modify another courier's delivery records.

## 2.4 Dispatcher (Operational + Administrative Responsibilities)

The Dispatcher is the primary operational management role and also performs administrative responsibilities.

There is NO separate Admin user role.

### A. Dispatch Management

- View incoming parcel and delivery requests.
- Assign couriers to deliveries.
- Reassign deliveries when necessary.
- Manage delivery scheduling.
- Monitor courier availability.
- Monitor courier workloads.
- Handle assignment conflicts.
- Manage delivery exceptions.
- Coordinate failed and delayed deliveries.

### B. Platform Administration

- Monitor overall platform operations.
- Manage user and partner operations.
- Review merchant and courier profiles.
- Process partner approval workflows.
- Manage operational configurations within authorized permissions.
- Monitor service-level operational information.
- Access administrative dashboards.
- Review relevant audit logs.

### C. Tracking and Monitoring

- Monitor delivery progress.
- View delivery status across the platform.
- Investigate delayed deliveries.
- Monitor failed deliveries.
- Review tracking and reporting information.
- Coordinate operational recovery actions.

### D. Operational Oversight

- Monitor notification failures.
- Review relevant integration failures.
- Coordinate webhook and notification issue resolution.
- Monitor operational reports.
- Escalate incidents when necessary.

### Dispatcher Security Restrictions

Although the Dispatcher has administrative responsibilities:

- All actions must be authorized by backend services.
- Administrative operations must be permission-controlled.
- Sensitive operations must be audited.
- Dispatchers must not access passwords, authentication secrets or private credentials.
- Dispatchers must not directly access microservice databases.
- Access to personal information must follow least-privilege principles.

### Important Role Design Rule

Use one primary role:

`DISPATCHER`

Do not introduce a separate:

`ADMIN`

The Dispatcher role combines operational and administrative responsibilities.

If different levels of administrative access become necessary, introduce permission-based authorization within the Dispatcher role rather than creating an unnecessary fifth primary role.

Example permissions:

- `DISPATCH_VIEW`
- `DISPATCH_ASSIGN`
- `DISPATCH_REASSIGN`
- `COURIER_MANAGE`
- `MERCHANT_APPROVE`
- `USER_MANAGE`
- `PLATFORM_MONITOR`
- `REPORT_VIEW`
- `AUDIT_VIEW`

Permissions must be enforced on the backend.

---

# 3. ARCHITECTURAL PRINCIPLES

All development MUST follow the established SwiftDrop architecture.

Mandatory principles:

1. SOLID principles.
2. DRY (Don't Repeat Yourself).
3. KISS (Keep It Simple).
4. Separation of Concerns.
5. High Cohesion and Low Coupling.
6. Domain-Driven Design where appropriate.
7. Database-per-Service.
8. API Gateway pattern.
9. Event-Driven Architecture.
10. Transactional Outbox pattern.
11. Secure by Design.
12. Observability by Default.

Do not introduce unnecessary complexity or architectural patterns.

Do not change established architectural decisions without justification and approval.

---

# 4. SYSTEM ARCHITECTURE

SwiftDrop consists of nine architectural layers:

1. Client / User Layer.
2. Frontend Layer.
3. Security and Edge Layer.
4. Hybrid API Layer.
5. Core Business Microservices Layer.
6. Asynchronous Event Streaming Layer.
7. AI Delivery Assistant Layer.
8. External Integrations Layer.
9. Deployment and Operations Layer.

Maintain these architectural boundaries.

---

# 5. MICROSERVICE ARCHITECTURE

SwiftDrop contains five core business microservices.

Each service owns its business logic, database and persistence responsibilities.

## 5.1 S1 — Identity & Partner Service

Responsibilities:

- User and role management.
- Customer identity-related operations.
- Merchant profiles.
- Courier profiles.
- Partner approvals.
- Role and permission management.

Database:

`identity_db`

Rules:

- Maintain identity and partner business logic inside S1.
- Integrate with Keycloak appropriately.
- Enforce user and partner authorization.
- Maintain the Dispatcher role and its authorized permissions.
- Avoid duplicating identity logic across services.
- Never store plaintext passwords.

Important:

The Dispatcher is the combined operational and administrative role. Do not implement a separate Admin role.

## 5.2 S2 — Parcel & Pricing Service

Responsibilities:

- Parcel booking.
- Parcel details.
- Pricing rules.
- Fee estimation.

Database:

`parcel_pricing_db`

Rules:

- Keep parcel booking and pricing logic inside S2.
- Validate parcel information.
- Maintain consistent fee calculations.
- Avoid duplicating business pricing rules in the frontend.
- Publish relevant business events.

## 5.3 S3 — Dispatch & Courier Service

Responsibilities:

- Courier assignment.
- Delivery scheduling.
- Workload management.
- Delivery progress coordination.
- Reassignment and dispatch operations.

Database:

`dispatch_courier_db`

Rules:

- Maintain dispatch and courier assignment logic inside S3.
- Validate courier availability.
- Prevent conflicting assignments.
- Enforce Dispatcher permissions.
- Allow authorized Dispatchers to manage operational assignments.
- Use gRPC for approved synchronous internal communication.
- Publish relevant asynchronous events.

## 5.4 S4 — Tracking & Reporting Service

Responsibilities:

- Tracking history.
- Read models.
- Reports and analytics.
- SSE / WebSocket live updates.

Database:

`tracking_reporting_db`

Rules:

- Maintain tracking and reporting data independently.
- Consume relevant Kafka events.
- Build read models where appropriate.
- Support efficient tracking queries.
- Never access another service's database directly.
- Provide authorized live delivery updates.

## 5.5 S5 — Notification & Integration Service

Responsibilities:

- Email notifications.
- SMS notifications.
- Merchant webhooks.
- Retry and failure handling.
- Audit logs.

Database:

`notification_integration_db`

Rules:

- Consume relevant Kafka events.
- Handle notification failures gracefully.
- Implement retry policies with appropriate backoff.
- Support idempotent processing.
- Secure outbound webhook communication.
- Maintain relevant audit information.
- Never expose provider credentials.

---

# 6. MICROSERVICE CODING STANDARDS

Recommended Spring Boot package structure:

    src/main/java/com/swiftdrop/service/
    |
    +-- controller/
    +-- service/
    +-- repository/
    +-- entity/
    +-- dto/
    |   +-- request/
    |   +-- response/
    +-- mapper/
    +-- config/
    +-- exception/
    +-- validation/
    +-- client/
    +-- event/
    +-- security/

Adapt the structure according to actual service requirements.

## Controller

- Handle HTTP requests.
- Validate incoming data.
- Delegate business logic to services.
- Return appropriate HTTP responses.
- Never contain complex business logic.

## Service

- Implement business rules.
- Coordinate workflows.
- Manage transaction boundaries.
- Enforce application-level rules.

## Repository

- Handle persistence.
- Execute database queries.
- Avoid business logic.

## DTO

- Define API request and response contracts.
- Never expose JPA entities directly through public APIs.
- Validate incoming request data.

## Exception Handling

- Use centralized exception handling.
- Use `@RestControllerAdvice` where appropriate.
- Return consistent error responses.
- Never expose internal stack traces to clients.

---

# 7. COMMUNICATION STANDARDS

SwiftDrop uses REST, GraphQL, gRPC and Kafka.

## 7.1 REST APIs

Used for:

- Parcel booking.
- Fee quotation.
- Dispatch actions.
- Tracking requests.
- External integrations.
- Frontend commands.

Rules:

- Follow REST conventions.
- Use appropriate HTTP methods and status codes.
- Validate request payloads.
- Maintain consistent response structures.
- Document APIs using OpenAPI where appropriate.
- Consider backward compatibility.

## 7.2 GraphQL

GraphQL is READ-ONLY.

Used for:

- Dashboard aggregation.
- Cross-service read models.
- Complex read operations.

Rules:

- Do not implement GraphQL mutations for business commands.
- Do not bypass service ownership.
- Apply authorization to protected queries.
- Prevent N+1 query problems.
- Apply query depth and complexity limits where appropriate.

All business commands must use approved REST or other explicitly defined command integrations.

## 7.3 gRPC

Used for synchronous internal service communication.

Rules:

- Define contracts using Protocol Buffers.
- Version `.proto` files carefully.
- Maintain backward compatibility where possible.
- Configure deadlines and timeouts.
- Handle service unavailability.
- Avoid infinite retries.
- Propagate tracing context.

## 7.4 Apache Kafka

Used for asynchronous event-driven communication.

Example topics:

- `parcel.created`
- `delivery.assigned`
- `parcel.status.updated`
- `delivery.failed`

Rules:

- Use events to communicate business facts.
- Define event schemas.
- Include event identifiers and metadata.
- Implement idempotent consumers.
- Handle duplicate events.
- Configure dead-letter handling.
- Avoid publishing unnecessary sensitive information.

---

# 8. TRANSACTIONAL OUTBOX PATTERN

Use the Transactional Outbox pattern for reliable event publishing.

Expected workflow:

1. Receive a business command.
2. Validate the request.
3. Execute the business operation.
4. Persist business data.
5. Save the corresponding outbox event in the same transaction.
6. Commit the database transaction.
7. Publish pending outbox events to Kafka.
8. Mark successfully published events as processed.

Mandatory rules:

- Business changes and outbox records must be committed atomically.
- Never publish an event before the business transaction commits.
- Implement retries.
- Handle duplicate publishing.
- Ensure consumer idempotency.
- Preserve ordering where required.

Do not replace the Outbox pattern with direct database-plus-Kafka publishing without approval.

---

# 9. DATABASE OWNERSHIP

SwiftDrop follows Database-per-Service.

| Service | Database |
|---|---|
| S1 Identity & Partner | identity_db |
| S2 Parcel & Pricing | parcel_pricing_db |
| S3 Dispatch & Courier | dispatch_courier_db |
| S4 Tracking & Reporting | tracking_reporting_db |
| S5 Notification & Integration | notification_integration_db |

Rules:

- Each microservice owns its database.
- No direct cross-service database queries.
- Use approved APIs or events for cross-service information.
- Maintain independent migrations.
- Use transactions within the owning service.
- Avoid distributed database transactions.
- Never share persistence entities across services.

---

# 10. AUTHENTICATION AND AUTHORIZATION

Use Keycloak as the identity provider.

Support configured Google OpenID Connect authentication.

## Primary Roles

- `CUSTOMER`
- `MERCHANT`
- `COURIER`
- `DISPATCHER`

There is NO separate `ADMIN` role.

## Dispatcher Permissions

The Dispatcher has operational and administrative responsibilities.

Examples:

- `DISPATCH_VIEW`
- `DISPATCH_ASSIGN`
- `DISPATCH_REASSIGN`
- `COURIER_MANAGE`
- `MERCHANT_APPROVE`
- `USER_MANAGE`
- `PLATFORM_MONITOR`
- `REPORT_VIEW`
- `AUDIT_VIEW`

Rules:

- Authenticate users through approved identity flows.
- Validate JWT signatures and claims.
- Enforce backend authorization.
- Validate resource ownership.
- Protect administrative operations.
- Apply least-privilege principles.
- Audit sensitive Dispatcher actions.
- Never rely solely on frontend role checks.

Do not create a fifth primary user role for Admin.

---

# 11. API GATEWAY AND SECURITY

The Spring Cloud API Gateway is the central backend entry point.

Responsibilities:

- Request routing.
- JWT validation.
- Rate limiting.
- Request logging.
- Authentication integration.

Rules:

- Do not expose internal service ports unnecessarily.
- Maintain service-level authorization.
- Avoid logging tokens.
- Apply appropriate rate limits.
- Preserve tracing headers.

NGINX handles edge HTTPS and TLS termination.

Security requirements:

- Use HTTPS in production.
- Validate external input.
- Protect against SQL injection, XSS and relevant CSRF risks.
- Never hardcode secrets.
- Never commit production credentials.
- Use environment variables or an approved secret manager.
- Never store passwords in plaintext.

---

# 12. FRONTEND DEVELOPMENT STANDARDS

Technology: React + TypeScript + Vite.

Recommended structure:

    frontend/
    |
    +-- src/
        |
        +-- app/
        +-- assets/
        +-- components/
        +-- features/
        |   +-- auth/
        |   +-- customer/
        |   +-- merchant/
        |   +-- courier/
        |   +-- dispatcher/
        |   +-- parcels/
        |   +-- tracking/
        +-- hooks/
        +-- layouts/
        +-- pages/
        +-- routes/
        +-- services/
        +-- types/
        +-- utils/

## Role-Based Frontend

Create separate user experiences for:

- Customer.
- Merchant.
- Courier.
- Dispatcher.

The Dispatcher interface combines operational dashboards and administrative management functions.

There must NOT be a separate Admin dashboard or Admin user role unless the architecture is explicitly revised.

Frontend restrictions:

- Do not treat UI visibility as backend authorization.
- Do not store confidential secrets in frontend code.
- Do not bypass the API Gateway.
- Do not duplicate backend business rules.

## React and TypeScript Rules

- Use functional components.
- Prefer strict TypeScript.
- Avoid `any` unless justified.
- Use reusable components.
- Keep components focused.
- Use TanStack Query for server state where appropriate.
- Centralize API communication.
- Handle loading, error, empty and success states.
- Follow accessibility and responsive design standards.

---

# 13. AI DELIVERY ASSISTANT AND MCP

Use Spring AI ChatClient and MCP Server tools.

Example tools:

- `track_parcel`
- `calculate_delivery_fee`
- `get_my_deliveries`

Rules:

- AI tools must call authorized backend APIs.
- AI must never access service databases directly.
- Route tool calls through the approved API Gateway.
- Validate all tool arguments.
- Enforce user authorization and resource ownership.
- Never bypass backend business rules.
- Treat user-provided and external content as untrusted.
- Do not expose secrets or private system information.

The AI assistant is an interface to backend functionality, not a replacement for business logic.

---

# 14. EXTERNAL INTEGRATIONS

Potential integrations:

- Google OIDC / Keycloak.
- Email provider such as SendGrid.
- SMS provider such as Twilio.
- Merchant webhook endpoints.
- Maps and distance APIs.
- Object storage such as S3 or Supabase Storage.

Rules:

- Isolate external integrations behind adapters or client classes.
- Configure credentials securely.
- Apply timeouts.
- Implement safe retries.
- Handle external provider failures.
- Validate webhook signatures where supported.
- Use idempotency where appropriate.
- Avoid coupling core business logic to provider-specific implementations.

---

# 15. TESTING STANDARDS

## Backend

Use:

- JUnit 5.
- Mockito.
- Spring Boot Test.
- Testcontainers where appropriate.
- Spring Security Test.

Test:

- Business logic.
- Repository operations.
- REST endpoints.
- Authentication and authorization.
- Kafka producers and consumers.
- gRPC integrations.
- Failure scenarios.

## Frontend

Use:

- Vitest.
- React Testing Library.
- Playwright where end-to-end tests are required.

Test:

- Component rendering.
- User interactions.
- Form validation.
- API integration.
- Role-based UI behavior.
- Critical user workflows.

## Role-Based Authorization Testing

Explicitly test:

- Customers cannot access other customers' private data.
- Merchants cannot access other merchants' private data.
- Couriers cannot access unassigned deliveries.
- Couriers cannot perform Dispatcher operations.
- Only authorized Dispatchers can execute administrative operations.
- Unauthenticated users cannot access protected resources.

Never remove existing tests just to achieve a passing build.

---

# 16. ERROR HANDLING AND RELIABILITY

- Use consistent error responses.
- Configure timeouts.
- Apply retry policies appropriately.
- Use circuit breakers where justified.
- Handle partial service failures.
- Support idempotent operations.
- Log diagnostic information safely.
- Avoid leaking internal exceptions.
- Account for eventual consistency in asynchronous workflows.

---

# 17. OBSERVABILITY

Use:

- OpenTelemetry.
- Prometheus.
- Grafana.

Requirements:

- Structured logging.
- Correlation and trace identifiers.
- API latency metrics.
- Error rate monitoring.
- Kafka consumer lag monitoring.
- Database health monitoring.
- Service availability monitoring.
- Notification failure monitoring.

Never log passwords, tokens or secrets.

---

# 18. DEVOPS AND DEPLOYMENT

## Docker

- Use appropriate Docker images.
- Prefer multi-stage builds.
- Avoid unnecessary image layers.
- Never embed secrets in images.
- Configure health checks.

## Docker Compose

- Support local development.
- Use environment variables.
- Configure persistent volumes.
- Configure service health checks.
- Avoid production credentials in local configuration.

## Kubernetes and Helm

- Use Deployments for application workloads.
- Use Services for service discovery.
- Use ConfigMaps for non-sensitive configuration.
- Use secure Secrets management.
- Configure readiness and liveness probes.
- Define resource requests and limits.
- Apply appropriate access restrictions.

## GitHub Actions

Expected pipeline:

1. Checkout.
2. Configure runtime.
3. Install dependencies.
4. Run formatting and static analysis.
5. Run unit tests.
6. Run relevant integration tests.
7. Build frontend and backend.
8. Build container images where configured.
9. Perform dependency and security checks.
10. Publish artifacts or deploy through approved workflows.

Never bypass failed CI checks without approval.

---

# 19. CODE QUALITY AND NAMING CONVENTIONS

## Java

| Element | Convention |
|---|---|
| Class | PascalCase |
| Method | camelCase |
| Variable | camelCase |
| Constant | UPPER_SNAKE_CASE |
| Package | lowercase |

## React / TypeScript

| Element | Convention |
|---|---|
| Component | PascalCase |
| Hook | useCamelCase |
| Function | camelCase |
| Variable | camelCase |
| Constant | UPPER_SNAKE_CASE |
| Interface / Type | PascalCase |

General requirements:

- Use meaningful names.
- Keep methods focused.
- Avoid duplicated logic.
- Avoid oversized classes and components.
- Remove unused imports.
- Follow established formatting and linting configuration.
- Add comments to explain intent, not obvious code.

---

# 20. GIT AND COLLABORATION WORKFLOW

Follow the repository's established branching strategy.

Recommended branches:

- `main` — stable production branch.
- `develop` — integration branch.
- `feature/*` — new features.
- `bugfix/*` — bug fixes.
- `hotfix/*` — urgent production fixes.

Use Conventional Commits.

Examples:

- `feat(parcel): implement parcel booking`
- `feat(dispatch): implement courier assignment`
- `feat(dispatcher): add operational dashboard`
- `fix(tracking): correct delivery status updates`
- `test(identity): add role authorization tests`
- `docs(api): update API documentation`

Rules:

- Do not commit directly to protected branches.
- Do not force-push shared branches.
- Keep pull requests focused.
- Include relevant tests.
- Explain breaking changes.
- Do not overwrite other developers' changes.
- Avoid unrelated modifications.

---

# 21. DOCUMENTATION

Maintain documentation for:

- System architecture.
- Microservice responsibilities.
- API contracts.
- Database schemas.
- Kafka event contracts.
- gRPC contracts.
- Authentication and authorization.
- Deployment instructions.
- Development setup.
- Architecture Decision Records.

Recommended structure:

    docs/
    |
    +-- architecture/
    +-- api/
    +-- database/
    +-- events/
    +-- security/
    +-- deployment/
    +-- development/
    +-- decisions/

Keep documentation consistent with the actual implementation.

---

# 22. AI AGENT DEVELOPMENT WORKFLOW

Before implementing any task:

## Step 1 — Understand

- Read the task requirements.
- Inspect the existing codebase.
- Identify the affected microservice.
- Understand the current implementation.
- Review relevant documentation.

## Step 2 — Analyze

Identify:

- Affected modules.
- Database changes.
- API changes.
- Kafka event changes.
- gRPC contract changes.
- Security implications.
- Testing requirements.
- Backward compatibility risks.

## Step 3 — Plan

For complex changes:

- Explain the proposed implementation.
- Identify affected files.
- Identify dependencies.
- Explain architectural impacts.
- Request clarification for ambiguous or high-impact decisions.

## Step 4 — Implement

- Follow established architecture.
- Make focused changes.
- Maintain service boundaries.
- Avoid unnecessary dependencies.
- Write or update tests.
- Update documentation when needed.

## Step 5 — Verify

- Run relevant tests.
- Run application builds.
- Check formatting and static analysis.
- Review modified files.
- Check security and regression risks.
- Verify no unrelated files were modified.

## Step 6 — Report

Provide:

1. Implementation summary.
2. Files modified.
3. Architectural impact.
4. Tests executed.
5. Test results.
6. Remaining issues.

Never claim that tests or builds passed without actually executing them.

---

# 23. PROHIBITED AI AGENT ACTIONS

The AI agent MUST NOT:

- Introduce a separate ADMIN primary role.
- Create a separate Admin dashboard contrary to the established role model.
- Allow unauthorized users to perform Dispatcher operations.
- Modify another microservice's database directly.
- Bypass the API Gateway without approval.
- Implement GraphQL mutations for business commands.
- Remove the Transactional Outbox pattern.
- Disable authentication to bypass errors.
- Expose internal credentials.
- Commit secrets.
- Delete tests to achieve a passing build.
- Change API contracts silently.
- Perform destructive database migrations without approval.
- Rewrite entire services without justification.
- Modify unrelated modules without justification.

If a requested change conflicts with the architecture, explain the conflict before implementation.

---

# 24. DEFINITION OF DONE

A task is complete only when applicable criteria are satisfied.

## Implementation

- [ ] Requirements are implemented.
- [ ] Correct microservice owns the business logic.
- [ ] Architecture is respected.
- [ ] Service boundaries are maintained.
- [ ] Code follows project conventions.

## User Roles and Permissions

- [ ] Only the four primary roles are implemented: CUSTOMER, MERCHANT, COURIER and DISPATCHER.
- [ ] No separate ADMIN primary role is introduced.
- [ ] Dispatcher operational responsibilities are supported.
- [ ] Dispatcher administrative permissions are enforced.
- [ ] Unauthorized access is prevented.

## Database and Communication

- [ ] Database ownership is respected.
- [ ] Required migrations are included.
- [ ] API contracts are updated where necessary.
- [ ] Kafka events are handled correctly.
- [ ] Transactional Outbox consistency is maintained.

## Security

- [ ] Authentication is enforced.
- [ ] Authorization is enforced.
- [ ] Sensitive information is protected.
- [ ] Secrets are not committed.
- [ ] External input is validated.

## Testing

- [ ] Relevant unit tests are implemented.
- [ ] Relevant integration tests are implemented.
- [ ] Role-based authorization tests are included where applicable.
- [ ] Relevant builds succeed.
- [ ] Failure scenarios are considered.

## Documentation

- [ ] Relevant documentation is updated.
- [ ] Changes are reviewed.
- [ ] No unrelated modifications are introduced.
- [ ] Implementation summary is provided.
- [ ] Remaining limitations are documented.

---

# FINAL ARCHITECTURAL INSTRUCTION

Always treat SwiftDrop Architecture Baseline v1.3 as the primary architectural reference.

The four primary roles are:

1. CUSTOMER
2. MERCHANT
3. COURIER
4. DISPATCHER

The Dispatcher combines operational and administrative responsibilities.

There is NO separate ADMIN primary role.

Prioritize:

1. Business correctness.
2. Architectural consistency.
3. Security.
4. Data integrity.
5. Reliability.
6. Testability.
7. Maintainability.
8. Performance.

Build software that is secure, scalable, testable, observable and production-ready.

---

# REUSABLE CODE AND UTILITY MANAGEMENT

## 1. Reusability Principle

Whenever reusable code is identified, evaluate whether it should be extracted into an appropriate `utils/` folder.

Follow the DRY (Don't Repeat Yourself) principle.

Before implementing new functionality:
- Check whether similar logic already exists.
- Reuse existing utility functions where appropriate.
- Avoid duplicating common helper logic.
- Extract genuinely reusable logic into dedicated utility modules.

## 2. Frontend Utilities

Location:

`frontend/src/utils/`

Examples:
- `dateUtils.ts` – date formatting and date operations.
- `currencyUtils.ts` – currency formatting.
- `stringUtils.ts` – reusable string operations.
- `validationUtils.ts` – generic validation helpers.
- `formatUtils.ts` – reusable formatting functions.

## 3. Backend Utilities

Location within each microservice:

`src/main/java/com/swiftdrop/<service>/utils/`

Examples:
- `DateUtils.java`
- `StringUtils.java`
- `FormatUtils.java`
- `CollectionUtils.java`

Use utility classes for generic, reusable and preferably stateless operations.

## 4. Mandatory Rules

- Do not duplicate reusable logic across files.
- Do not create multiple utility classes with overlapping responsibilities.
- Use meaningful utility class and method names.
- Prefer small, focused utility functions.
- Avoid unnecessary static methods when dependency injection or instance-based design is more appropriate.
- Do not put business logic inside utility classes.
- Do not put database operations inside utility classes.
- Do not put service-specific workflows inside utility classes.
- Do not turn the utils folder into a dumping ground for unrelated code.

## 5. Microservice Boundaries

Each microservice should maintain its own utilities by default.

Do not directly import another microservice's internal utility classes.

If genuinely common functionality must be shared across services, evaluate a properly versioned shared library with clear ownership and compatibility rules.

Do not introduce a shared library for trivial or unstable functionality.

## 6. AI Agent Instructions

Before creating new helper code:

1. Search for existing reusable implementations.
2. Determine whether the logic is generic or business-specific.
3. If generic and reusable, place it in the appropriate `utils/` folder.
4. If business-specific, keep it in the appropriate service or domain layer.
5. Reuse the extracted implementation where appropriate.
6. Add tests for important utility functions.

Always prioritize maintainability, cohesion and separation of concerns.
