#!/usr/bin/env bash
# PREPARED, NOT INSTALLED. Nightly logical backup of the shared Supabase "keycloak" schema, run on the
# Keycloak VM by cron, e.g.:  15 2 * * *  /opt/zentro-identity/backup-keycloak-schema.sh
# Reads DB settings from .env.production (never echoed). Dumps in a read-only session, verifies the
# archive, keeps 14 days locally, and optionally copies off-host when BACKUP_REMOTE is set (rclone remote).
set -euo pipefail; set +x
cd "$(dirname "$0")"
umask 077
get() { grep -E "^$1=" .env.production | head -1 | cut -d= -f2- | tr -d '\r'; }
URL=$(get KC_DB_URL); HOST=$(echo "$URL" | sed -E 's#jdbc:postgresql://([^:/]+).*#\1#'); PORT=$(echo "$URL" | sed -E 's#.*:([0-9]+)/.*#\1#')
[ "$PORT" = "5432" ] || { echo "refusing: backups must use the session pooler (5432)" >&2; exit 2; }
DEST=/var/backups/zentro-keycloak; mkdir -p "$DEST"
TS=$(date -u +%Y%m%dT%H%M%SZ); OUT="$DEST/keycloak-$TS.dump"
docker run --rm --network host -v "$DEST:/out" \
  -e PGHOST="$HOST" -e PGPORT=5432 -e PGDATABASE=postgres -e PGUSER="$(get KC_DB_USERNAME)" -e PGPASSWORD="$(get KC_DB_PASSWORD)" \
  -e PGSSLMODE=require -e PGOPTIONS='-c default_transaction_read_only=on' \
  postgres:17-alpine sh -c "pg_dump --format=custom --schema=keycloak --no-owner --no-privileges --lock-wait-timeout=10s -f /out/$(basename "$OUT") && pg_restore --list /out/$(basename "$OUT") >/dev/null"
sha256sum "$OUT" > "$OUT.sha256"
find "$DEST" -name 'keycloak-*.dump*' -mtime +14 -delete
if [ -n "${BACKUP_REMOTE:-}" ]; then rclone copy "$DEST" "$BACKUP_REMOTE" --include "keycloak-$TS.dump*"; fi
echo "backup ok: $(basename "$OUT")"
