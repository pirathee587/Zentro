# CI pipeline

Zentro's GitHub Actions workflow is `.github/workflows/ci.yml`. It runs on pushes and pull requests to any branch. It uses GitHub-hosted Ubuntu runners, read-only repository permissions, and cancels an older run when a newer run for the same branch or pull request starts.

## Jobs

- **Frontend checks** uses Node.js 22 and the npm lockfile, installs dependencies with `npm ci`, runs the Vitest suite and ESLint, then builds the Vite production bundle.
- **Backend checks** uses Temurin Java 21 and Maven's cache, runs `mvn --batch-mode --no-transfer-progress test package` from `backend/` across the Maven reactor, and uploads Surefire XML reports even when a check fails.

Backend tests currently use mocks and web test clients; they do not require PostgreSQL or Keycloak. No repository secrets are required for CI. Environment values in application examples are for local/runtime use and are not injected into the workflow.

## Run checks locally

From the repository root:

```sh
cd frontend/zentro-web
npm ci
npm test
npm run lint
npm run build

cd ../../backend
mvn --batch-mode --no-transfer-progress test package
```

The frontend commands require Node.js 22 and npm. The backend requires Java 21 and Maven.

## Inspect a GitHub run

After pushing a commit or opening a pull request, open the repository's **Actions** tab, select the **CI** run for that commit, and inspect the failed job's step logs. For backend test failures, download the `backend-test-reports` artifact when the job produced reports. This workflow only validates and packages the applications; it does not publish images or deploy them.
