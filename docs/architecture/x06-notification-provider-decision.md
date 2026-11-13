# X06 provider readiness — PROPOSED, release decision BLOCKED

Date: 2026-11-14. Refs #24 and #356. Baseline develop:
`b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22`.
Implementation owner @Deadsecnote1; independent reviewer @Kiruthiyan.
Historical #24 mock acceptance is not approval of a real provider or mock-only release.

## Context and reusable implementation

S5 already has NotificationSender (`channel`, `send`), OutgoingNotification (stable event ID,
tracking reference, status, channel, rendered message and policy attempt), MockSmsSender,
post-commit delivery, durable pending/retry outcomes and metrics/health. Reuse these rather
than creating a second provider framework. OutgoingNotification is currently tracking-reference
addressed: it is not a secure personal destination contract and cannot be pointed at a real
SMS/email provider simply by changing a channel string. ChannelPreferenceService supports
MOCK_SMS only. RecipientResolver has no approved person-contact lookup or persisted consent.

Existing runtime needs no provider credentials. Mock mode remains active; no real messages
are sent by this work. G17's local HTTP test receiver is a synthetic acceptance/deduplication
fixture, not an SMS/email adapter or approved external sandbox.

## Options — comparison, not approval

No vendor, geographic route, volume or price quote is approved. Cost entries are planning
inputs, not current market quotes; obtain regional vendor/security terms before selection.

| Dimension | A: mock only | B: real SMS | C: real email |
|---|---|---|---|
| Cost | Existing local compute/storage; no per-message provider bill | Obtain route/segment, sender registration, volume, retry and sandbox costs | Obtain volume, domain, dedicated-IP/support and retry costs |
| Security | No new provider credential; still protect stored event/message data | Scoped credential, outbound TLS, sender authorization, signing/verification of callbacks if supported | Scoped credential, outbound TLS, domain authentication, callback verification if supported |
| Privacy | No real destination; tracking reference is still restricted operational data | Phone destination, processor/data-region/retention approval | Email destination, processor/data-region/retention approval |
| Consent | Does not demonstrate consent or real-recipient correctness | Approved transactional/marketing purpose, lawful consent/eligibility and suppression rules required | Same; unsubscribe/transactional classification must be decided, not invented |
| Operations | Current retry scheduler/health; clearly label mock outcomes | Rate limits, sender registration, delivery receipts, routing, outages and abuse handling | Domain setup, bounce/complaint handling, reputation and suppression |
| Secrets | No SMS/email provider secret | Named secret owner, least-privilege key, rotation/revocation and separate sandbox/production secrets | Same, plus approved domain ownership |
| Sandbox | Existing mock/failure modes | Verify vendor has an authorized restricted sandbox; capability/cost unknown until selected | Verify restricted sandbox/test recipients and domain controls; capability unknown until selected |
| Testing | Existing deterministic unit/PostgreSQL/Kafka tests | Adapter mocks, timeout/429/5xx, ambiguous acceptance, idempotency, safe callback tests and separately authorized sandbox exercise | Same plus bounce/complaint/suppression semantics if in scope |
| Deployment | Current configuration | Approved egress, secret injection, monitoring and explicit enablement; infrastructure owner review | Same plus approved sender/domain configuration |

Recommendation: retain current mock operation while decisions are pending. Ask product
whether an explicitly limited mock-only release is acceptable (Option A). If real contact
is mandatory, select B/C only after the G16/S1 destination/consent and provider security
contracts are approved. This recommendation is not acceptance of mock-only release scope.
No SMS or email vendor is recommended without requirements and authorized evaluation.

## Approval register

Each decision is **PROPOSED / BLOCKED**. Record approver identity, date and decision link;
a technical PR review is not a substitute for product/security sign-off.

| Decision | Options / recommended approach | Required approver | Consequence |
|---|---|---|---|
| Release scope | A, B, C, or approved combination; explicitly state whether courier contact delivery is required | Product with S3/S5 | No real adapter activation until accepted scope |
| Provider selection | Approved shortlisted vendors, region, supported purpose and idempotency/receipt guarantees | Product/security/privacy and service owner | No SDK, paid account or provider-specific contract yet |
| Destination and consent | S1 purpose-specific lookup; approved channel/preferences, withdrawal and suppression | S1/security/privacy/product, main 02 contract review | Current tracking reference cannot be substituted for a person contact |
| Credential ownership | Dedicated S5 sandbox/production credentials injected by approved secret manager/environment bridge | Deployment/main 03, security and named operational custodian | Credentials are not available/approved; do not solicit or commit secrets in PRs |
| Sandbox authorization | Restricted sandbox identities, test destinations, spending limit, data handling and teardown | Product/security, credential custodian and recipient authorization where needed | No network sandbox test or real message is authorized now |
| Production rollout | Explicit enablement, failure budgets, rollback, support and evidence | Product/security/S5/main 03 | No production provider configuration or egress enabled |
| Provider deduplication | Verify stable request idempotency semantics and duration; otherwise accept documented at-least-once effects | Product/S5/provider owner/security | Cannot infer exactly-once from S5 uniqueness or a vendor request ID |

No actual credentials, authorized sandbox account, provider approval or production enablement
record was found in the inspected sources/issues. Their absence is an implementation gate,
not a request for credentials to be pasted into this repository or conversation.

## Safe configuration groundwork already present

These are existing variables, not newly activated provider switches:

| Variable | Existing meaning |
|---|---|
| NOTIFICATIONS_CONSUMER_ENABLED | Parcel consumer disabled by default |
| NOTIFICATION_MOCK_FAIL_MODE | none, always, first-attempt, timeout, throttled, permanent — test mock behavior |
| NOTIFICATION_MAX_ATTEMPTS | Provider-attempt budget, default 5; separate from Kafka durable storage retry |
| NOTIFICATION_RETRY_POLL_MS | Due-work polling interval, default 5000 ms |
| NOTIFICATION_RETRY_BASE_DELAY_MS / NOTIFICATION_RETRY_MAX_DELAY_MS | Existing exponential backoff, default 5000/600000 ms |
| NOTIFICATION_HEALTH_FAILED_WINDOW_MINUTES / NOTIFICATION_HEALTH_STUCK_PENDING_MINUTES | Existing health windows, default 60/10 |

For a future adapter, propose an explicit validated provider selector (mock by default),
credential reference, timeouts, bounded rate-limit recovery and approved egress endpoint.
Exact names/defaults are intentionally not added to .env or application.yml before a
provider contract exists. Never allow an unrecognized provider value to silently select
real delivery. No real recipient fallback to tracking-reference data. Preserve mock mode
for local tests and an independent emergency disable path in the approved rollout plan.

## Proposed delivery/observability contract

Retain post-commit durable scheduling. Distinguish accepted, retryable rejection, permanent
rejection and ambiguous acceptance. Current NotificationSender returns no provider receipt
and has one checked failure type: typed results/receipts are a future reviewed interface
extension, not implemented here. Do not claim that present channel strings distinguish
all provider outcomes. A real adapter must define connect/read/total deadlines, retry-after
handling, provider idempotency key mapping, cancellation and timeout ambiguity.

Reuse safe attempt/success/failure/retry counters, pending/failed summaries and health.
Propose bounded provider/category labels for timeout/rate-limit without recipient, event,
error-text or credential labels; a metric is not durable evidence. Main 03 coordinates
collectors/alerts. The existing failed counter/failed.total gauge Prometheus name collision
is documented by G17 and needs a separately coordinated metric contract change.
Provider delivery receipts are distinct from HTTP/API acceptance; authenticated callbacks
and deduplication require a reviewed API if selected. Log only bounded safe categories.

Automated tests must never send real SMS/email: mock the adapter or use a loopback HTTP
receiver with synthetic IDs. Separately authorized sandbox exercises must have a controlled
recipient list, budget, consent and cleanup; approval and results must be recorded honestly.
Test credential redaction, timeout, 429, transient/permanent errors, restart/replay, opt-out,
incorrect recipient, callback spoofing if applicable and acceptance-before-commit ambiguity.
Provider idempotency key lifetime must cover the approved replay horizon; no duration is
assumed here.

## Consequences, verification and rollback

No new abstraction, dependency, API, migration, contact schema or secret configuration is
needed for safe groundwork: those components already exist and missing contracts constrain
use. This PR supplies the missing decision artifact and configuration inventory only.
Source/issue inspection and documentation checks are its validation, not proof of real
provider readiness. G17's tests remain separately identified; they do not satisfy X06.

A future approved rollout must disable new external sends on rollback while preserving
pending work/outcomes and resolving ambiguous sends before replay. Rotating/revoking a key
is a credential-custodian operation, not deleting delivery or deduplication evidence.
This documentation-only proposal is reversible without runtime effects. X06 stays BLOCKED
until scope, provider/consent/security, credentials and authorized sandbox decisions are
recorded and the selected implementation is tested and independently accepted.
