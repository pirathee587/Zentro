# Local Keycloak setup

Import `realm-export.json` into Keycloak (or use it as the realm import file at first startup). The public `zentro-web` client uses Authorization Code with PKCE S256, registration is enabled, and new users receive only the `CUSTOMER` realm role by default.

The login theme is CSS-only and inherits the built-in `keycloak.v2` theme. To make it available in a local Docker Compose Keycloak service, add this volume to that service:

```yaml
volumes:
  - ./infra/keycloak/themes:/opt/keycloak/themes:ro
```

Restart Keycloak after mounting the theme. For a local seeded realm, set the variables in `.env` from `.env.example` and run `./seed-users.ps1`. The script creates or updates the dispatcher and two courier users idempotently, generates fresh strong passwords each run, and writes the credentials to the ignored `seed-credentials.local.txt` file while printing a table to the terminal. S1 provisions application user profiles the first time `/api/v1/users/me` is called. Dispatchers should provision couriers through `POST /api/v1/admin/couriers`; do not insert user records into S1's database directly.
