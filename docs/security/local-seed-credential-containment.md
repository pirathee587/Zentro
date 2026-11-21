# Local Keycloak seed credential containment

## Incident scope

Treat credentials for `dispatcher@zentro.test`, `courier1@zentro.test`, and
`courier2@zentro.test` as exposed. The previous seed scripts embedded fixed
passwords and printed or saved credential material. The existing ignored
`infra/keycloak/seed-credentials.local.txt` is retained until its owner approves
separate removal. Git ignore does not prevent OneDrive synchronization.

## Hardened seed behavior

`infra/keycloak/seed-users.ps1` only creates a missing local test account. It
leaves an existing account, its password, and realm settings unchanged. It only
accepts Keycloak at `http://localhost:8180`, realm `zentro`. It reads new-account
credentials from `%LOCALAPPDATA%\ZentroSecrets\seed-users.env`; it never reads
the old ignored credential file or prints secrets, tokens, or response bodies.
The Bash entry point delegates to the same PowerShell checks. The bootstrap
administrator values are read from `%LOCALAPPDATA%\ZentroSecrets\keycloak-admin.env`,
not from the repository's `infra/keycloak/.env`; that private file must pass the
same outside-OneDrive and ACL checks.

The private seed file needs these keys, each with an independent 32-character
or longer value:

```text
ZENTRO_SEED_DISPATCHER_PASSWORD=
ZENTRO_SEED_COURIER1_PASSWORD=
ZENTRO_SEED_COURIER2_PASSWORD=
```

Do not put values in this document, chat, shell history, or the repository.
The PowerShell workflow refuses OneDrive paths and requires inheritance disabled
with exactly the owner, SYSTEM, and Administrators granted full control on both
the private directory and files. `keycloak-admin.env` also requires
`KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD`.

## Separate rotation procedure (not executed)

Rotation requires a separate explicit owner approval limited to the three local
test accounts above. Before rotation:

1. Confirm the target server is the owner's local Keycloak at
   `http://localhost:8180`, realm `zentro`; stop if the target is remote or
   ambiguous.
2. Create three independent random passwords in the owner's password manager.
   Place them in the private `seed-users.env` file using a secure local editor;
   do not copy old values from `seed-credentials.local.txt`.
3. Recheck private-directory and file ACLs without displaying file contents.
4. In the local Keycloak Admin Console, reset only the password for each exact
   username. Do not change realm defaults, roles, client configuration, or any
   other account. Do not use either seed script for rotation.
5. Verify each account through the local application login without capturing
   passwords, tokens, or browser/network logs. Stop at the first ambiguous
   result and report only the account name and outcome.
6. Keep the old ignored credential file untouched until its removal and any
   OneDrive recycle/version-history handling receive separate approval.

No account password was rotated as part of this change.
