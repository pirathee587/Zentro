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

# Sync declarative UserProfile from realm-export.json so "permissions" attribute is allowed
comp="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/components?type=org.keycloak.userprofile.UserProfileProvider" | jq '.[0]')"
comp_id="$(jq -r '.id // empty' <<< "$comp")"
if [[ -n "$comp_id" && -f "$script_dir/realm-export.json" ]]; then
  desired_cfg="$(jq '.components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"]' "$script_dir/realm-export.json")"
  updated_comp="$(jq --argjson cfg "$desired_cfg" '.config["kc.user.profile.config"] = $cfg' <<< "$comp")"
  curl --fail --silent --show-error -X PUT "${headers[@]}" -d "$updated_comp" "$api/components/$comp_id" >/dev/null
fi

# Sync zentro-web client redirectUris, webOrigins, and protocol mappers (especially realm_access.roles in ID token & permissions claim)
client_obj="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/clients?clientId=zentro-web" | jq '.[0]')"
client_uuid="$(jq -r '.id // empty' <<< "$client_obj")"
if [[ -n "$client_uuid" && -f "$script_dir/realm-export.json" ]]; then
  desired_client="$(jq '.clients[0]' "$script_dir/realm-export.json")"
  updated_client="$(jq --argjson d "$desired_client" '.redirectUris = $d.redirectUris | .webOrigins = $d.webOrigins | .attributes = ($d.attributes // {})' <<< "$client_obj")"
  curl --fail --silent --show-error -X PUT "${headers[@]}" -d "$updated_client" "$api/clients/$client_uuid" >/dev/null

  existing_mappers="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/clients/$client_uuid/protocol-mappers/models")"
  while read -r mapper; do
    m_name="$(jq -r '.name' <<< "$mapper")"
    existing_id="$(jq -r --arg n "$m_name" '.[] | select(.name == $n) | .id // empty' <<< "$existing_mappers" | head -n 1)"
    if [[ -n "$existing_id" ]]; then
      updated_m="$(jq --arg id "$existing_id" '. + {id: $id}' <<< "$mapper")"
      curl --fail --silent --show-error -X PUT "${headers[@]}" -d "$updated_m" "$api/clients/$client_uuid/protocol-mappers/models/$existing_id" >/dev/null
    else
      curl --fail --silent --show-error -X POST "${headers[@]}" -d "$mapper" "$api/clients/$client_uuid/protocol-mappers/models" >/dev/null
    fi
  done < <(jq -c '.clients[0].protocolMappers[]' "$script_dir/realm-export.json")
fi

ensure_user() {
  local username="$1" email="$2" first="$3" last="$4" role="$5"
  local perms_json="${6:-}"
  local desired_password="${7:-}"
  local users user_id password user_json mapping role_json
  users="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/users?username=$username&exact=true")"
  user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  if [[ -z "$user_id" ]]; then
    users="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/users?email=$email&exact=true")"
    user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  fi
  if [[ -n "$perms_json" ]]; then
    user_json="$(jq -n --arg username "$username" --arg email "$email" --arg first "$first" --arg last "$last" --argjson perms "$perms_json" '{username:$username,email:$email,firstName:$first,lastName:$last,enabled:true,emailVerified:true,requiredActions:[],attributes:{permissions:$perms}}')"
  else
    user_json="$(jq -n --arg username "$username" --arg email "$email" --arg first "$first" --arg last "$last" '{username:$username,email:$email,firstName:$first,lastName:$last,enabled:true,emailVerified:true,requiredActions:[]}')"
  fi
  if [[ -z "$user_id" ]]; then
    curl --fail --silent --show-error -X POST "${headers[@]}" -d "$user_json" "$api/users" >/dev/null
    users="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/users?username=$username&exact=true")"
    user_id="$(jq -r '.[0].id // empty' <<< "$users")"
  fi
  [[ -n "$user_id" ]] || { echo "Could not find created user $username." >&2; exit 1; }
  curl --fail --silent --show-error -X PUT "${headers[@]}" -d "$user_json" "$api/users/$user_id" >/dev/null
  curl --fail --silent --show-error -X DELETE "${headers[@]:0:2}" "$api/attack-detection/brute-force/users/$user_id" >/dev/null || true
  password="${desired_password:-$(openssl rand -hex 24)}"
  curl --fail --silent --show-error -X PUT "${headers[@]}" \
    -d "$(jq -n --arg value "$password" '{type:"password",value:$value,temporary:false}')" \
    "$api/users/$user_id/reset-password" >/dev/null
  role_json="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/roles/$role")"
  mapping="$(curl --fail --silent --show-error "${headers[@]:0:2}" "$api/users/$user_id/role-mappings/realm")"
  if ! jq -e --arg role "$role" 'any(.[]; .name == $role)' <<< "$mapping" >/dev/null; then
    curl --fail --silent --show-error -X POST "${headers[@]}" -d "[$role_json]" \
      "$api/users/$user_id/role-mappings/realm" >/dev/null
  fi
  printf '%s\t%s\t%s\t%s\n' "$username" "$email" "$password" "$role" >> "$credentials_file"
}

printf 'Username\tEmail\tPassword\tRole\n' > "$credentials_file"
ensure_user dispatcher@zentro.test dispatcher@zentro.test Zentro Dispatcher DISPATCHER '["DISPATCH_VIEW", "DISPATCH_ASSIGN"]' "Dispatch@123"
ensure_user courier1@zentro.test courier1@zentro.test Zentro Courier1 COURIER "" "Courier1@123"
ensure_user courier2@zentro.test courier2@zentro.test Zentro Courier2 COURIER "" "Courier2@123"

cat "$credentials_file"
