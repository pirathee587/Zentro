# Zentro production Keycloak: single hosted server (prepared, not deployed)

**Status:** this configuration has been validated offline (compose config, Caddy validator, Keycloak 26.8.1
build options). Nothing has been provisioned, purchased or connected. Any deployment needs the owner's approval.

## Target

One always-on Keycloak 26.8.1 server in production mode. It is the **only** Keycloak connected to the
shared Supabase `keycloak` schema (`aws-0-ap-southeast-2`, Sydney). Every environment uses its public
HTTPS address as the token issuer. Laptops no longer run their own Keycloak.

```
browser / laptops / services ──HTTPS──> Caddy (TLS, Let's Encrypt) ──> Keycloak :8080 ──TLS──> Supabase pooler :5432 (schema keycloak)
                                                                       └ management :9000 (health, metrics; private)
```

## Requirements

| Item | Requirement |
|------|-------------|
| Size | 2 vCPU, 2 GB RAM minimum (JVM heap capped at ~70% of a 1.4 GB container limit), 20 GB+ disk |
| Region | Same region as the Keycloak database: **Sydney (ap-southeast-2)**. Keycloak queries its database on every login, and each round-trip adds latency |
| Network | Inbound 80/443 open; SSH (22) only from the owner's IP; outbound 5432 to Supabase and 443 to Google |
| Domain | A DNS name you control, for example `auth.<your-domain>`, with an A record pointing at the server's static IP |
| Database | Supabase **session pooler** (port 5432, IPv4). Never the transaction pooler (6543) |
| OS | Ubuntu 24.04 LTS with Docker Engine and Docker Compose v2, unattended security upgrades on |

## Files

- `docker-compose.production.yml`: Keycloak (`start`, production mode, `KC_CACHE=local` single node,
  `KC_PROXY_HEADERS=xforwarded`, strict hostname, health and metrics on a private port, memory limit,
  log rotation) and Caddy (automatic HTTPS, HSTS, admin console allow-list, health and metrics hidden).
- `Caddyfile`: the HTTPS front.
- `production.env.example`: template for the server-only `.env.production` (`chmod 600`; ignored by git).
- `backup-keycloak-schema.sh`: nightly read-only `pg_dump` of the `keycloak` schema with verification,
  14-day retention and optional off-host copy.
- `themes/`: copy `infra/keycloak/themes` here at deploy time. It is mounted read-only.

## Deployment steps (after approval)

1. Create the VM and a static IP, open ports 80/443, and restrict SSH.
2. Create the DNS A record, then wait until it resolves.
3. Copy this folder and `../themes` to `/opt/zentro-identity`. Create `.env.production` from the
   template on the server.
4. **Stop every other Keycloak connected to the shared database**, laptops included.
5. Pre-pull the images. Then `docker compose -f docker-compose.production.yml up -d`.
6. Wait for the healthcheck to pass and for `https://<host>/realms/zentro/.well-known/openid-configuration`
   to return `issuer = https://<host>/realms/zentro`.
7. Admin access: use the existing shared master admin, or the documented one-time `bootstrap-admin`.
   Create named admins, then remove any temporary admin.
8. Then continue with the identity migration runbook (service clients plus scope mappings, redirect
   URIs, imports, S1 reconciliation, service issuer switch).

## Monitoring

- External uptime check every minute on the OIDC discovery URL. UptimeRobot's or Better Stack's free tier
  is enough. Alert by email.
- Provider CPU, memory and disk alarms (for example Lightsail metric alarms) at 80%.
- `docker compose ps` healthcheck. Container restart policy `unless-stopped`.
- Optional: scrape `:9000/metrics` from inside the VM only.
- Certificate renewal is automatic (Caddy). Let's Encrypt expiry emails go to `ACME_EMAIL`.

## Backups and recovery

- **Database:** confirm your Supabase plan's built-in backups (the free plan has no point-in-time
  recovery). In addition, run `backup-keycloak-schema.sh` nightly with an off-host copy, and do a
  monthly restore test into a throwaway Postgres.
- **Server:** stateless apart from the Caddy certificate volume. Use a weekly provider snapshot.
  Rebuilding means: new VM, the same folder and `.env.production`, `up -d`.
- **Configuration:** keep this folder in git. `.env.production` is backed up in the owner's password
  manager or secret store only.

## Known follow-ups (not blockers for hosting)

- **S1 admin credentials.** S1 currently calls the Keycloak admin API by logging in as the master-realm
  bootstrap admin with a username and password. Before hosting, replace this with a dedicated confidential
  client limited to `realm-management` `manage-users` and `view-users`. That needs a small S1 code change,
  and keeps the master admin password off developer machines.
- **Cross-site session check.** With Keycloak on `https://auth.<domain>` and a frontend on
  `http://localhost`, the hidden-iframe session check becomes cross-site, and browsers may block its
  cookies. Plan to host the frontend under the same registrable domain (for example `app.<domain>`), and
  re-test refresh behaviour for local development after cutover.
- **Supabase plan.** Keycloak's connections count against the pooler's connection limits (pool max 10 here).
