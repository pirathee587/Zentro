# Backend operational handover — Issue #359

Status: PARTIAL. No handover meeting, reviewer walkthrough or production release
has occurred in this task. [Runbook](backend-runbook.md),
[technical appendix](../architecture/backend-completion-359.md),
[candidate evidence](../testing/backend-integration-evidence.md) and
[owner requests](../testing/issue-359-owner-requests.md) form the review package.

| Main / service | Authoritative owner | Escalation path |
|---|---|---|
| 01 identity/auth | @kirusigan03 | #350 owner follow-up |
| 02 gateway/shared contracts/security | @pavi3alnx | #351 owner follow-up |
| 03 infra/build/CI/observability | @Niro0526 | #352 owner follow-up |
| 04 parcel/pricing/source | @Jathuja | #353 owner follow-up |
| 05 dispatch/courier/proof | @Nilakshana23 | #354 owner follow-up |
| 06 merchant acceptance | @Kiruthiyan | #355 owner follow-up |
| 07 Kafka/notification runtime | @Deadsecnote1 | #356 owner follow-up |
| 08 tracking/receipt/privacy | @hephzibah-peries | #357 owner follow-up |
| 09 reports | @14Jannan | #358 owner follow-up |
| 10 final acceptance/evidence | @pirathee587 | #359; independent review @Niro0526 |

These issue/owner paths are repository coordination contacts, not an invented
on-call roster, emergency contact or response SLA. Production escalation and
credential custodians require authorized operator confirmation.

Reviewer walkthrough:

- Confirm candidate SHA, worktree differences, artifact manifest revision and CI.
- Follow the runbook from clean disposable databases; verify health for all services.
- Inspect gateway/direct-service role differences and real booking event/receipt evidence.
- Inspect secret provisioning references without exporting credentials or test payloads.
- Reproduce service/broker restart, outbox retry and per-service restore drills;
  retain independent before/after facts. These drills remain required.
- Local evidence now includes five quiesced service-database restores with matching
  selected table counts. S2 booking and S5 notification counts were 1→1; S1/S3/S4
  selected tables were 0→0. This does not establish full nonempty restore integrity,
  broker recovery or independent production recovery sign-off.
- Confirm Kafka retention/offset recovery, migration compatibility and prior artifact
  rollback plan with Main 03/07/08. Never roll back applied migrations casually.
- Resolve R01–R07 with authorized approvals and domain PRs; verify actual full courier,
  dispatcher, capacity and proof acceptance. A green characterization is insufficient.
- Record independent security/recovery/document decisions and accepted risks explicitly.
- Keep release BLOCKED until the candidate gate is satisfied. No publishing/deployment
  or production secret/setting change is authorized by this handover package.
