# Contributing to Zentro

All integration work targets `develop`. Do not create or target a `main` branch as part of this workflow.

## Workflow

1. **Issue:** Create or select a GitHub issue that describes the change and its acceptance criteria.
2. **Board:** Add the issue to the Zentro Project board and move it to **Ready** before starting.
3. **Branch:** Update your local `develop` branch, then create a short-lived branch using the issue number:
   - `feature/<issue-no>-<short-name>`
   - `fix/<issue-no>-<short-name>`
   - `chore/<issue-no>-<short-name>`
4. **Commit:** Use Conventional Commit messages, such as `feat: ...`, `fix: ...`, `chore: ...`, or `docs: ...`. Keep commits focused.
5. **Pull request:** Open one PR from your branch into `develop`. Include `Closes #N` in the PR description and explain the change and testing.
6. **CI and review:** Wait for the required CI checks to pass and at least one reviewer to approve. Address requested changes before merging.
7. **Merge:** Squash-merge the approved PR into `develop`. Delete the feature branch after merging if appropriate.

## Local verification

Run checks relevant to your changes before opening a PR. For backend changes, from the repository root:

```bash
cd backend
mvn --batch-mode --no-transfer-progress test package
```

For documentation-only changes, review the rendered Markdown and check links where practical.

## Good practices

- Keep each PR focused and small.
- Do not commit secrets, real `.env` files, passwords, tokens, private keys, build output, or dependency folders.
- Do not edit existing Flyway migrations; add a new migration instead.
- Coordinate changes to shared contracts and security-sensitive code with the relevant maintainers.
- Never close an issue just to clear the board; close it only when the work is complete or intentionally not planned.
