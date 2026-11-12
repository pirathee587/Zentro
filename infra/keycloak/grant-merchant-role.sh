#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--revoke] <email-or-subject-uuid>" >&2
  exit 1
}

revoke=false
target=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --revoke)
      revoke=true
      shift
      ;;
    -*)
      echo "Unknown flag: $1" >&2
      usage
      ;;
    *)
      if [[ -z "$target" ]]; then
        target="$1"
      else
        echo "Unexpected argument: $1" >&2
        usage
      fi
      shift
      ;;
  esac
done

if [[ -z "$target" ]]; then
  usage
fi

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

base_url="${KEYCLOAK_URL:-http://localhost:${KC_HTTP_PORT:-8180}}"
realm="${KEYCLOAK_REALM:-zentro}"
admin_user="${KC_BOOTSTRAP_ADMIN_USERNAME:?KC_BOOTSTRAP_ADMIN_USERNAME is required}"
admin_password="${KC_BOOTSTRAP_ADMIN_PASSWORD:?KC_BOOTSTRAP_ADMIN_PASSWORD is required}"

token="$(curl --fail --silent --show-error \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' \
  --data-urlencode "username=$admin_user" \
  --data-urlencode "password=$admin_password" \
  "$base_url/realms/master/protocol/openid-connect/token" | jq -r '.access_token')"
[[ -n "$token" && "$token" != null ]] || { echo "Could not get a Keycloak admin token." >&2; exit 1; }

api="$base_url/admin/realms/$realm"
headers=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')

user_id=""
user_email=""

# Check if target is UUID
if [[ "$target" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  user_json="$(curl --silent "${headers[0]}" "$api/users/$target" || true)"
  user_id="$(jq -r '.id // empty' <<< "$user_json")"
  user_email="$(jq -r '.email // .username // empty' <<< "$user_json")"
fi

if [[ -z "$user_id" ]]; then
  users="$(curl --fail --silent --show-error "${headers[0]}" "$api/users?username=$(printf '%s' "$target" | jq -sRr @uri)&exact=true")"
  user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  user_email="$(jq -r '.[0].email // .[0].username // empty' <<< "$users")"
  if [[ -z "$user_id" ]]; then
    users="$(curl --fail --silent --show-error "${headers[0]}" "$api/users?email=$(printf '%s' "$target" | jq -sRr @uri)&exact=true")"
    user_id="$(jq -r '.[0].id // empty' <<< "$users")"
    user_email="$(jq -r '.[0].email // .[0].username // empty' <<< "$users")"
  fi
fi

if [[ -z "$user_id" ]]; then
  echo "Could not find Keycloak user '$target'." >&2
  exit 1
fi

role_json="$(curl --fail --silent --show-error "${headers[0]}" "$api/roles/MERCHANT")"
mapping="$(curl --fail --silent --show-error "${headers[0]}" "$api/users/$user_id/role-mappings/realm")"
has_role="$(jq -e --arg role "MERCHANT" 'any(.[]; .name == $role)' <<< "$mapping" >/dev/null && echo "true" || echo "false")"

if [[ "$revoke" == "true" ]]; then
  if [[ "$has_role" == "true" ]]; then
    curl --fail --silent --show-error -X DELETE "${headers[@]}" -d "[$role_json]" \
      "$api/users/$user_id/role-mappings/realm" >/dev/null
    echo "Successfully revoked 'MERCHANT' role from $user_email (Subject: $user_id)."
  else
    echo "User $user_email (Subject: $user_id) does not have the 'MERCHANT' role. No action taken."
  fi
else
  if [[ "$has_role" == "false" ]]; then
    curl --fail --silent --show-error -X POST "${headers[@]}" -d "[$role_json]" \
      "$api/users/$user_id/role-mappings/realm" >/dev/null
    echo "Successfully granted 'MERCHANT' role to $user_email (Subject: $user_id)."
    echo "Note: The user must refresh their token or sign in again to receive the updated role in their JWT."
  else
    echo "User $user_email (Subject: $user_id) already possesses the 'MERCHANT' role."
  fi
fi
