#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$script_dir/.env"
if [[ ! -f "$env_file" ]]; then
  echo "Missing $env_file. Copy .env.example to .env first." >&2
  exit 1
fi

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* || "$line" != *=* ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ ${#value} -ge 2 && ( "$value" == \"*\" || "$value" == \'*\' ) ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf -v "$key" '%s' "$value"
  export "$key"
done < "$env_file"

if [[ -z "${KC_DB_PASSWORD:-}" ]]; then
  echo "Set KC_DB_PASSWORD in infra/keycloak/.env, then run this script again." >&2
  exit 1
fi

connection="${KC_DB_URL#jdbc:postgresql://}"
authority="${connection%%/*}"
host="${authority%%:*}"
port="${authority#*:}"
[[ "$port" == "$authority" ]] && port=5432
schema="${KC_DB_SCHEMA:-keycloak}"
user="${KC_DB_USERNAME:?KC_DB_USERNAME is required}"

docker run --rm \
  -e PGPASSWORD="$KC_DB_PASSWORD" \
  postgres:16 \
  psql "host=$host port=$port dbname=postgres user=$user sslmode=require" \
    --set=ON_ERROR_STOP=1 \
    --command="CREATE SCHEMA IF NOT EXISTS \"$schema\";"
