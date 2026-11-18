#!/usr/bin/env python3
"""Idempotently provision least-privilege Zentro service clients in Keycloak.

Secrets are read only from the environment. The script never prints credentials or tokens.
"""
from __future__ import annotations

import base64
import json
import os
import sys
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


def request(method: str, path: str, token: str | None = None, body=None, form=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
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
        raise RuntimeError(f"Keycloak API returned HTTP {error.code} for {method} {path}") from None


def realm_path(suffix: str) -> str:
    return f"/admin/realms/{urllib.parse.quote(REALM, safe='')}{suffix}"


def admin_token() -> str:
    if not ADMIN_PASSWORD:
        raise RuntimeError("Set KC_ADMIN_PASSWORD or KC_BOOTSTRAP_ADMIN_PASSWORD before provisioning clients.")
    _, result = request("POST", "/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USER, "password": ADMIN_PASSWORD,
    })
    token = result.get("access_token") if isinstance(result, dict) else None
    if not token:
        raise RuntimeError("Keycloak did not return an administrator access token.")
    return token


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


def assign_only_service_role(token: str, client_internal_id: str, role: dict):
    account_path = realm_path(f"/clients/{client_internal_id}/service-account-user")
    _, account = request("GET", account_path, token)
    account_id = account["id"]
    mapping_path = realm_path(f"/users/{account_id}/role-mappings/realm")
    _, current = request("GET", mapping_path, token)
    remove = [entry for entry in current or [] if entry.get("name") in APP_ROLES and entry.get("name") != "SERVICE"]
    if remove:
        request("DELETE", mapping_path, token, body=remove)
    names = {entry.get("name") for entry in current or []}
    if "SERVICE" not in names:
        request("POST", mapping_path, token, body=[role])

    scope_path = realm_path(f"/clients/{client_internal_id}/scope-mappings/realm")
    _, scoped = request("GET", scope_path, token)
    remove_scopes = [entry for entry in scoped or [] if entry.get("name") in APP_ROLES and entry.get("name") != "SERVICE"]
    if remove_scopes:
        request("DELETE", scope_path, token, body=remove_scopes)
    if "SERVICE" not in {entry.get("name") for entry in scoped or []}:
        request("POST", scope_path, token, body=[role])


def client_for(token: str, spec: dict) -> tuple[str, dict]:
    client_id = spec["clientId"]
    secret = os.environ.get(spec["secretEnv"], "")
    if len(secret) < 32:
        raise RuntimeError(f"{spec['secretEnv']} must be injected with at least 32 characters.")
    query = urllib.parse.urlencode({"clientId": client_id})
    _, matches = request("GET", realm_path("/clients") + "?" + query, token)
    body = {
        "clientId": client_id,
        "name": client_id,
        "description": spec["description"],
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "bearerOnly": False,
        "clientAuthenticatorType": "client-secret",
        "secret": secret,
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True,
        "authorizationServicesEnabled": False,
        "fullScopeAllowed": False,
    }
    if matches:
        internal_id = matches[0]["id"]
        request("PUT", realm_path(f"/clients/{internal_id}"), token, body={**matches[0], **body})
    else:
        request("POST", realm_path("/clients"), token, body=body)
        _, matches = request("GET", realm_path("/clients") + "?" + query, token)
        if not matches:
            raise RuntimeError(f"Keycloak did not create client {client_id}.")
        internal_id = matches[0]["id"]

    mapper_upsert(token, internal_id, {
        "name": "Zentro API audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {"included.custom.audience": MANIFEST["audience"], "id.token.claim": "false", "access.token.claim": "true"},
    })
    permissions = spec.get("podPermissions")
    if permissions:
        mapper_upsert(token, internal_id, {
            "name": "Zentro POD permissions",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-hardcoded-claim-mapper",
            "consentRequired": False,
            "config": {"claim.name": "pod_permissions", "claim.value": " ".join(permissions),
                       "jsonType.label": "String", "id.token.claim": "false", "access.token.claim": "true",
                       "userinfo.token.claim": "false"},
        })
    else:
        remove_mapper(token, internal_id, "Zentro POD permissions")
    return internal_id, {**body, "podPermissions": permissions or []}


def token_claims(client_id: str, secret: str) -> dict:
    _, response = request("POST", f"/realms/{urllib.parse.quote(REALM, safe='')}/protocol/openid-connect/token", form={
        "grant_type": "client_credentials", "client_id": client_id, "client_secret": secret,
    })
    token = response.get("access_token", "")
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        raise RuntimeError(f"Keycloak did not issue a valid access token for {client_id}.") from None


def main():
    if REALM != MANIFEST["realm"]:
        raise RuntimeError(f"This manifest only provisions realm {MANIFEST['realm']}.")
    token = admin_token()
    _, roles = request("GET", realm_path("/roles"), token)
    role = next((item for item in roles or [] if item.get("name") == MANIFEST["serviceRole"]), None)
    if not role:
        raise RuntimeError("Realm is missing the SERVICE role.")
    for spec in MANIFEST["clients"]:
        internal_id, _ = client_for(token, spec)
        assign_only_service_role(token, internal_id, role)
        secret = os.environ[spec["secretEnv"]]
        claims = token_claims(spec["clientId"], secret)
        audience = claims.get("aud", [])
        if isinstance(audience, str):
            audience = [audience]
        app_roles = set(claims.get("realm_access", {}).get("roles", [])) & APP_ROLES
        if claims.get("azp") != spec["clientId"] or MANIFEST["audience"] not in audience or app_roles != {"SERVICE"}:
            raise RuntimeError(f"Issued token claims failed least-privilege verification for {spec['clientId']}.")
        expected_permissions = sorted(spec.get("podPermissions", []))
        received = claims.get("pod_permissions", "")
        if isinstance(received, str):
            received = received.split()
        if sorted(received or []) != expected_permissions:
            raise RuntimeError(f"POD permissions claim is incorrect for {spec['clientId']}.")
        print(f"Provisioned and verified {spec['clientId']}: SERVICE, aud={MANIFEST['audience']}, podPermissions={expected_permissions}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"service client provisioning failed: {error}", file=sys.stderr)
        sys.exit(1)
