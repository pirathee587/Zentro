# CI pipeline

The maintained inventory and execution evidence are in [CI artifacts and release verification](development/ci-and-release.md).

`.github/workflows/ci.yml` runs build/test jobs on pushes and pull requests to `develop` and `main`. Main is not currently present; these triggers do not create or approve a release branch. Existing check names `Frontend checks`, `Backend checks` and `CI result` are retained. The aggregate now also requires `Build metadata checks` and `Container build checks` to succeed, including rejecting skipped dependencies.

Frontend uses Node 22, `npm ci`, Vitest, ESLint, TypeScript and Vite. Backend uses Temurin Java 21 and the complete Maven reactor with `-DrunE2E verify`. Docker is required: integration tests use disposable PostgreSQL, Kafka and Keycloak Testcontainers. The performance profile is excluded. No repository secrets are injected into build/test jobs.

Build-only image checks cover gateway, S1–S5 and frontend. Compose validation does not interpolate variables or resolve .env files. Report artifacts omit JUnit properties, captured output and diagnostic payloads; existing S2 coverage is exported when available. Packaged build artifacts include commit/run inputs and hashes. All artifacts retain the existing seven-day duration.

No release, tag, registry-publish or deployment workflow is included. CI must not publish or deploy without approval. Repository protection, release conventions and production approval environments remain owner actions, not settings modified by this change.
