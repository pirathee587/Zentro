#!/usr/bin/env bash
set -euo pipefail
umask 077

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
credentials_file="$script_dir/seed-credentials.local.txt"

token="$(curl --fail --silent --show-error \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' \
  --data-urlencode "username=$admin_user" \
  --data-urlencode "password=$admin_password" \
  "$base_url/realms/master/protocol/openid-connect/token" | jq -r '.access_token')"
[[ -n "$token" && "$token" != null ]] || { echo "Could not get a Keycloak admin token." >&2; exit 1; }
api="$base_url/admin/realms/$realm"
headers=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')

ensure_user() {
  local username="$1" email="$2" first="$3" last="$4" role="$5"
  local users user_id password user_json mapping role_json
  users="$(curl --fail --silent --show-error "${headers[@]:0:1}" "$api/users?username=$username&exact=true")"
  user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  if [[ -z "$user_id" ]]; then
    users="$(curl --fail --silent --show-error "${headers[@]:0:1}" "$api/users?email=$email&exact=true")"
    user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  fi
  if [[ -z "$user_id" ]]; then
    user_json="$(jq -n --arg username "$username" --arg email "$email" --arg first "$first" --arg last "$last" '{username:$username,email:$email,firstName:$first,lastName:$last,enabled:true,emailVerified:true}')"
    curl --fail --silent --show-error -X POST "${headers[@]}" -d "$user_json" "$api/users" >/dev/null
    users="$(curl --fail --silent --show-error "${headers[@]:0:1}" "$api/users?username=$username&exact=true")"
    user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  fi
  [[ -n "$user_id" ]] || { echo "Could not find created user $username." >&2; exit 1; }
  user_json="$(jq -n --arg username "$username" --arg email "$email" --arg first "$first" --arg last "$last" '{username:$username,email:$email,firstName:$first,lastName:$last,enabled:true,emailVerified:true}')"
  curl --fail --silent --show-error -X PUT "${headers[@]}" -d "$user_json" "$api/users/$user_id" >/dev/null
  password="$(openssl rand -hex 24)"
  curl --fail --silent --show-error -X PUT "${headers[@]}" \
    -d "$(jq -n --arg value "$password" '{type:"password",value:$value,temporary:false}')" \
    "$api/users/$user_id/reset-password" >/dev/null
  role_json="$(curl --fail --silent --show-error "${headers[@]:0:1}" "$api/roles/$role")"
  mapping="$(curl --fail --silent --show-error "${headers[@]:0:1}" "$api/users/$user_id/role-mappings/realm")"
  if ! jq -e --arg role "$role" 'any(.[]; .name == $role)' <<< "$mapping" >/dev/null; then
    curl --fail --silent --show-error -X POST "${headers[@]}" -d "[$role_json]" \
      "$api/users/$user_id/role-mappings/realm" >/dev/null
  fi
  printf '%s\t%s\t%s\t%s\n' "$username" "$email" "$password" "$role" >> "$credentials_file"
}

printf 'Username\tEmail\tPassword\tRole\n' > "$credentials_file"
ensure_user dispatcher@zentro.test dispatcher@zentro.test Zentro Dispatcher DISPATCHER
ensure_user courier1@zentro.test courier1@zentro.test Zentro Courier1 COURIER
ensure_user courier2@zentro.test courier2@zentro.test Zentro Courier2 COURIER

cat "$credentials_file"
