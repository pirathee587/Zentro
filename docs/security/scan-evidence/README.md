# Backend dependency scan evidence

Scan completed 2026-11-12 at 16:45 UTC with Trivy 0.59.1
(`aquasec/trivy@sha256:029e990b328d149bf0a9ffe355919041e1f86192db2df47e217f8a36dd42ceac`).
Scope: `backend/` filesystem dependency inputs; scanners: vulnerabilities;
severity: Critical and High. The Trivy vulnerability database update completed
before analysis. The local source was based on `origin/develop` at
`9275ea8ccb3170097757e332525125264769d21f` with this branch's uncommitted
changes, including the S1 gRPC upgrade to 1.75.0. The scan exited 0 with no
Critical or High findings. Raw JSON is in `trivy-backend-local.json`.

This is a local filesystem dependency scan, not an image scan or a signed
attestation. The CI scan uses the pinned Trivy action and is independently
rerun on GitHub; results can change as the vulnerability database advances.
