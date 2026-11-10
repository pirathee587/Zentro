# Merchant order and webhook E2E

`backend/merchant-order-webhook-e2e` is a test-only Maven module. It is not
packaged into, or depended on by, any production service. It uses the gateway,
Identity (S1), Parcel (S2), Notification (S5), PostgreSQL, Kafka and a
Testcontainers Keycloak instance importing `infra/keycloak/realm-export.json`.
The only stub is the local HTTP webhook receiver.

For resource stability, the fixture uses one PostgreSQL container but creates
`identity_db`, `parcel_pricing_db`, and `notification_integration_db`, each with
its own login. This is only a test-environment optimisation: services still
connect exclusively to their own database and credentials, preserving the
database-per-service ownership boundary. Kafka, Keycloak and service JVM heaps
are bounded, and scenarios run sequentially in one shared stack.

Run it (Docker is required) with:

```bash
cd backend && mvn --batch-mode --no-transfer-progress -pl merchant-order-webhook-e2e -am -DrunE2E verify
```

CI invokes the same `-DrunE2E verify` command. The module depends on the four
service artifacts only to make Maven build their executable jars first; its
test dependencies are JUnit, Testcontainers, AssertJ, Awaitility and
MockWebServer.

The E2E module normally completes in about 70 seconds on a warm local Maven
cache. Its capped test stack needs roughly 2.5 GiB of RAM in addition to Maven
and the operating system, so it fits the 7 GiB standard GitHub Actions Linux
runner. It is enabled only by `-DrunE2E`; ordinary `mvn test package` remains
unchanged and does not start Docker.

## Current coverage

The gateway-level flow applies as a customer, approves as a dispatcher, then
records the S1 `APPROVED` result. `findingK4_...` deliberately proves the
current approval-to-role gap: a freshly issued token still has no `MERCHANT`
role and both merchant endpoints return 403. The helper
`simulateManualMerchantRoleAssignment()` then uses the Keycloak Admin API in
the test container only. It stands in for the manual Keycloak administrator
action that production does not automate. A token issued before the mapping
stays roleless; a newly issued token has the role.

After that manual test-fixture step, the test registers a webhook, verifies the
secret is only returned on creation, creates an order, and checks the
`parcel.created` payload and HMAC on an initial 503 delivery followed by a
successful retry.

The E2E process explicitly enables the existing test-only local HTTP switch
(`WEBHOOKS_ALLOW_INSECURE_LOCAL=true`) solely so MockWebServer can receive the
callback. It neither changes `.env`, docker-compose nor a running Keycloak.
The normal validator rejects private addresses by default; that behaviour
remains covered by Notification's `WebhookUrlValidatorTest`.

## Observed delivery contract

Webhook delivery retries retryable 408, 429, 5xx, timeout, connection and DNS
failures for up to eight attempts with exponential backoff. The E2E process
uses a 100 ms base delay and 500 ms cap; production defaults remain unchanged.
400, 410 and other terminal 4xx responses are not retried. Redirects are not
followed. Terminal outcomes are recorded in the delivery store; there is no
Kafka DLT. The present wire contract has no `X-Delivery-Id` header (K5).

## Findings and limitations

| Finding | Evidence / impact |
| --- | --- |
| **K4 — approval does not assign `MERCHANT`** | S1 approval changes status only; a Keycloak admin must manually grant the role, and the user must re-login or refresh. This is the principal unmet acceptance criterion. |
| K3 — S1 API keys are not authentication | No service validates or uses S1 API keys for authentication. |
| K5 — no delivery-id response header | Current webhook delivery headers omit `X-Delivery-Id`; the E2E assertion pins that behaviour. |

This test-only change intentionally does not automate approval-to-role
assignment. Any PR carrying it must stay Draft and use `Refs #166` until that
production behaviour is separately implemented.
