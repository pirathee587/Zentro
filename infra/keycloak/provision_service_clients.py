#!/usr/bin/env python3
"""Provision least-privilege Zentro service clients in Keycloak, create-only by default.

Modes (secrets are read only from the environment; credentials and tokens are never printed):

  (no flags)                      Create-only. Refuses before any write if a target client already exists.
  --plan                          Read-only. Reports which clients would be created, or how existing ones drift.
  --verify                        Read-only. Issues client_credentials tokens with the injected secrets and
                                  checks claims plus effective service-account roles.
  --repair-dispatch CLIENT_ID     Repair only the documented partial dispatch client state.
      --dry-run                   Read-only preflight for a recovery mode.
  --repair-tracking CLIENT_ID    Repair only the documented partial tracking client state.
      --dry-run                   Read-only preflight for --repair-tracking.
  --create-missing CLIENT_ID      Create only tracking + notification after existing clients verify cleanly.
  --create-notification           Create only Notification after Parcel, Dispatch, and Tracking verify cleanly.
  --update-existing               Reconcile configuration of clients this tool manages. Never changes secrets.
  --rotate-secret CLIENT_ID       Explicitly replace the secret of a managed client (repeatable).
  --remove-managed CLIENT_ID      Recovery only: delete a manifest client tagged as created by this tool
      --confirm-remove            (repeatable). Unmanaged or unrelated clients are never touched.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MANIFEST = json.loads((ROOT / "service-clients.json").read_text(encoding="utf-8"))
BASE = os.environ.get("KC_URL", "http://localhost:8180").rstrip("/")
REALM = os.environ.get("KC_REALM", MANIFEST["realm"])
ADMIN_USER = os.environ.get("KC_ADMIN_USERNAME", os.environ.get("KC_BOOTSTRAP_ADMIN_USERNAME", "admin"))
ADMIN_PASSWORD = os.environ.get("KC_ADMIN_PASSWORD", os.environ.get("KC_BOOTSTRAP_ADMIN_PASSWORD", ""))
APP_ROLES = {"CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE"}
MANAGED_ATTRIBUTE = "zentro.managed-by"
MANAGED_VALUE = "provision_service_clients"
MIN_SECRET_LENGTH = 32
ADMIN_CLIENT = "realm-management"

READ_ONLY = False  # set by --plan / --verify; enforced in request()
ADMIN_TOKEN_REFRESH_MARGIN_SECONDS = 15
ADMIN_WRITE_MIN_REMAINING_SECONDS = 5
_ADMIN_TOKEN_CACHE: str | None = None
_ADMIN_TOKEN_EXPIRY: int | None = None


class ProvisioningError(RuntimeError):
    pass


class KeycloakAPIError(ProvisioningError):
    def __init__(self, status_code: int, method: str, path: str):
        self.status_code = status_code
        super().__init__(f"Keycloak API returned HTTP {status_code} for {method} {path}")


def _is_token_endpoint(path: str) -> bool:
    return path.endswith("/protocol/openid-connect/token")


def request(method: str, path: str, token: str | None = None, body=None, form=None,
            _retry_auth: bool = True):
    if READ_ONLY and method != "GET" and not (method == "POST" and _is_token_endpoint(path)):
        raise ProvisioningError(f"Read-only mode refused {method} {path}")
    request_token = token
    if path.startswith("/admin/") and token:
        request_token = _ADMIN_TOKEN_CACHE or token
        expiry = (_ADMIN_TOKEN_EXPIRY if _ADMIN_TOKEN_CACHE
                  else _jwt_expiry(request_token))
        if expiry is None or expiry <= time.time() + ADMIN_TOKEN_REFRESH_MARGIN_SECONDS:
            request_token = admin_token(force_refresh=True)
            expiry = _ADMIN_TOKEN_EXPIRY
        if (method in {"POST", "PUT", "DELETE"}
                and (expiry is None or expiry <= time.time() + ADMIN_WRITE_MIN_REMAINING_SECONDS)):
            raise ProvisioningError("Refusing admin configuration write: token expiry is not safely verifiable.")
    headers = {"Accept": "application/json"}
    if request_token:
        headers["Authorization"] = f"Bearer {request_token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    else:
        data = None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        # Do not include response bodies: identity-provider errors can contain request details.
        if error.code == 401 and method == "GET" and path.startswith("/admin/") and request_token and _retry_auth:
            fresh_token = admin_token(force_refresh=True)
            return request(method, path, fresh_token, body=body, form=form, _retry_auth=False)
        raise KeycloakAPIError(error.code, method, path) from None


def realm_path(suffix: str) -> str:
    return f"/admin/realms/{urllib.parse.quote(REALM, safe='')}{suffix}"


def _jwt_expiry(token: str) -> int | None:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload)).get("exp"))
    except (IndexError, TypeError, ValueError, json.JSONDecodeError):
        return None


def admin_token(force_refresh: bool = False) -> str:
    global _ADMIN_TOKEN_CACHE, _ADMIN_TOKEN_EXPIRY
    if (not force_refresh and _ADMIN_TOKEN_CACHE and _ADMIN_TOKEN_EXPIRY
            and _ADMIN_TOKEN_EXPIRY > time.time() + ADMIN_TOKEN_REFRESH_MARGIN_SECONDS):
        return _ADMIN_TOKEN_CACHE
    if not ADMIN_PASSWORD:
        raise ProvisioningError("Set KC_ADMIN_PASSWORD or KC_BOOTSTRAP_ADMIN_PASSWORD before provisioning clients.")
    _, result = request("POST", "/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USER, "password": ADMIN_PASSWORD,
    })
    token = result.get("access_token") if isinstance(result, dict) else None
    if not token:
        raise ProvisioningError("Keycloak did not return an administrator access token.")
    _ADMIN_TOKEN_CACHE = token
    _ADMIN_TOKEN_EXPIRY = _jwt_expiry(token)
    return token


# --- read helpers -----------------------------------------------------------------------------

def find_client(token: str, client_id: str) -> dict | None:
    query = urllib.parse.urlencode({"clientId": client_id})
    _, matches = request("GET", realm_path("/clients") + "?" + query, token)
    exact = [m for m in matches or [] if m.get("clientId") == client_id]
    return exact[0] if exact else None


def is_managed(client: dict) -> bool:
    return (client.get("attributes") or {}).get(MANAGED_ATTRIBUTE) == MANAGED_VALUE


def realm_info(token: str) -> tuple[dict, str]:
    _, realm = request("GET", realm_path(""), token)
    _, roles = request("GET", realm_path("/roles"), token)
    role = next((item for item in roles or [] if item.get("name") == MANIFEST["serviceRole"]), None)
    if not role:
        raise ProvisioningError("Realm is missing the SERVICE role.")
    default_role = (realm.get("defaultRole") or {}).get("name") or f"default-roles-{REALM}"
    return role, default_role


def service_account_id(token: str, client_internal_id: str) -> str:
    _, account = request("GET", realm_path(f"/clients/{client_internal_id}/service-account-user"), token)
    return account["id"]


def effective_roles(token: str, client_internal_id: str) -> tuple[set[str], list[str]]:
    """Effective (composite-expanded) realm roles and any realm-management roles of the service account."""
    account = service_account_id(token, client_internal_id)
    _, realm_roles = request("GET", realm_path(f"/users/{account}/role-mappings/realm/composite"), token)
    admin = find_client(token, ADMIN_CLIENT)
    admin_roles: list[str] = []
    if admin:
        _, mapped = request("GET", realm_path(f"/users/{account}/role-mappings/clients/{admin['id']}/composite"), token)
        admin_roles = sorted(r["name"] for r in mapped or [])
    return {r["name"] for r in realm_roles or []}, admin_roles


def desired_body(spec: dict, secret: str | None) -> dict:
    body = {
        "clientId": spec["clientId"],
        "name": spec["clientId"],
        "description": spec["description"],
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "bearerOnly": False,
        "clientAuthenticatorType": "client-secret",
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True,
        "authorizationServicesEnabled": False,
        "fullScopeAllowed": False,
        "attributes": {MANAGED_ATTRIBUTE: MANAGED_VALUE},
    }
    if secret is not None:
        body["secret"] = secret
    return body


def drift(token: str, client: dict, spec: dict) -> list[str]:
    want = desired_body(spec, None)
    issues = [k for k in ("publicClient", "bearerOnly", "standardFlowEnabled", "implicitFlowEnabled",
                          "directAccessGrantsEnabled", "serviceAccountsEnabled", "fullScopeAllowed", "enabled")
              if client.get(k) != want[k]]
    if not is_managed(client):
        issues.append("not tagged as managed by this tool")
    if client.get("serviceAccountsEnabled"):
        roles, admin_roles = effective_roles(token, client["id"])
        app = roles & APP_ROLES
        if app != {"SERVICE"}:
            issues.append(f"effective app roles {sorted(app)}")
        extra = sorted(r for r in roles if r.startswith("default-roles-"))
        if extra:
            issues.append(f"inherits {extra}")
        if admin_roles:
            issues.append(f"realm-management roles {admin_roles}")
    return issues


# --- write helpers ----------------------------------------------------------------------------

def mapper_upsert(token: str, client_internal_id: str, mapper: dict):
    path = realm_path(f"/clients/{client_internal_id}/protocol-mappers/models")
    _, current = request("GET", path, token)
    existing = next((item for item in current or [] if item.get("name") == mapper["name"]), None)
    if existing:
        mapper = {**mapper, "id": existing["id"]}
        request("PUT", path + "/" + urllib.parse.quote(existing["id"], safe=""), token, body=mapper)
    else:
        request("POST", path, token, body=mapper)


def remove_mapper(token: str, client_internal_id: str, name: str):
    path = realm_path(f"/clients/{client_internal_id}/protocol-mappers/models")
    _, current = request("GET", path, token)
    for item in current or []:
        if item.get("name") == name:
            request("DELETE", path + "/" + urllib.parse.quote(item["id"], safe=""), token)


def configure_mappers(token: str, client_internal_id: str, spec: dict):
    desired = expected_mappers(spec)
    for mapper in desired.values():
        mapper_upsert(token, client_internal_id, mapper)
    if "Zentro POD permissions" not in desired:
        remove_mapper(token, client_internal_id, "Zentro POD permissions")


def expected_mappers(spec: dict) -> dict[str, dict]:
    """Return the only managed protocol mappers permitted for this service client."""
    mappers = {
        "Zentro API audience": {
            "name": "Zentro API audience",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "consentRequired": False,
            "config": {"included.custom.audience": MANIFEST["audience"], "id.token.claim": "false", "access.token.claim": "true"},
        }
    }
    permissions = spec.get("podPermissions")
    if permissions:
        mappers["Zentro POD permissions"] = {
            "name": "Zentro POD permissions",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-hardcoded-claim-mapper",
            "consentRequired": False,
            "config": {"claim.name": "pod_permissions", "claim.value": " ".join(permissions),
                       "jsonType.label": "String", "id.token.claim": "false", "access.token.claim": "true",
                       "userinfo.token.claim": "false"},
        }
    return mappers


def _mapper_matches(actual: dict, expected: dict) -> bool:
    """Match the declared mapper plus Keycloak 26.8.1 effective-config defaults.

    The effective representation derives userinfo inclusion from ID-token
    inclusion and introspection inclusion from access-token inclusion. These
    flags govern those response types independently; they do not replace the
    explicitly checked access-token mapper setting.
    """
    if not isinstance(actual, dict):
        return False
    if set(actual) - (set(expected) | {"id"}):
        return False
    for key, value in expected.items():
        if key != "config" and actual.get(key) != value:
            return False

    actual_config = actual.get("config")
    expected_config = expected.get("config")
    if not isinstance(actual_config, dict) or not isinstance(expected_config, dict):
        return False
    if any(actual_config.get(key) != value for key, value in expected_config.items()):
        return False
    normalized_defaults = {}
    if expected_config.get("id.token.claim") == "false":
        normalized_defaults["userinfo.token.claim"] = "false"
    if expected_config.get("access.token.claim") == "true":
        normalized_defaults["introspection.token.claim"] = "true"
    extra_config = set(actual_config) - set(expected_config)
    if extra_config - set(normalized_defaults):
        return False
    return all(actual_config.get(key) == value
               for key, value in normalized_defaults.items() if key in actual_config)


def mapper_issues(token: str, client: dict, spec: dict) -> list[str]:
    path = realm_path(f"/clients/{client['id']}/protocol-mappers/models")
    _, rows = request("GET", path, token)
    expected = expected_mappers(spec)
    actual = {row.get("name"): row for row in rows or []}
    issues = [f"unexpected mappers {sorted(set(actual) - set(expected))}"] if set(actual) - set(expected) else []
    issues.extend(f"missing mapper {name}" for name in sorted(set(expected) - set(actual)))
    issues.extend(f"mapper {name} has drift" for name in sorted(set(actual) & set(expected))
                  if not _mapper_matches(actual[name], expected[name]))
    return issues


def _names(rows: list[dict] | None) -> set[str]:
    return {row.get("name", "") for row in rows or []}


def authorization_services_disabled(token: str, client: dict) -> bool:
    """Treat omitted nullable flag as disabled only when the resource-server GET confirms absence."""
    client_label = client.get("clientId", "service client")
    field_present = "authorizationServicesEnabled" in client
    flag = client.get("authorizationServicesEnabled")
    if field_present and type(flag) is not bool:
        raise ProvisioningError(f"Refusing {client_label} recovery: authorizationServicesEnabled has an unexpected value.")
    if flag is True:
        raise ProvisioningError(f"Refusing {client_label} recovery: authorization services are enabled.")
    if client.get("authorizationSettings") is not None:
        raise ProvisioningError(f"Refusing {client_label} recovery: authorization settings are present.")

    path = realm_path(f"/clients/{client['id']}/authz/resource-server")
    try:
        request("GET", path, token)
    except KeycloakAPIError as error:
        if error.status_code == 404:
            return True
        raise ProvisioningError(f"Unable to confirm {client_label} authorization-service state safely.") from None
    raise ProvisioningError(f"Refusing {client_label} recovery: an authorization resource server exists.")


def inspect_dispatch_repair(token: str, client: dict, role: dict, default_role: str) -> tuple[dict, list[str]]:
    """Validate only the known dispatch partial state and return exact targeted actions."""
    spec = next(s for s in MANIFEST["clients"] if s["clientId"] == "zentro-dispatch-service")
    if role.get("name") != "SERVICE":
        raise ProvisioningError("Refusing dispatch repair: SERVICE realm role is unavailable.")
    if not authorization_services_disabled(token, client):
        raise ProvisioningError("Refusing dispatch repair: authorization services are enabled.")
    if not is_managed(client):
        raise ProvisioningError("Refusing dispatch repair: client is not tagged as managed by this tool.")
    want = desired_body(spec, None)
    mismatched = [key for key in ("protocol", "clientAuthenticatorType",
                                   "enabled", "publicClient", "bearerOnly", "standardFlowEnabled",
                                   "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled",
                                   "fullScopeAllowed") if client.get(key) != want[key]]
    if mismatched:
        raise ProvisioningError(f"Refusing dispatch repair: unexpected client configuration {mismatched}.")

    account_id = service_account_id(token, client["id"])
    mapping_path = realm_path(f"/users/{account_id}/role-mappings/realm")
    _, direct_rows = request("GET", mapping_path, token)
    direct = _names(direct_rows)
    allowed_direct = {frozenset({default_role}), frozenset({"SERVICE"}),
                      frozenset({default_role, "SERVICE"}), frozenset()}
    if frozenset(direct) not in allowed_direct:
        raise ProvisioningError(f"Refusing dispatch repair: unexpected direct realm mappings {sorted(direct)}.")

    scope_path = realm_path(f"/clients/{client['id']}/scope-mappings/realm")
    _, scope_rows = request("GET", scope_path, token)
    scope_names = _names(scope_rows)
    if scope_names not in (set(), {"SERVICE"}):
        raise ProvisioningError(f"Refusing dispatch repair: unexpected client scope mappings {sorted(scope_names)}.")

    roles, admin_roles = effective_roles(token, client["id"])
    if admin_roles:
        raise ProvisioningError("Refusing dispatch repair: service account has realm-management roles.")
    _, default_composites = request("GET", realm_path(
        f"/roles/{urllib.parse.quote(default_role, safe='')}/composites"), token)
    expected_effective = set()
    if default_role in direct:
        expected_effective.update(_names(default_composites))
        expected_effective.add(default_role)
    if "SERVICE" in direct:
        expected_effective.add("SERVICE")
    # Keycloak can omit some non-application composites from the effective-role
    # endpoint, so allow a subset of the default role's verified composites while
    # still rejecting any extra role and requiring the exact effective app roles.
    unexpected_effective = roles - expected_effective
    expected_app_roles = expected_effective & APP_ROLES
    actual_app_roles = roles & APP_ROLES
    if unexpected_effective or actual_app_roles != expected_app_roles:
        raise ProvisioningError(f"Refusing dispatch repair: unexpected effective roles {sorted(roles)}.")

    _, groups = request("GET", realm_path(f"/users/{account_id}/groups"), token)
    if groups:
        raise ProvisioningError("Refusing dispatch repair: service account has unexpected group memberships.")

    mapper_path = realm_path(f"/clients/{client['id']}/protocol-mappers/models")
    _, mapper_rows = request("GET", mapper_path, token)
    expected = expected_mappers(spec)
    actual_by_name = {row.get("name"): row for row in mapper_rows or []}
    if set(actual_by_name) - set(expected):
        raise ProvisioningError(f"Refusing dispatch repair: unexpected protocol mappers {sorted(set(actual_by_name) - set(expected))}.")
    for name, actual in actual_by_name.items():
        if not _mapper_matches(actual, expected[name]):
            raise ProvisioningError(f"Refusing dispatch repair: unexpected mapper configuration for {name}.")

    actions = []
    if default_role in direct:
        actions.append(f"remove direct service-account mapping {default_role}")
    if "SERVICE" not in direct:
        actions.append("assign SERVICE to dispatch service account")
    if "SERVICE" not in scope_names:
        actions.append("add SERVICE to dispatch client scope")
    for name in expected:
        if name not in actual_by_name:
            actions.append(f"add dispatch mapper {name}")
    return {"spec": spec, "accountId": account_id, "client": client}, actions


def inspect_tracking_repair(token: str, client: dict, role: dict,
                            default_role: str) -> tuple[dict, list[str]]:
    """Accept only the observed managed Tracking partial-create state."""
    client_id = "zentro-tracking-service"
    spec = next(s for s in MANIFEST["clients"] if s["clientId"] == client_id)
    if role.get("name") != "SERVICE":
        raise ProvisioningError("Refusing Tracking recovery: SERVICE realm role is unavailable.")
    if not is_managed(client):
        raise ProvisioningError("Refusing Tracking recovery: client is not tagged as managed by this tool.")
    if not authorization_services_disabled(token, client):
        raise ProvisioningError("Refusing Tracking recovery: authorization services are enabled.")

    want = desired_body(spec, None)
    settings = ("protocol", "clientAuthenticatorType", "enabled", "publicClient", "bearerOnly",
                "standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled",
                "serviceAccountsEnabled", "fullScopeAllowed")
    mismatched = [key for key in settings if client.get(key) != want[key]]
    if mismatched:
        raise ProvisioningError(f"Refusing Tracking recovery: unexpected client configuration {mismatched}.")

    account_id = service_account_id(token, client["id"])
    mapping_path = realm_path(f"/users/{account_id}/role-mappings/realm")
    _, direct_rows = request("GET", mapping_path, token)
    direct = _names(direct_rows)
    if direct != {default_role}:
        raise ProvisioningError(f"Refusing Tracking recovery: unexpected direct realm mappings {sorted(direct)}.")

    roles, admin_roles = effective_roles(token, client["id"])
    if admin_roles:
        raise ProvisioningError("Refusing Tracking recovery: service account has realm-management roles.")
    _, default_composites = request("GET", realm_path(
        f"/roles/{urllib.parse.quote(default_role, safe='')}/composites"), token)
    expected_effective = _names(default_composites) | {default_role}
    if default_role not in roles or roles - expected_effective or (roles & APP_ROLES) != {"CUSTOMER"}:
        raise ProvisioningError(f"Refusing Tracking recovery: unexpected effective roles {sorted(roles)}.")

    scope_path = realm_path(f"/clients/{client['id']}/scope-mappings/realm")
    _, scope_rows = request("GET", scope_path, token)
    scope = _names(scope_rows)
    if scope:
        raise ProvisioningError(f"Refusing Tracking recovery: unexpected client scope mappings {sorted(scope)}.")

    _, groups = request("GET", realm_path(f"/users/{account_id}/groups"), token)
    if groups:
        raise ProvisioningError("Refusing Tracking recovery: service account has unexpected group memberships.")

    mapper_path = realm_path(f"/clients/{client['id']}/protocol-mappers/models")
    _, mapper_rows = request("GET", mapper_path, token)
    expected_mappers_by_name = expected_mappers(spec)
    actual_by_name = {row.get("name"): row for row in mapper_rows or []}
    if set(actual_by_name) != set(expected_mappers_by_name):
        raise ProvisioningError(
            f"Refusing Tracking recovery: unexpected protocol mappers {sorted(actual_by_name)}.")
    for name, expected_mapper in expected_mappers_by_name.items():
        if not _mapper_matches(actual_by_name[name], expected_mapper):
            raise ProvisioningError(f"Refusing Tracking recovery: unexpected mapper configuration for {name}.")

    actions = [f"remove direct service-account mapping {default_role}",
               "assign SERVICE to tracking service account",
               "add SERVICE to tracking client scope",
               "preserve existing zentro-api audience mapper"]
    return {"spec": spec, "accountId": account_id, "client": client}, actions


def repair_tracking(client_id: str, dry_run: bool = False) -> int:
    global READ_ONLY
    if client_id != "zentro-tracking-service":
        raise ProvisioningError("Tracking recovery only accepts zentro-tracking-service.")
    prior_read_only = READ_ONLY
    if dry_run:
        READ_ONLY = True
    try:
        token = admin_token()
        role, default_role = realm_info(token)
        client = find_client(token, client_id)
        if not client:
            raise ProvisioningError("Refusing Tracking recovery: client does not exist.")
        state, actions = inspect_tracking_repair(token, client, role, default_role)
        secret = injected_secret(state["spec"], required=True)
        if dry_run:
            for action in actions:
                print(f"{client_id}: would {action}")
            print("Dry run only: no changes were made.")
            return 0
        try:
            assign_only_service_role(token, client["id"], role, default_role)
            print(verify_client(token, state["spec"], client["id"], secret))
        except Exception as error:
            print("Tracking recovery stopped; a subset of planned writes may have succeeded. "
                  "Inspect with --plan and --repair-tracking zentro-tracking-service --dry-run. "
                  "No automatic rollback was performed.", file=sys.stderr)
            raise ProvisioningError(f"Tracking recovery failed: {error}") from None
        return 0
    finally:
        READ_ONLY = prior_read_only


def repair_dispatch(client_id: str, dry_run: bool = False) -> int:
    global READ_ONLY
    if client_id != "zentro-dispatch-service":
        raise ProvisioningError("Dispatch repair only accepts zentro-dispatch-service.")
    prior_read_only = READ_ONLY
    if dry_run:
        READ_ONLY = True
    try:
        token = admin_token()
        role, default_role = realm_info(token)
        client = find_client(token, client_id)
        if not client:
            raise ProvisioningError("Refusing dispatch repair: client does not exist.")
        state, actions = inspect_dispatch_repair(token, client, role, default_role)
        if dry_run:
            for action in actions:
                print(f"{client_id}: would {action}")
            if not actions:
                print(f"{client_id}: already matches repair target")
            print("Dry run only: no changes were made.")
            return 0

        secret = injected_secret(state["spec"], required=True)
        if not actions:
            print(verify_client(token, state["spec"], client["id"], secret))
            return 0
        try:
            configure_mappers(token, client["id"], state["spec"])
            assign_only_service_role(token, client["id"], role, default_role)
            print(verify_client(token, state["spec"], client["id"], secret))
        except Exception as error:
            print("Dispatch repair stopped; a subset of planned writes may have succeeded. "
                  "Inspect with --plan and --repair-dispatch zentro-dispatch-service --dry-run. "
                  "No automatic rollback was performed.", file=sys.stderr)
            raise ProvisioningError(f"Dispatch repair failed: {error}") from None
        return 0
    finally:
        READ_ONLY = prior_read_only


def create_missing(client_ids: list[str], dry_run: bool = False) -> int:
    global READ_ONLY
    allowed = {"zentro-tracking-service", "zentro-notification-service"}
    if not client_ids or len(client_ids) != len(set(client_ids)) or set(client_ids) != allowed:
        raise ProvisioningError("Create-missing requires exactly one explicit selection of tracking and notification clients.")
    prior_read_only = READ_ONLY
    if dry_run:
        READ_ONLY = True
    created: list[str] = []
    attempted: list[str] = []
    try:
        token = admin_token()
        role, default_role = realm_info(token)
        manifest_by_id = {s["clientId"]: s for s in MANIFEST["clients"]}
        clients = {cid: find_client(token, cid) for cid in manifest_by_id}
        present_targets = sorted(cid for cid in allowed if clients.get(cid))
        if present_targets:
            raise ProvisioningError(f"Refusing create-missing: target client(s) already exist: {present_targets}.")

        for cid in ("zentro-parcel-service", "zentro-dispatch-service"):
            client = clients.get(cid)
            if not client or not is_managed(client):
                raise ProvisioningError(f"Refusing create-missing: preserved client {cid} is missing or unmanaged.")
            issues = drift(token, client, manifest_by_id[cid])
            if issues:
                raise ProvisioningError(f"Refusing create-missing: preserved client {cid} has drift: {issues}.")
            verify_client(token, manifest_by_id[cid], client["id"], injected_secret(manifest_by_id[cid], True))

        secrets = {cid: injected_secret(manifest_by_id[cid], True) for cid in sorted(allowed)}
        if dry_run:
            for cid in sorted(allowed):
                print(f"{cid}: would CREATE (SERVICE only, aud={MANIFEST['audience']}, "
                      f"podPermissions={sorted(manifest_by_id[cid].get('podPermissions', []))})")
            print("Dry run only: no changes were made.")
            return 0

        for cid in client_ids:
            spec = manifest_by_id[cid]
            attempted.append(cid)
            request("POST", realm_path("/clients"), token, body=desired_body(spec, secrets[cid]))
            created.append(cid)
            client = find_client(token, cid)
            if not client:
                raise ProvisioningError(f"Keycloak did not return newly created client {cid}.")
            configure_mappers(token, client["id"], spec)
            assign_only_service_role(token, client["id"], role, default_role)
            print(verify_client(token, spec, client["id"], secrets[cid]))
        return 0
    except Exception as error:
        if created and not dry_run:
            print(f"Create-missing stopped; POST succeeded for {created}; attempted clients: {attempted}. "
                  "Inspect with --plan; no automatic deletion was performed.", file=sys.stderr)
        elif attempted and not dry_run:
            print(f"Create-missing stopped after attempting {attempted}. The server result may be uncertain; "
                  "inspect with --plan. No automatic deletion was performed.", file=sys.stderr)
        raise
    finally:
        READ_ONLY = prior_read_only


def create_notification_only(dry_run: bool = False) -> int:
    """Create only Notification after Parcel, Dispatch, and Tracking verify cleanly."""
    global READ_ONLY
    prior_read_only = READ_ONLY
    if dry_run:
        READ_ONLY = True
    attempted = False
    created = False
    try:
        token = admin_token()
        role, default_role = realm_info(token)
        manifest_by_id = {s["clientId"]: s for s in MANIFEST["clients"]}
        preserved_ids = ("zentro-parcel-service", "zentro-dispatch-service", "zentro-tracking-service")
        target_id = "zentro-notification-service"
        clients = {cid: find_client(token, cid) for cid in (*preserved_ids, target_id)}
        if clients[target_id]:
            raise ProvisioningError("Refusing Notification-only creation: zentro-notification-service already exists.")

        for cid in preserved_ids:
            client = clients[cid]
            spec = manifest_by_id[cid]
            if not client or not is_managed(client):
                raise ProvisioningError(f"Refusing Notification-only creation: preserved client {cid} is missing or unmanaged.")
            issues = drift(token, client, spec)
            issues.extend(mapper_issues(token, client, spec))
            if issues:
                raise ProvisioningError(f"Refusing Notification-only creation: preserved client {cid} has drift: {issues}.")
            verify_client(token, spec, client["id"], injected_secret(spec, required=True))

        spec = manifest_by_id[target_id]
        secret = injected_secret(spec, required=True)
        if dry_run:
            print(f"{target_id}: would CREATE (SERVICE only, aud={MANIFEST['audience']}, podPermissions=[])")
            print("Dry run only: no changes were made.")
            return 0

        attempted = True
        request("POST", realm_path("/clients"), token, body=desired_body(spec, secret))
        created = True
        client = find_client(token, target_id)
        if not client:
            raise ProvisioningError("Keycloak did not return newly created Notification client.")
        configure_mappers(token, client["id"], spec)
        assign_only_service_role(token, client["id"], role, default_role)
        print(verify_client(token, spec, client["id"], secret))
        return 0
    except Exception as error:
        if attempted:
            if created:
                print("Notification-only creation stopped after Notification was created; "
                      "inspect with --plan. No automatic deletion was performed.", file=sys.stderr)
            else:
                print("Notification-only creation stopped; the client POST result may be uncertain. "
                      "Inspect with --plan. No automatic deletion was performed.", file=sys.stderr)
        raise
    finally:
        READ_ONLY = prior_read_only


def assign_only_service_role(token: str, client_internal_id: str, role: dict, default_role: str):
    """Service account gets SERVICE only: other app roles and the realm default-roles composite
    (which carries CUSTOMER and account roles) are removed; realm-management roles are refused."""
    account_id = service_account_id(token, client_internal_id)
    mapping_path = realm_path(f"/users/{account_id}/role-mappings/realm")
    _, current = request("GET", mapping_path, token)
    remove = [entry for entry in current or []
              if (entry.get("name") in APP_ROLES and entry.get("name") != "SERVICE") or entry.get("name") == default_role]
    if remove:
        request("DELETE", mapping_path, token, body=remove)
    if "SERVICE" not in {entry.get("name") for entry in current or []}:
        request("POST", mapping_path, token, body=[role])

    scope_path = realm_path(f"/clients/{client_internal_id}/scope-mappings/realm")
    _, scoped = request("GET", scope_path, token)
    remove_scopes = [entry for entry in scoped or [] if entry.get("name") in APP_ROLES and entry.get("name") != "SERVICE"]
    if remove_scopes:
        request("DELETE", scope_path, token, body=remove_scopes)
    if "SERVICE" not in {entry.get("name") for entry in scoped or []}:
        request("POST", scope_path, token, body=[role])

    _, admin_roles = effective_roles(token, client_internal_id)
    if admin_roles:
        raise ProvisioningError(f"Service account of {client_internal_id} holds realm-management roles; refusing.")


# --- verification -----------------------------------------------------------------------------

def token_claims(client_id: str, secret: str) -> dict:
    _, response = request("POST", f"/realms/{urllib.parse.quote(REALM, safe='')}/protocol/openid-connect/token", form={
        "grant_type": "client_credentials", "client_id": client_id, "client_secret": secret,
    })
    token = (response or {}).get("access_token", "")
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        raise ProvisioningError(f"Keycloak did not issue a valid access token for {client_id}.") from None


def verify_client(token: str, spec: dict, client_internal_id: str, secret: str | None) -> str:
    cid = spec["clientId"]
    roles, admin_roles = effective_roles(token, client_internal_id)
    if roles & APP_ROLES != {"SERVICE"} or any(r.startswith("default-roles-") for r in roles):
        raise ProvisioningError(f"Effective service-account roles are not SERVICE-only for {cid}.")
    if admin_roles:
        raise ProvisioningError(f"Service account of {cid} holds realm-management roles.")
    if secret is None:
        return f"{cid}: effective roles SERVICE-only (token not checked: no secret injected)"
    claims = token_claims(cid, secret)
    audience = claims.get("aud", [])
    if isinstance(audience, str):
        audience = [audience]
    token_roles = set(claims.get("realm_access", {}).get("roles", []))
    if claims.get("azp") != cid or MANIFEST["audience"] not in audience or token_roles & APP_ROLES != {"SERVICE"}:
        raise ProvisioningError(f"Issued token claims failed least-privilege verification for {cid}.")
    if "CUSTOMER" in token_roles or ADMIN_CLIENT in (claims.get("resource_access") or {}):
        raise ProvisioningError(f"Issued token for {cid} carries CUSTOMER or realm-management access.")
    expected = sorted(spec.get("podPermissions", []))
    received = claims.get("pod_permissions", "")
    if isinstance(received, str):
        received = received.split()
    if sorted(received or []) != expected:
        raise ProvisioningError(f"POD permissions claim is incorrect for {cid}.")
    return f"{cid}: token verified (SERVICE only, aud={MANIFEST['audience']}, podPermissions={expected})"


def injected_secret(spec: dict, required: bool) -> str | None:
    secret = os.environ.get(spec["secretEnv"], "")
    if not secret:
        if required:
            raise ProvisioningError(f"{spec['secretEnv']} must be injected.")
        return None
    if len(secret) < MIN_SECRET_LENGTH:
        raise ProvisioningError(f"{spec['secretEnv']} must be at least {MIN_SECRET_LENGTH} characters.")
    return secret


# --- modes ------------------------------------------------------------------------------------

def plan() -> int:
    token = admin_token()
    realm_info(token)
    for spec in MANIFEST["clients"]:
        client = find_client(token, spec["clientId"])
        if not client:
            print(f"{spec['clientId']}: would CREATE (SERVICE only, aud={MANIFEST['audience']}, "
                  f"podPermissions={sorted(spec.get('podPermissions', []))}; secret from {spec['secretEnv']})")
            continue
        issues = drift(token, client, spec)
        state = "managed" if is_managed(client) else "UNMANAGED"
        print(f"{spec['clientId']}: EXISTS ({state}); " + (f"drift: {'; '.join(issues)}" if issues else "no drift"))
    print("Plan only: no changes were made.")
    return 0


def verify() -> int:
    token = admin_token()
    realm_info(token)
    for spec in MANIFEST["clients"]:
        client = find_client(token, spec["clientId"])
        if not client:
            raise ProvisioningError(f"{spec['clientId']} does not exist.")
        print(verify_client(token, spec, client["id"], injected_secret(spec, required=False)))
    return 0


def apply(update_existing: bool = False, rotate: set[str] | None = None) -> int:
    rotate = set(rotate or ())
    manifest_ids = {s["clientId"] for s in MANIFEST["clients"]}
    if rotate - manifest_ids:
        raise ProvisioningError(f"Unknown client(s) for rotation: {sorted(rotate - manifest_ids)}")
    token = admin_token()
    role, default_role = realm_info(token)

    # Preflight everything before the first write.
    existing = {s["clientId"]: find_client(token, s["clientId"]) for s in MANIFEST["clients"]}
    present = sorted(cid for cid, c in existing.items() if c)
    if present and not update_existing and not rotate:
        raise ProvisioningError(f"Create-only mode: client(s) already exist: {present}. Nothing was changed. "
                                "Use --plan, then --update-existing or --rotate-secret with explicit approval.")
    unmanaged = sorted(cid for cid, c in existing.items() if c and not is_managed(c))
    if unmanaged:
        raise ProvisioningError(f"Refusing to modify unmanaged existing client(s): {unmanaged}. Nothing was changed.")
    missing_for_rotation = sorted(cid for cid in rotate if not existing.get(cid))
    if missing_for_rotation:
        raise ProvisioningError(f"Cannot rotate missing client(s): {missing_for_rotation}. Nothing was changed.")
    secrets = {}
    for spec in MANIFEST["clients"]:
        cid = spec["clientId"]
        needed = existing[cid] is None or cid in rotate
        secrets[cid] = injected_secret(spec, required=needed)

    created: list[str] = []
    try:
        for spec in MANIFEST["clients"]:
            cid = spec["clientId"]
            client = existing[cid]
            if client is None:
                request("POST", realm_path("/clients"), token, body=desired_body(spec, secrets[cid]))
                client = find_client(token, cid)
                if not client:
                    raise ProvisioningError(f"Keycloak did not create client {cid}.")
                created.append(cid)
            elif update_existing or cid in rotate:
                body = {**client, **desired_body(spec, secrets[cid] if cid in rotate else None)}
                if cid not in rotate:
                    body.pop("secret", None)  # never replace an existing secret implicitly
                body["attributes"] = {**(client.get("attributes") or {}), MANAGED_ATTRIBUTE: MANAGED_VALUE}
                request("PUT", realm_path(f"/clients/{client['id']}"), token, body=body)
            if client is not None and (existing[cid] is None or update_existing or cid in rotate):
                configure_mappers(token, client["id"], spec)
                assign_only_service_role(token, client["id"], role, default_role)
            secret_for_check = secrets[cid] if (existing[cid] is None or cid in rotate) else secrets.get(cid)
            print(verify_client(token, spec, client["id"], secret_for_check))
    except Exception:
        if created:
            print(f"Partial failure. Clients created in this run: {created}. Inspect with --plan; to undo only these, "
                  f"run --remove-managed {' --remove-managed '.join(created)} --confirm-remove after approval.",
                  file=sys.stderr)
        raise
    return 0


def remove_managed(client_ids: list[str], confirmed: bool) -> int:
    if not confirmed:
        raise ProvisioningError("--remove-managed requires --confirm-remove.")
    manifest_ids = {s["clientId"] for s in MANIFEST["clients"]}
    outside = sorted(set(client_ids) - manifest_ids)
    if outside:
        raise ProvisioningError(f"Refusing to remove clients outside the manifest: {outside}")
    token = admin_token()
    for cid in client_ids:
        client = find_client(token, cid)
        if not client:
            print(f"{cid}: not present")
            continue
        if not is_managed(client):
            raise ProvisioningError(f"Refusing to remove {cid}: not tagged as managed by this tool.")
        request("DELETE", realm_path(f"/clients/{client['id']}"), token)
        print(f"{cid}: removed")
    return 0


def main() -> int:
    """Create-only provisioning (used by wrappers and the disposable e2e stack)."""
    if REALM != MANIFEST["realm"]:
        raise ProvisioningError(f"This manifest only provisions realm {MANIFEST['realm']}.")
    return apply()


def cli(argv: list[str] | None = None) -> int:
    global READ_ONLY
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--plan", action="store_true", help="read-only plan")
    mode.add_argument("--verify", action="store_true", help="read-only token and role verification")
    mode.add_argument("--update-existing", action="store_true", help="reconcile managed clients; never changes secrets")
    mode.add_argument("--remove-managed", action="append", metavar="CLIENT_ID", help="recovery: remove a managed manifest client")
    mode.add_argument("--repair-dispatch", metavar="CLIENT_ID", help="repair only the managed dispatch partial-provisioning state")
    mode.add_argument("--repair-tracking", metavar="CLIENT_ID", help="repair only the managed tracking partial-provisioning state")
    mode.add_argument("--create-missing", action="append", metavar="CLIENT_ID", help="create only explicit tracking and notification clients")
    mode.add_argument("--create-notification", action="store_true", help="create only Notification after preserved clients verify cleanly")
    parser.add_argument("--dry-run", action="store_true", help="read-only preflight for a recovery mode")
    parser.add_argument("--rotate-secret", action="append", metavar="CLIENT_ID", default=[], help="explicitly rotate a managed client's secret")
    parser.add_argument("--confirm-remove", action="store_true")
    args = parser.parse_args(argv)
    if REALM != MANIFEST["realm"]:
        raise ProvisioningError(f"This manifest only provisions realm {MANIFEST['realm']}.")
    recovery_mode = (args.repair_dispatch is not None or args.repair_tracking is not None
                     or args.create_missing is not None or args.create_notification)
    if args.dry_run and not recovery_mode:
        parser.error("--dry-run is only available with a recovery mode.")
    if recovery_mode and (args.rotate_secret or args.confirm_remove):
        parser.error("Recovery modes cannot be combined with secret rotation or removal flags.")
    if args.repair_dispatch is not None:
        return repair_dispatch(args.repair_dispatch, dry_run=args.dry_run)
    if args.repair_tracking is not None:
        return repair_tracking(args.repair_tracking, dry_run=args.dry_run)
    if args.create_missing is not None:
        return create_missing(args.create_missing, dry_run=args.dry_run)
    if args.create_notification:
        return create_notification_only(dry_run=args.dry_run)
    if args.rotate_secret and (args.plan or args.verify or args.remove_managed):
        parser.error("--rotate-secret cannot be combined with read-only or removal modes.")
    if args.plan or args.verify:
        READ_ONLY = True
        return plan() if args.plan else verify()
    if args.remove_managed:
        return remove_managed(args.remove_managed, args.confirm_remove)
    return apply(update_existing=args.update_existing, rotate=set(args.rotate_secret))


if __name__ == "__main__":
    try:
        sys.exit(cli())
    except Exception as error:
        print(f"service client provisioning failed: {error}", file=sys.stderr)
        sys.exit(1)
