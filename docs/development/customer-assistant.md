# Customer delivery assistant

The assistant is part of **S5 `notification-integration-service`**, in
`backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/assistant/`.
S5 owns external-provider integrations and already has the database and Keycloak resource-server infrastructure.
S1 owns identity, S2 owns parcel/pricing logic, S3 owns dispatch/courier operations, and S4 owns tracking/reporting.
No new microservice, database, frontend application, or publicly exposed MCP listener is introduced.

## Package organization

S5 uses feature-owned responsibility packages (for example `webhook/controller`, `webhook/service`,
`webhook/repository`, and `webhook/dto/request|response`). The assistant follows the same structure:

```text
com.zentro.notificationintegration
├── config                         # Existing S5 configuration package
│   ├── AssistantConfiguration
│   └── AssistantProperties
└── assistant
    ├── controller                 # AssistantController: HTTP validation and delegation
    ├── service                    # DeepSeekAssistant and AssistantConversationService
    ├── mcp                        # Managed client factory/session, tools and in-process transport
    ├── repository                 # AssistantConversationStore: JDBC persistence and leases
    └── dto
        ├── request                # AssistantChatRequest and AssistantHistoryMessage
        └── response               # Chat, conversation, message and history response records
```

Conversation orchestration and conversion of stored messages into model context belong to
`AssistantConversationService`. Controllers and repositories do not declare API DTOs.
No additional security components, duplicate configurations or services are introduced.

## Flow and security

The existing `frontend/zentro-web/src/portals/customer/pages/SupportPage.tsx` sends messages through the
existing `customerApi.askAssistant` and authenticated Axios client to the API Gateway, then S5.
The screenshot's JSX and CSS are preserved. Existing typing, error/retry, FAQ and contact-form behavior remains.
`CustomerSupportWidget.tsx` was an empty untracked file at implementation time; it is not replaced or used.

S5 uses Spring AI 1.0.3 `ChatClient` and the DeepSeek adapter with Java 21 / Spring Boot 3.5.16.
The official `spring-ai-starter-mcp-client:1.0.3` creates and manages the synchronous MCP client.
`CustomerMcpClientFactory` is a Spring-managed component that opens an isolated Spring context for each
chat invocation. That context registers the embedded server and an in-process `NamedClientMcpTransport`,
then runs Spring AI's `McpClientAutoConfiguration` and `McpToolCallbackAutoConfiguration`.
These official auto-configurations create, initialize and close the client and supply its
`SyncMcpToolCallbackProvider`. `CustomerMcpSession` closes the context after the reply, including on failure.
Real MCP initialize, tools/list and tools/call messages pass through the existing in-process transport. This is an embedded MCP server, not an HTTP MCP endpoint.
Spring AI exposes its managed MCP callbacks to DeepSeek (with its automatic client-name prefix), and the application
executes at most three model rounds and six tool calls. The default model read timeout is 15 seconds;
gateway tools use 1-second connect and 3-second read timeouts. New rounds stop after a 70-second budget.
Configured timeout values are validated against those maxima to preserve the request budget.
There are no automatic provider retries. The chat gateway route and frontend request have 90-second timeouts.
Existing notification routes retain their original timeout behavior.

The gateway and S5 validate Keycloak JWTs independently using the existing security infrastructure.
Only CUSTOMER tokens without DISPATCHER/SERVICE privileges can use S5 chat endpoints: S2 intentionally
grants unrestricted parcel access to these privileged roles, so mixed privileged tokens are rejected.
The identity always comes from the authenticated JWT subject. Tools cannot accept customer IDs, arbitrary
URLs, request paths or credentials. Each request captures its own JWT; credentials never enter model context.
The JWT is forwarded only to fixed gateway business endpoints. S2 enforces parcel ownership.
Tracking lookups first verify an exact reference in S4's owner-scoped `/tracking/mine` response before
calling the public tracking endpoint. Projection lag can therefore yield a safe unavailable/not-found answer.
The assistant omits sender/recipient contact details from parcel tool results.

Only server-stored conversation messages become model history. Legacy frontend `history` is validated
for compatibility but ignored as context. S5 persists successful question/answer pairs (including safe provider
fallbacks) in its own existing `notification` schema. Per-conversation leases prevent overlapping turns;
persistence is atomic and never keeps a database transaction open during provider calls.
History returns the latest 100 messages; model context uses the latest 20. Lists return the latest 50 conversations.
The UI keeps its conversation ID in component memory; history survives server restarts and is retrievable by API,
but this change does not add a conversation selector or automatically restore a conversation after page reload.

## Spring AI MCP Client configuration

Spring AI 1.0.3's starter targets Spring Boot 3.5.0; this service's existing dependency management
selects Boot 3.5.16 and Java 21. Compilation, packaging, and the auto-configuration integration tests
verify this combination. The SDK remains a transitive dependency because Spring AI 1.0.3 uses it internally;
there is no separate Spring AI wire client to replace it with.

The S5 POM replaces the direct `spring-ai-mcp` dependency with `spring-ai-starter-mcp-client`, pinned by
the existing Spring AI 1.0.3 BOM. Application configuration uses the official property namespace:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
        name: zentro-customer-support
        version: 1.0.0
        type: SYNC
        initialized: true
        request-timeout: 8s
        toolcallback:
          enabled: true
```

`enabled: false` prevents the starter from creating an application-wide client. The managed factory
enables the same official auto-configurations inside each per-customer context, enforcing synchronous,
initialized, tool-callback-enabled sessions and inheriting the name/version/request-timeout settings.
These contexts have no parent bean factory, so client instances, callback providers and customer-bound
tools cannot be inherited from another session. No globally configured STDIO/SSE connections are created.
The custom in-process transport is registered through the starter's `NamedClientMcpTransport` extension point.
`DeepSeekAssistant` receives the factory through constructor injection and uses only session callbacks.
There is no application-level `McpClient.sync(...)` or manually constructed MCP callback provider.
S2/S4 gateway paths, parameter validation, ownership verification and JWT forwarding remain unchanged.

## API

All paths pass through the existing gateway; all require customer authentication.

| Method | Path | Contract |
|---|---|---|
| POST | `/api/v1/assistant/chat` | `{ "message": "...", "conversationId": "optional UUID" }` -> `{ "reply": "...", "conversationId": "UUID" }` |
| POST | `/api/v1/assistant/conversations` | Empty body -> 201 `{ "id": "UUID", "createdAt": "..." }` |
| GET | `/api/v1/assistant/conversations` | Owner-scoped conversation list |
| GET | `/api/v1/assistant/conversations/{id}` | `{ "id": "UUID", "messages": [{ "role": "user or assistant", "text": "...", "createdAt": "..." }] }` |

A foreign/missing conversation returns 404; an overlapping turn returns 409.
Messages are nonblank and limited to 4,000 characters. No client-supplied subject is accepted.
Absent provider credentials, provider authentication failures, rate limits and outages return distinct safe
fallbacks where appropriate. Provider response bodies and exception details are not returned or logged by the integration.

## MCP tools and existing service APIs

| Tools | Existing service | Gateway API |
|---|---|---|
| `get_parcel_details`, `get_parcel_status`, `get_parcel_information` | S2 parcel-pricing-service | GET `/api/v1/parcels/{uuid}` |
| `get_tracking_history`, `get_current_tracking_status`, `get_estimated_delivery` | S4 tracking-reporting-service | GET `/api/v1/tracking/mine?q={ref}`, then GET `/api/v1/tracking/{ref}` |
| `get_my_deliveries` | S4 tracking-reporting-service | GET `/api/v1/tracking/mine?size=10` |
| `calculate_delivery_fee` | S2 parcel-pricing-service | POST `/api/v1/parcels/estimate` |

Dispatch/courier reads require COURIER or DISPATCHER access, and S5 exposes no authorized customer
notification-history/send endpoint. Those tools are deliberately unavailable. The assistant cannot mutate deliveries
or send notifications. Estimates, current statuses, histories and timelines come from business APIs, not duplicated logic.

## Configuration and running

Add these values to **`backend/notification-integration-service/.env` only**:

```dotenv
DEEPSEEK_API_KEY=your_deepseek_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
ASSISTANT_CONNECT_TIMEOUT_MS=1000
ASSISTANT_READ_TIMEOUT_MS=15000
ASSISTANT_TOOL_TIMEOUT_MS=3000
```

Existing S5 DB_URL, DB_USER, DB_PASSWORD and JWT settings are still required. Use `.env.example` for the
complete configuration. Actual `.env` files remain ignored by Git. No key belongs in any VITE variable.
`GATEWAY_ASSISTANT_RESPONSE_TIMEOUT_MS` optionally overrides the gateway timeout (default 90000).
S5 calls S2 directly over gRPC for parcel support tools. The frontend still reaches S5 through the API Gateway.
The existing S5 `.env` did not contain a DeepSeek key during implementation.

With existing environment files and the existing database/Keycloak/Kafka infrastructure available, run from repository root:

```bash
mvn -f backend/pom.xml -pl notification-integration-service,api-gateway -am package -DskipTests
docker compose up -d --build api-gateway notification-integration-service zentro-web
```

S2 and S4 must also be running for tool results. Flyway applies V6 to the existing S5 database at startup.
For host development, run the S5 jar from its service directory so `./.env` resolves correctly:

```bash
cd backend/notification-integration-service
java -jar target/notification-integration-service-0.1.0-SNAPSHOT.jar
```

Frontend development uses the existing `npm run dev` in `frontend/zentro-web`, its API base URL pointing
at the gateway, and existing Keycloak login. `VITE_USE_ASSISTANT_MOCK=true` explicitly selects the existing
mock adapter; normal browser runs default to the real assistant independently of other customer mock APIs.

## File inventory

New S5 files (under its directory):

- `src/main/java/com/zentro/notificationintegration/assistant/controller/AssistantController.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/request/AssistantChatRequest.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/request/AssistantHistoryMessage.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/response/AssistantChatResponse.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/response/AssistantConversationResponse.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/response/AssistantHistoryResponse.java`
- `src/main/java/com/zentro/notificationintegration/assistant/dto/response/AssistantMessageResponse.java`
- `src/main/java/com/zentro/notificationintegration/assistant/mcp/CustomerMcpClientFactory.java`
- `src/main/java/com/zentro/notificationintegration/assistant/mcp/CustomerMcpSession.java`
- `src/main/java/com/zentro/notificationintegration/assistant/mcp/CustomerSupportTools.java`
- `src/main/java/com/zentro/notificationintegration/assistant/mcp/InProcessMcpTransport.java`
- `src/main/java/com/zentro/notificationintegration/assistant/repository/AssistantConversationStore.java`
- `src/main/java/com/zentro/notificationintegration/assistant/service/AssistantConversationService.java`
- `src/main/java/com/zentro/notificationintegration/assistant/service/DeepSeekAssistant.java`
- `src/main/java/com/zentro/notificationintegration/config/AssistantConfiguration.java`
- `src/main/java/com/zentro/notificationintegration/config/AssistantProperties.java`
- `src/main/resources/db/migration/V6__assistant_conversations.sql`
- `src/test/java/com/zentro/notificationintegration/assistant/mcp/CustomerMcpClientFactoryTest.java`
- `src/test/java/com/zentro/notificationintegration/assistant/controller/AssistantControllerTest.java`
- `src/test/java/com/zentro/notificationintegration/assistant/repository/AssistantConversationStoreIntegrationTest.java`
- `src/test/java/com/zentro/notificationintegration/assistant/service/DeepSeekAssistantTest.java`

Modified files:

- S5 `pom.xml`, `.env.example`, `src/main/resources/application.yml`.
- `backend/api-gateway/.env.example`.
- `backend/api-gateway/src/main/resources/application.yml`.
- `backend/api-gateway/src/main/java/com/zentro/gateway/config/GatewaySecurityConfig.java`.
- `backend/api-gateway/src/test/java/com/zentro/gateway/routes/GatewayInventoryTests.java`.
- `backend/api-gateway/src/test/java/com/zentro/gateway/routes/GatewayBehaviorTests.java`.
- Root `docker-compose.yml`.
- `frontend/zentro-web/src/portals/customer/pages/SupportPage.tsx`.
- `frontend/zentro-web/src/portals/customer/pages/__tests__/SupportPage.test.tsx`.
- `frontend/zentro-web/src/portals/customer/services/customerApi.ts` and `types.ts`.

The existing unfinished assistant-service module/container wiring was adapted to S5; the parent POM ends
with its original five business services and gateway. Other pre-existing uncommitted work is retained.
Conversation persistence uses the existing JdbcTemplate/transaction infrastructure, so no new JPA entities are needed.
This document is also new.

## Validation

Reproduce backend checks:

```bash
mvn -f backend/pom.xml -pl notification-integration-service,api-gateway -am test
```

Assistant tests cover a DeepSeek-compatible HTTP stub using the real Spring AI adapter and real embedded
MCP handshake/tool round trip, JWT forwarding and exclusion from provider payloads, malicious tool arguments,
foreign tracking references, provider 401/429/503 fallbacks, message validation, roles, foreign conversations,
untrusted client history and PostgreSQL migration/persistence/leases. The provider and gateway business responses
are stubs in the orchestration tests; they do not prove live DeepSeek or deployed S2/S4 connectivity.
The persistence test uses a real disposable PostgreSQL 16 container, not a mock database.
Gateway tests cover S5 routing, conversation routing, customer-only access and parcel-detail JWT forwarding.
Frontend tests cover existing UI behavior and reuse of the returned conversation ID.

Live DeepSeek verification and a live browser-to-Keycloak-to-business-services flow require the configured key
and running application environment. They were not claimed by local stub tests.

Compatibility/configuration references: [Spring AI 1.0 getting started](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html),
[Spring AI DeepSeek adapter](https://docs.spring.io/spring-ai/reference/1.0/api/chat/deepseek-chat.html),
and [DeepSeek chat completions](https://api-docs.deepseek.com/api/create-chat-completion/).

### Results from the initial integration run

| Check | Actual result |
|---|---|
| Full Maven test reactor for S5, gateway and security starter | 797 tests passed: 520 S5, 215 gateway, 62 security; no failures/errors/skips |
| Final Maven package + assistant tests | S5/gateway packages built; 11 assistant tests passed, including a real disposable PostgreSQL migration/persistence test |
| SupportPage + customerApi Vitest suites | 25 passed; business/provider transport responses are mocked or stubbed |
| Frontend ESLint | Passed with 0 errors and 6 existing warnings; changed files also pass targeted lint |
| TypeScript `tsc -b --pretty false` | Passed after correcting the added test query option |
| Vite production build | Passed with output `/tmp/zentro-chat-frontend-build`; existing dist could not be overwritten due to filesystem permissions |
| Root `docker compose config --quiet` | Passed |
| `git diff --check` | Passed |
| Live DeepSeek / deployed end-to-end flow | Not verified: the saved S5 `.env` still had no nonempty DEEPSEEK_API_KEY when checked |

The sandbox initially blocked JVM attachment/local HTTP binding; backend tests passed when rerun with host access.
`npm` was unavailable in this session, so frontend commands used the IDE-bundled Node runtime directly with
`node_modules/vitest/vitest.mjs`, `node_modules/eslint/bin/eslint.js`, `node_modules/typescript/bin/tsc`,
and `node_modules/vite/bin/vite.js` under the existing frontend directory.

### Package reorganization verification

The responsibility-based package reorganization passed compilation and 27 targeted tests:
11 assistant/controller/MCP/PostgreSQL tests, 14 existing S5 JWT-security tests, and 2 gateway inventory tests.
Tests mirror the new production packages. DTO JSON fields, endpoint paths, ownership checks, conversation
leases, database schema, MCP behavior and frontend source remain unchanged by this reorganization.
The final package command rechecks the 11 assistant tests and 2 inventory tests while building S5 and gateway jars.
The earlier 797-test regression run above predates this package-only refactor.

### Spring AI managed MCP Client verification

The client migration checks the official Spring AI auto-configuration beans, one initialized client per
session, callback discovery, concurrent Alice/Bob isolation over both S2 and S4 gateway paths, inherited
client settings and transport disposal on context close. HTTP business/provider responses are local stubs;
this is not evidence of a live DeepSeek or deployed S2/S4 call.

Reproduce the targeted migration checks and package builds:

```bash
mvn -f backend/pom.xml -pl notification-integration-service,api-gateway -am package -Dtest='*Assistant*Test,CustomerMcpClientFactoryTest,ZeroTrustJwtSecurityTest,GatewayInventoryTests' -Dsurefire.failIfNoSpecifiedTests=false
```

See [Spring AI MCP Client starter](https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-client-boot-starter-docs.html)
for the official dependency, lifecycle, callback integration and configuration properties. The exact
1.0.3 auto-configuration signatures were also checked against the locally resolved artifacts.

Final migration results: **28 targeted tests passed**, with zero failures, errors or skips;
S5 and gateway packages built successfully on Java 21 / Spring Boot 3.5.16.
`git diff --check` also passed. No frontend changes were required for this client migration.
