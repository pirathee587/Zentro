# Backend release-candidate gate — G41 / Issue #359

Decision: BLOCKED. Primary @pirathee587; independent reviewer @Niro0526.
No release, tag, deployment or merge is authorized by this document.

| Required gate | Status | Objective condition |
|---|---|---|
| One reproducible candidate | PARTIAL | Record committed SHA; full runner checks identical start/end HEAD and clean build/contract/fixture/config inputs. Later evidence commits are identified separately. |
| Automated service and integration checks | BLOCKED | Frozen full run at `3bc31d9` collected 2,564 tests across 237 suites: 2,558 passed, 5 failed, 1 errored, zero skipped; lifecycle/dispatcher suites are absent. See [exact evidence](backend-integration-evidence.md#frozen-full-candidate-run--2026-11-14). |
| Customer booking/receipt/tracking | BLOCKED | Main 02 gateway receipt/detail registration and Main 08 tracking initialization/hydration; current real acceptance must pass. |
| Real dispatch/courier/proof | BLOCKED | Main 04/05 approved source, capacity and progress contracts/implementation plus domain-owned lifecycle/dispatcher suites. |
| Signed-role / disabled-account sign-off | PARTIAL | All protected operations, foreign owners and SERVICE restrictions verified. S1 disabled-profile update behavior is distinct from unapproved global stale-token revocation. |
| Recovery | PARTIAL | Local restart, storage rollback/retry and quiesced selected-count restore evidence; independent complete recovery/broker/restore walkthrough still required. |
| Policy and risk acceptance | BLOCKED | Named authorized approver records lifecycle/capacity/late-proof and #301/#305/#306 decisions; no silent residual-risk acceptance. |
| CI on final head | UNVERIFIED | No GitHub run exists yet for the new branch head; prior draft PR #379 backend CI failed on its older head. |
| Independent approval / merge | UNVERIFIED | @Niro0526 approval, required checks and actual merge SHA recorded. A draft PR does not move Issue #359 to Review. |
| Version/tag/publication policy | BLOCKED | Authorized policy, release owner and approval recorded. Existing development SNAPSHOT and build-only CI do not grant release authority. |

Evidence: [single candidate index](backend-integration-evidence.md),
[security sign-off](../security/backend-final-signoff.md),
[owner requests](issue-359-owner-requests.md),
[runbook](../operations/backend-runbook.md), [handover](../operations/backend-handover.md).
Optional AI, Redis and WebSocket features are not silently required gates; no
unapproved scope decision is made here.

Remaining backend deliverables are the existing architecture/ER/deployment
appendix, factual test/coverage summary and handover package. Coverage percentage,
production topology, contribution sign-off and any required non-frontend PDF remain
UNVERIFIED until owners/reviewer confirm them. Existing Markdown diagrams are not
proof of a deployed topology. No frontend artifact or final project PDF is added.
