"""Offline safety tests for provision_service_clients.py (no Keycloak, no network, synthetic values only)."""
import contextlib
import io
import unittest
import time
import urllib.error
import base64
import json
from unittest.mock import patch

import provision_service_clients as p

SYNTHETIC = "synthetic-test-only-" * 3
REAL_CONFIGURE_MAPPERS = p.configure_mappers
REAL_ASSIGN_SERVICE_ROLE = p.assign_only_service_role
ALL_SECRETS = {s["secretEnv"]: SYNTHETIC for s in p.MANIFEST["clients"]}
IDS = [s["clientId"] for s in p.MANIFEST["clients"]]


def managed(cid):
    return {"id": f"id-{cid}", "clientId": cid, "attributes": {p.MANAGED_ATTRIBUTE: p.MANAGED_VALUE}}


class Base(unittest.TestCase):
    def setUp(self):
        self.writes = []
        patches = [
            patch.object(p, "admin_token", return_value="admin-token"),
            patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")),
            patch.object(p, "configure_mappers"),
            patch.object(p, "assign_only_service_role"),
            patch.object(p, "verify_client", return_value="verified"),
            patch.object(p, "READ_ONLY", False),
            patch.object(p, "_ADMIN_TOKEN_CACHE", None),
            patch.object(p, "_ADMIN_TOKEN_EXPIRY", None),
        ]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)

    def record(self, method, path, token=None, body=None, form=None):
        self.writes.append((method, path, body))
        return 201, None

    def quietly(self, fn, *args, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
            try:
                return fn(*args, **kwargs), err.getvalue()
            except p.ProvisioningError as error:
                return error, err.getvalue()


class ReadOnlyGuardTests(unittest.TestCase):
    def test_read_only_refuses_writes_before_any_network_call(self):
        with patch.object(p, "READ_ONLY", True), patch("urllib.request.urlopen") as net:
            for method in ("POST", "PUT", "DELETE"):
                with self.assertRaises(p.ProvisioningError):
                    p.request(method, p.realm_path("/clients"), "t", body={})
            net.assert_not_called()

    def test_read_only_allows_token_endpoint(self):
        self.assertTrue(p._is_token_endpoint("/realms/master/protocol/openid-connect/token"))


class AdminTokenRetryTests(unittest.TestCase):
    def setUp(self):
        self.patches = [patch.object(p, "READ_ONLY", False),
                        patch.object(p, "_ADMIN_TOKEN_CACHE", None),
                        patch.object(p, "_ADMIN_TOKEN_EXPIRY", None)]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    @staticmethod
    def response():
        from unittest.mock import MagicMock
        response = MagicMock()
        response.status = 200
        response.read.return_value = b"{}"
        response.__enter__.return_value = response
        return response

    @staticmethod
    def unauthorized():
        return urllib.error.HTTPError("http://keycloak/admin", 401, "Unauthorized", {}, io.BytesIO())

    def test_expiring_admin_token_is_refreshed_before_read(self):
        p._ADMIN_TOKEN_CACHE = "expired-soon"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 2
        def refresh_cache(force_refresh=False):
            p._ADMIN_TOKEN_CACHE = "fresh-admin-token"
            p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
            return p._ADMIN_TOKEN_CACHE
        with patch.object(p, "admin_token", side_effect=refresh_cache) as refresh, \
             patch("urllib.request.urlopen", return_value=self.response()) as urlopen:
            p.request("GET", p.realm_path("/clients"), "expired-soon")
        refresh.assert_called_once_with(force_refresh=True)
        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_header("Authorization"), "Bearer fresh-admin-token")

    def test_short_lived_admin_token_is_refreshed_before_post(self):
        p._ADMIN_TOKEN_CACHE = "short-lived-admin-token"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 2
        def refresh_cache(force_refresh=False):
            p._ADMIN_TOKEN_CACHE = "fresh-admin-token"
            p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
            return p._ADMIN_TOKEN_CACHE
        with patch.object(p, "admin_token", side_effect=refresh_cache) as refresh, \
             patch("urllib.request.urlopen", return_value=self.response()) as urlopen:
            p.request("POST", p.realm_path("/clients"), "stale-local-token", body={"clientId": "synthetic"})
        refresh.assert_called_once_with(force_refresh=True)
        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_header("Authorization"), "Bearer fresh-admin-token")

    def test_mutation_refuses_token_without_verifiable_expiry(self):
        p._ADMIN_TOKEN_CACHE = "short-lived-admin-token"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 2
        def refresh_without_expiry(force_refresh=False):
            p._ADMIN_TOKEN_CACHE = "opaque-token"
            p._ADMIN_TOKEN_EXPIRY = None
            return p._ADMIN_TOKEN_CACHE
        with patch.object(p, "admin_token", side_effect=refresh_without_expiry), \
             patch("urllib.request.urlopen") as urlopen:
            with self.assertRaisesRegex(p.ProvisioningError, "expiry is not safely verifiable"):
                p.request("POST", p.realm_path("/clients"), "stale-local-token", body={})
        urlopen.assert_not_called()

    def test_authenticated_get_retries_once_after_401(self):
        p._ADMIN_TOKEN_CACHE = "initial-admin-token"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
        def refresh_cache(force_refresh=False):
            p._ADMIN_TOKEN_CACHE = "fresh-admin-token"
            p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
            return p._ADMIN_TOKEN_CACHE
        with patch.object(p, "admin_token", side_effect=refresh_cache) as refresh, \
             patch("urllib.request.urlopen", side_effect=[self.unauthorized(), self.response()]) as urlopen:
            status, result = p.request("GET", p.realm_path("/clients"), "initial-admin-token")
        self.assertEqual((status, result), (200, {}))
        self.assertEqual(urlopen.call_count, 2)
        refresh.assert_called_once_with(force_refresh=True)
        retry_request = urlopen.call_args_list[1].args[0]
        self.assertEqual(retry_request.get_header("Authorization"), "Bearer fresh-admin-token")

    def test_get_retry_is_bounded_to_one_attempt(self):
        p._ADMIN_TOKEN_CACHE = "initial-admin-token"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
        def refresh_cache(force_refresh=False):
            p._ADMIN_TOKEN_CACHE = "fresh-admin-token"
            p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
            return p._ADMIN_TOKEN_CACHE
        with patch.object(p, "admin_token", side_effect=refresh_cache) as refresh, \
             patch("urllib.request.urlopen", side_effect=[self.unauthorized(), self.unauthorized()]) as urlopen:
            with self.assertRaises(p.KeycloakAPIError) as raised:
                p.request("GET", p.realm_path("/clients"), "initial-admin-token")
        self.assertEqual(raised.exception.status_code, 401)
        self.assertEqual(urlopen.call_count, 2)
        refresh.assert_called_once_with(force_refresh=True)

    def test_post_put_delete_are_never_retried_after_401(self):
        p._ADMIN_TOKEN_CACHE = "current-admin-token"
        p._ADMIN_TOKEN_EXPIRY = int(time.time()) + 60
        for method in ("POST", "PUT", "DELETE"):
            with self.subTest(method=method), \
                 patch.object(p, "admin_token") as refresh, \
                 patch("urllib.request.urlopen", side_effect=self.unauthorized()) as urlopen:
                with self.assertRaises(p.KeycloakAPIError):
                    p.request(method, p.realm_path("/clients"), "current-admin-token", body={})
                self.assertEqual(urlopen.call_count, 1)
                refresh.assert_not_called()


class MapperRepresentationTests(unittest.TestCase):
    def setUp(self):
        self.dispatch = next(s for s in p.MANIFEST["clients"]
                             if s["clientId"] == "zentro-dispatch-service")
        self.expected = p.expected_mappers(self.dispatch)

    def test_declared_audience_and_pod_mapper_configurations_match(self):
        for mapper in self.expected.values():
            with self.subTest(name=mapper["name"]):
                self.assertTrue(p._mapper_matches(dict(mapper), mapper))
        audience = self.expected["Zentro API audience"]
        self.assertEqual(audience["protocolMapper"], "oidc-audience-mapper")
        self.assertEqual(audience["config"]["included.custom.audience"], "zentro-api")
        pod = self.expected["Zentro POD permissions"]
        self.assertEqual(pod["protocolMapper"], "oidc-hardcoded-claim-mapper")
        self.assertEqual(pod["config"]["claim.name"], "pod_permissions")
        self.assertEqual(pod["config"]["claim.value"], "pod:read pod:update")

    def test_keycloak_introspection_default_is_accepted(self):
        for mapper in self.expected.values():
            config = {**mapper["config"], "introspection.token.claim": "true"}
            if "id.token.claim" in mapper["config"]:
                config["userinfo.token.claim"] = mapper["config"]["id.token.claim"]
            normalized = {**mapper, "id": "keycloak-assigned-id", "config": config}
            with self.subTest(name=mapper["name"]):
                self.assertTrue(p._mapper_matches(normalized, mapper))

    def test_unexpected_introspection_value_is_rejected(self):
        mapper = self.expected["Zentro API audience"]
        for value in ("false", False, "TRUE", None):
            actual = {**mapper, "config": {**mapper["config"], "introspection.token.claim": value}}
            with self.subTest(value=value):
                self.assertFalse(p._mapper_matches(actual, mapper))
        audience = self.expected["Zentro API audience"]
        wrong_userinfo = {**audience, "config": {**audience["config"],
                                                   "userinfo.token.claim": "true"}}
        self.assertFalse(p._mapper_matches(wrong_userinfo, audience))

    def test_unexpected_mapper_fields_are_rejected(self):
        mapper = self.expected["Zentro API audience"]
        extra_config = {**mapper, "config": {**mapper["config"], "unexpected.setting": "true"}}
        extra_top_level = {**mapper, "unexpectedField": "true"}
        self.assertFalse(p._mapper_matches(extra_config, mapper))
        self.assertFalse(p._mapper_matches(extra_top_level, mapper))

    def test_audience_and_pod_permission_drift_are_rejected(self):
        audience = self.expected["Zentro API audience"]
        wrong_audience = {**audience, "config": {**audience["config"],
                                                   "included.custom.audience": "other-api"}}
        pod = self.expected["Zentro POD permissions"]
        wrong_permissions = {**pod, "config": {**pod["config"],
                                                 "claim.value": "pod:read pod:delete"}}
        self.assertFalse(p._mapper_matches(wrong_audience, audience))
        self.assertFalse(p._mapper_matches(wrong_permissions, pod))

    def test_token_verification_rejects_customer_and_unauthorized_roles(self):
        spec = self.dispatch
        valid_claims = {
            "azp": spec["clientId"], "aud": [p.MANIFEST["audience"]],
            "realm_access": {"roles": ["SERVICE"]}, "resource_access": {},
            "pod_permissions": "pod:read pod:update",
        }
        with patch.object(p, "effective_roles", return_value=({"SERVICE"}, [])), \
             patch.object(p, "token_claims", return_value=valid_claims):
            self.assertIn("token verified", p.verify_client("admin", spec, "dispatch", SYNTHETIC))

        invalid_claims = (
            {**valid_claims, "realm_access": {"roles": ["SERVICE", "CUSTOMER"]}},
            {**valid_claims, "realm_access": {"roles": ["SERVICE", "MERCHANT"]}},
            {**valid_claims, "resource_access": {p.ADMIN_CLIENT: {"roles": ["manage-clients"]}}},
        )
        for claims in invalid_claims:
            with self.subTest(claims=claims), \
                 patch.object(p, "effective_roles", return_value=({"SERVICE"}, [])), \
                 patch.object(p, "token_claims", return_value=claims):
                with self.assertRaises(p.ProvisioningError):
                    p.verify_client("admin", spec, "dispatch", SYNTHETIC)

    def test_token_verification_rejects_wrong_audience_or_pod_permissions(self):
        spec = self.dispatch
        base = {
            "azp": spec["clientId"], "aud": [p.MANIFEST["audience"]],
            "realm_access": {"roles": ["SERVICE"]}, "resource_access": {},
            "pod_permissions": "pod:read pod:update",
        }
        invalid_claims = (
            {**base, "aud": ["other-api"]},
            {**base, "pod_permissions": "pod:read pod:delete"},
        )
        for claims in invalid_claims:
            with self.subTest(claims=claims), \
                 patch.object(p, "effective_roles", return_value=({"SERVICE"}, [])), \
                 patch.object(p, "token_claims", return_value=claims):
                with self.assertRaises(p.ProvisioningError):
                    p.verify_client("admin", spec, "dispatch", SYNTHETIC)

    def test_effective_unauthorized_roles_and_admin_roles_are_rejected(self):
        spec = self.dispatch
        with patch.object(p, "effective_roles", return_value=({"SERVICE", "CUSTOMER"}, [])):
            with self.assertRaises(p.ProvisioningError):
                p.verify_client("admin", spec, "dispatch", SYNTHETIC)
        with patch.object(p, "effective_roles", return_value=({"SERVICE"}, ["manage-clients"])):
            with self.assertRaises(p.ProvisioningError):
                p.verify_client("admin", spec, "dispatch", SYNTHETIC)


class AuthorizationServicesPreflightTests(unittest.TestCase):
    def test_explicit_false_requires_missing_resource_server(self):
        with patch.object(p, "request", side_effect=p.KeycloakAPIError(404, "GET", "/resource-server")) as request:
            self.assertTrue(p.authorization_services_disabled("admin", {"id": "dispatch", "authorizationServicesEnabled": False}))
        request.assert_called_once()

    def test_missing_flag_is_accepted_only_when_resource_server_is_absent(self):
        with patch.object(p, "request", side_effect=p.KeycloakAPIError(404, "GET", "/resource-server")):
            self.assertTrue(p.authorization_services_disabled("admin", {"id": "dispatch"}))
        with patch.object(p, "request", return_value=(200, {})):
            with self.assertRaises(p.ProvisioningError):
                p.authorization_services_disabled("admin", {"id": "dispatch"})

    def test_explicit_true_is_rejected_without_followup_request(self):
        with patch.object(p, "request") as request:
            with self.assertRaises(p.ProvisioningError):
                p.authorization_services_disabled("admin", {"id": "dispatch", "authorizationServicesEnabled": True})
            request.assert_not_called()

    def test_unexpected_flag_values_and_authorization_settings_are_rejected(self):
        for client in (
            {"id": "dispatch", "authorizationServicesEnabled": 0},
            {"id": "dispatch", "authorizationServicesEnabled": "false"},
            {"id": "dispatch", "authorizationServicesEnabled": None},
            {"id": "dispatch", "authorizationSettings": {}},
        ):
            with self.subTest(client=client), patch.object(p, "request") as request:
                with self.assertRaises(p.ProvisioningError):
                    p.authorization_services_disabled("admin", client)
                request.assert_not_called()

    def test_non_404_api_failure_is_not_treated_as_disabled(self):
        with patch.object(p, "request", side_effect=p.KeycloakAPIError(500, "GET", "/resource-server")):
            with self.assertRaises(p.ProvisioningError):
                p.authorization_services_disabled("admin", {"id": "dispatch"})


class CreateOnlyTests(Base):
    def test_existing_client_refuses_before_any_write(self):
        existing = {cid: (managed(cid) if cid == IDS[0] else None) for cid in IDS}
        with patch.object(p, "find_client", side_effect=lambda t, cid: existing[cid]), \
             patch.object(p, "request", side_effect=self.record), patch.dict(p.os.environ, ALL_SECRETS):
            result, _ = self.quietly(p.apply)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertIn("Create-only mode", str(result))
        self.assertEqual(self.writes, [])

    def test_missing_or_short_secret_refuses_before_any_write(self):
        with patch.object(p, "find_client", return_value=None), patch.object(p, "request", side_effect=self.record), \
             patch.dict(p.os.environ, {**ALL_SECRETS, p.MANIFEST["clients"][-1]["secretEnv"]: "short"}):
            result, _ = self.quietly(p.apply)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])

    def test_created_client_is_tagged_managed_and_carries_injected_secret(self):
        state = {}
        def find(t, cid): return state.get(cid)
        def req(method, path, token=None, body=None, form=None):
            if method == "POST" and path.endswith("/clients"):
                state[body["clientId"]] = {**managed(body["clientId"]), "secretSet": "secret" in body}
            self.writes.append((method, path, body))
            return 201, None
        with patch.object(p, "find_client", side_effect=find), patch.object(p, "request", side_effect=req), \
             patch.dict(p.os.environ, ALL_SECRETS):
            result, _ = self.quietly(p.apply)
        self.assertEqual(result, 0)
        posted = [b for m, _, b in self.writes if m == "POST"]
        self.assertEqual(sorted(b["clientId"] for b in posted), sorted(IDS))
        for body in posted:
            self.assertEqual(body["attributes"][p.MANAGED_ATTRIBUTE], p.MANAGED_VALUE)
            self.assertFalse(body["publicClient"] or body["standardFlowEnabled"] or body["directAccessGrantsEnabled"])
            self.assertTrue(body["serviceAccountsEnabled"])
            self.assertFalse(body["fullScopeAllowed"])

    def test_partial_failure_reports_only_clients_created_in_this_run(self):
        state = {}
        def req(method, path, token=None, body=None, form=None):
            if method == "POST" and path.endswith("/clients"):
                state[body["clientId"]] = managed(body["clientId"])
            return 201, None
        calls = {"n": 0}
        def verify(*_):
            calls["n"] += 1
            if calls["n"] == 2:
                raise p.ProvisioningError("simulated")
            return "ok"
        with patch.object(p, "find_client", side_effect=lambda t, cid: state.get(cid)), \
             patch.object(p, "request", side_effect=req), patch.object(p, "verify_client", side_effect=verify), \
             patch.dict(p.os.environ, ALL_SECRETS):
            result, err = self.quietly(p.apply)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertIn(str(IDS[:2]), err)
        self.assertNotIn(SYNTHETIC, err)


class UpdateAndRotationTests(Base):
    def test_update_existing_never_sends_a_secret(self):
        existing = {cid: {**managed(cid), "secret": "existing-secret-value"} for cid in IDS}
        with patch.object(p, "find_client", side_effect=lambda t, cid: existing[cid]), \
             patch.object(p, "request", side_effect=self.record), patch.dict(p.os.environ, {}, clear=True):
            result, _ = self.quietly(p.apply, update_existing=True)
        self.assertEqual(result, 0)
        puts = [b for m, _, b in self.writes if m == "PUT"]
        self.assertEqual(len(puts), len(IDS))
        self.assertTrue(all("secret" not in b for b in puts))

    def test_rotation_only_changes_the_named_client(self):
        existing = {cid: {**managed(cid), "secret": "existing-secret-value"} for cid in IDS}
        target = IDS[0]
        with patch.object(p, "find_client", side_effect=lambda t, cid: existing[cid]), \
             patch.object(p, "request", side_effect=self.record), patch.dict(p.os.environ, ALL_SECRETS):
            result, _ = self.quietly(p.apply, rotate={target})
        self.assertEqual(result, 0)
        puts = [b for m, _, b in self.writes if m == "PUT"]
        self.assertEqual([b["clientId"] for b in puts], [target])
        self.assertEqual(puts[0]["secret"], SYNTHETIC)

    def test_unmanaged_existing_client_is_never_modified(self):
        existing = {cid: {"id": "x", "clientId": cid, "attributes": {}} for cid in IDS}
        with patch.object(p, "find_client", side_effect=lambda t, cid: existing[cid]), \
             patch.object(p, "request", side_effect=self.record), patch.dict(p.os.environ, ALL_SECRETS):
            result, _ = self.quietly(p.apply, update_existing=True)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])


class RemovalTests(Base):
    def test_removal_requires_confirmation(self):
        with patch.object(p, "request", side_effect=self.record):
            result, _ = self.quietly(p.remove_managed, [IDS[0]], False)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])

    def test_removal_refuses_clients_outside_manifest(self):
        with patch.object(p, "request", side_effect=self.record):
            result, _ = self.quietly(p.remove_managed, ["zentro-web"], True)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])

    def test_removal_refuses_unmanaged_manifest_client(self):
        with patch.object(p, "find_client", return_value={"id": "x", "clientId": IDS[0], "attributes": {}}), \
             patch.object(p, "request", side_effect=self.record):
            result, _ = self.quietly(p.remove_managed, [IDS[0]], True)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])


class ServiceAccountRoleTests(unittest.TestCase):
    def test_default_roles_composite_and_other_app_roles_are_removed(self):
        calls = []
        def req(method, path, token=None, body=None, form=None):
            calls.append((method, path, body))
            if path.endswith("/service-account-user"):
                return 200, {"id": "sa"}
            if path.endswith("/role-mappings/realm") and method == "GET":
                return 200, [{"name": "default-roles-zentro"}, {"name": "CUSTOMER"}, {"name": "offline_access"}]
            if path.endswith("/scope-mappings/realm") and method == "GET":
                return 200, []
            return 204, None
        with patch.object(p, "request", side_effect=req), patch.object(p, "effective_roles", return_value=({"SERVICE"}, [])):
            p.assign_only_service_role("t", "cid", {"name": "SERVICE"}, "default-roles-zentro")
        deleted = [b for m, path, b in calls if m == "DELETE" and path.endswith("/role-mappings/realm")]
        self.assertEqual(sorted(r["name"] for r in deleted[0]), ["CUSTOMER", "default-roles-zentro"])
        self.assertIn(("POST", p.realm_path("/users/sa/role-mappings/realm"), [{"name": "SERVICE"}]), calls)

    def test_realm_management_roles_abort(self):
        def req(method, path, token=None, body=None, form=None):
            if path.endswith("/service-account-user"):
                return 200, {"id": "sa"}
            return 200, []
        with patch.object(p, "request", side_effect=req), \
             patch.object(p, "effective_roles", return_value=({"SERVICE"}, ["realm-admin"])):
            with self.assertRaises(p.ProvisioningError):
                p.assign_only_service_role("t", "cid", {"name": "SERVICE"}, "default-roles-zentro")


class RecoveryModeTests(Base):
    def tracking_fixture(self, unmanaged=False, unexpected_role=False, extra_mapper=False,
                         unexpected_scope=False):
        spec = next(s for s in p.MANIFEST["clients"] if s["clientId"] == "zentro-tracking-service")
        client = {**managed("zentro-tracking-service"), **p.desired_body(spec, None)}
        if unmanaged:
            client["attributes"] = {}
        direct = [{"name": "default-roles-zentro"}]
        if unexpected_role:
            direct.append({"name": "MERCHANT"})
        scope = ([{"name": "SERVICE"}] if unexpected_scope else [])
        composites = [{"name": "CUSTOMER"}, {"name": "offline_access"},
                      {"name": "uma_authorization"}, {"name": "view-profile"}]
        mapper = {**p.expected_mappers(spec)["Zentro API audience"],
                  "id": "audience-mapper-id"}
        mapper["config"] = {**mapper["config"], "userinfo.token.claim": "false",
                            "introspection.token.claim": "true"}
        mappers = [mapper]
        if extra_mapper:
            mappers.append({"name": "unexpected", "protocol": "openid-connect"})
        writes = []

        def request(method, path, token=None, body=None, form=None):
            if method != "GET":
                writes.append((method, path, body))
            if path.endswith("/authz/resource-server") and method == "GET":
                raise p.KeycloakAPIError(404, method, path)
            if path.endswith("/role-mappings/realm") and method == "GET":
                return 200, list(direct)
            if path.endswith("/role-mappings/realm") and method == "DELETE":
                names = {row["name"] for row in body}
                direct[:] = [row for row in direct if row["name"] not in names]
                return 204, None
            if path.endswith("/role-mappings/realm") and method == "POST":
                direct.extend(body)
                return 204, None
            if path.endswith("/scope-mappings/realm") and method == "GET":
                return 200, list(scope)
            if path.endswith("/scope-mappings/realm") and method == "POST":
                scope.extend(body)
                return 204, None
            if path.endswith("/composites"):
                return 200, composites
            if path.endswith("/groups"):
                return 200, []
            if path.endswith("/protocol-mappers/models") and method == "GET":
                return 200, list(mappers)
            return 200, []

        return spec, client, direct, scope, mappers, writes, request

    def run_tracking_repair(self, dry_run=False, **fixture_options):
        spec, client, direct, scope, mappers, writes, request = self.tracking_fixture(**fixture_options)
        def effective(_token, _client_id):
            if any(row["name"] == "default-roles-zentro" for row in direct):
                return ({"default-roles-zentro", "CUSTOMER", "offline_access", "uma_authorization"}, [])
            return ({"SERVICE"}, [])
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", return_value=client), \
             patch.object(p, "service_account_id", return_value="service-account"), \
             patch.object(p, "request", side_effect=request), \
             patch.object(p, "effective_roles", side_effect=effective), \
             patch.object(p, "assign_only_service_role", side_effect=REAL_ASSIGN_SERVICE_ROLE), \
             patch.object(p, "verify_client", return_value="verified") as verify, \
             patch.dict(p.os.environ, {spec["secretEnv"]: SYNTHETIC}):
            result, err = self.quietly(p.repair_tracking, "zentro-tracking-service", dry_run=dry_run)
        return result, err, direct, scope, mappers, writes, verify

    def dispatch_fixture(self, extra_mapper=False, unmanaged=False, unexpected_role=False):
        spec = next(s for s in p.MANIFEST["clients"] if s["clientId"] == "zentro-dispatch-service")
        client = {**managed("zentro-dispatch-service"), **p.desired_body(spec, None)}
        if unmanaged:
            client["attributes"] = {}
        direct = [{"name": "default-roles-zentro"}]
        scope = []
        composites = [{"name": "CUSTOMER"}, {"name": "offline_access"}, {"name": "uma_authorization"}]
        mappers = ([{"name": "unexpected", "protocol": "openid-connect"}] if extra_mapper else [])
        if unexpected_role:
            direct.append({"name": "MERCHANT"})
        writes = []

        def request(method, path, token=None, body=None, form=None):
            if method != "GET":
                writes.append((method, path, body))
            if path.endswith("/authz/resource-server") and method == "GET":
                raise p.KeycloakAPIError(404, method, path)
            if path.endswith("/role-mappings/realm/composite"):
                names = {row["name"] for row in direct}
                result = []
                if "default-roles-zentro" in names:
                    result.extend(composites)
                    result.append({"name": "default-roles-zentro"})
                if "SERVICE" in names:
                    result.append({"name": "SERVICE"})
                if "MERCHANT" in names:
                    result.append({"name": "MERCHANT"})
                return 200, result
            if path.endswith("/role-mappings/realm") and method == "GET":
                return 200, list(direct)
            if path.endswith("/role-mappings/realm") and method == "DELETE":
                remove = {row["name"] for row in body}
                direct[:] = [row for row in direct if row["name"] not in remove]
                return 204, None
            if path.endswith("/role-mappings/realm") and method == "POST":
                direct.extend(body)
                return 204, None
            if path.endswith("/scope-mappings/realm") and method == "GET":
                return 200, list(scope)
            if path.endswith("/scope-mappings/realm") and method == "DELETE":
                remove = {row["name"] for row in body}
                scope[:] = [row for row in scope if row["name"] not in remove]
                return 204, None
            if path.endswith("/scope-mappings/realm") and method == "POST":
                scope.extend(body)
                return 204, None
            if path.endswith("/composites"):
                return 200, composites
            if path.endswith("/groups"):
                return 200, []
            if path.endswith("/protocol-mappers/models") and method == "GET":
                return 200, list(mappers)
            if path.endswith("/protocol-mappers/models") and method == "POST":
                mappers.append(body)
                return 201, None
            if "/protocol-mappers/models/" in path and method == "PUT":
                name = path.rsplit("/", 1)[1]
                mappers[:] = [row for row in mappers if row.get("id") != name]
                mappers.append(body)
                return 204, None
            return 200, []

        return spec, client, direct, scope, mappers, writes, request

    def run_dispatch_repair(self, dry_run=False, **fixture_options):
        spec, client, direct, scope, mappers, writes, request = self.dispatch_fixture(**fixture_options)
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", return_value=client), \
             patch.object(p, "service_account_id", return_value="service-account"), \
             patch.object(p, "request", side_effect=request), \
             patch.object(p, "configure_mappers", side_effect=REAL_CONFIGURE_MAPPERS), \
             patch.object(p, "assign_only_service_role", side_effect=REAL_ASSIGN_SERVICE_ROLE), \
             patch.dict(p.os.environ, {spec["secretEnv"]: SYNTHETIC}):
            p.effective_roles.side_effect = lambda _token, _cid: (
                ({"default-roles-zentro", "CUSTOMER", "offline_access", "uma_authorization"}
                 if any(r["name"] == "default-roles-zentro" for r in direct) else set())
                | ({"SERVICE"} if any(r["name"] == "SERVICE" for r in direct) else set()), [])
            result, err = self.quietly(p.repair_dispatch, "zentro-dispatch-service", dry_run=dry_run)
        return result, err, direct, scope, mappers, writes

    def test_tracking_partial_state_dry_run_plans_service_role_restore_without_writes(self):
        result, _, direct, scope, mappers, writes, verify = self.run_tracking_repair(dry_run=True)
        self.assertEqual(result, 0)
        self.assertEqual([row["name"] for row in direct], ["default-roles-zentro"])
        self.assertEqual(scope, [])
        self.assertEqual(len(mappers), 1)
        self.assertEqual(mappers[0]["name"], "Zentro API audience")
        self.assertEqual(writes, [])
        verify.assert_not_called()

    def test_tracking_recovery_removes_default_mapping_and_preserves_audience_mapper(self):
        result, _, direct, scope, mappers, writes, verify = self.run_tracking_repair()
        self.assertEqual(result, 0)
        self.assertEqual({row["name"] for row in direct}, {"SERVICE"})
        self.assertEqual({row["name"] for row in scope}, {"SERVICE"})
        self.assertEqual(len(mappers), 1)
        self.assertEqual(mappers[0]["name"], "Zentro API audience")
        self.assertTrue(any(method == "DELETE" and path.endswith("/role-mappings/realm")
                            and [row["name"] for row in body] == ["default-roles-zentro"]
                            for method, path, body in writes))
        self.assertFalse(any(method == "PUT" or path.endswith("/admin/realms/zentro/clients")
                             for method, path, _ in writes))
        self.assertFalse(any("zentro-parcel-service" in path or "zentro-dispatch-service" in path
                             or "zentro-notification-service" in path
                             for _, path, _ in writes))
        verify.assert_called_once()

    def test_tracking_recovery_refuses_unmanaged_or_unexpected_partial_state(self):
        for options in ({"unmanaged": True}, {"unexpected_role": True},
                        {"extra_mapper": True}, {"unexpected_scope": True}):
            with self.subTest(options=options):
                result, _, _, _, _, writes, _ = self.run_tracking_repair(**options)
                self.assertIsInstance(result, p.ProvisioningError)
                self.assertEqual(writes, [])

    def test_dispatch_repair_removes_only_default_role_and_assigns_service(self):
        result, _, direct, scope, mappers, writes = self.run_dispatch_repair()
        self.assertEqual(result, 0)
        self.assertEqual({row["name"] for row in direct}, {"SERVICE"})
        self.assertEqual({row["name"] for row in scope}, {"SERVICE"})
        deletes = [body for method, path, body in writes if method == "DELETE" and path.endswith("/role-mappings/realm")]
        self.assertEqual(len(deletes), 1)
        self.assertEqual([row["name"] for row in deletes[0]], ["default-roles-zentro"])
        self.assertFalse(any("/admin/realms/zentro/roles/" in path and method != "GET" for method, path, _ in writes))
        self.assertEqual({row["name"] for row in mappers}, {"Zentro API audience", "Zentro POD permissions"})
        pod = next(row for row in mappers if row["name"] == "Zentro POD permissions")
        self.assertEqual(pod["config"]["claim.name"], "pod_permissions")
        self.assertEqual(pod["config"]["claim.value"], "pod:read pod:update")
        audience = next(row for row in mappers if row["name"] == "Zentro API audience")
        self.assertEqual(audience["config"]["included.custom.audience"], "zentro-api")

    def test_dispatch_repair_dry_run_is_read_only(self):
        result, _, direct, scope, mappers, writes = self.run_dispatch_repair(dry_run=True)
        self.assertEqual(result, 0)
        self.assertEqual(writes, [])
        self.assertEqual([row["name"] for row in direct], ["default-roles-zentro"])
        self.assertEqual(scope, [])
        self.assertEqual(mappers, [])

    def test_dispatch_repair_refuses_unmanaged_client_without_writes(self):
        result, _, _, _, _, writes = self.run_dispatch_repair(unmanaged=True)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(writes, [])

    def test_dispatch_repair_refuses_unexpected_role_or_mapper_without_writes(self):
        for options in ({"unexpected_role": True}, {"extra_mapper": True}):
            with self.subTest(options=options):
                result, _, _, _, _, writes = self.run_dispatch_repair(**options)
                self.assertIsInstance(result, p.ProvisioningError)
                self.assertEqual(writes, [])

    def test_cli_dispatch_repair_requires_explicit_dispatch_id(self):
        with patch.object(p, "admin_token") as admin, patch.object(p, "request") as request:
            with self.assertRaises(p.ProvisioningError):
                p.cli(["--repair-dispatch", "zentro-parcel-service", "--dry-run"])
            admin.assert_not_called()
            request.assert_not_called()

    def test_create_missing_refuses_existing_target_without_writes(self):
        existing = {"zentro-tracking-service": managed("zentro-tracking-service")}
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=lambda _t, cid: existing.get(cid)), \
             patch.object(p, "request", side_effect=self.record):
            result, _ = self.quietly(p.create_missing,
                ["zentro-tracking-service", "zentro-notification-service"], dry_run=True)
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertEqual(self.writes, [])

    def test_create_missing_changes_only_tracking_and_notification(self):
        preserved = {
            cid: {**managed(cid), **p.desired_body(next(s for s in p.MANIFEST["clients"] if s["clientId"] == cid), None)}
            for cid in ("zentro-parcel-service", "zentro-dispatch-service")
        }
        created = {}
        calls = []
        def find(_token, cid): return created.get(cid) or preserved.get(cid)
        def request(method, path, token=None, body=None, form=None):
            calls.append((method, path, body))
            if method == "POST" and path.endswith("/clients"):
                cid = body["clientId"]
                created[cid] = {**managed(cid), **body}
            return 201, None
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=find), \
             patch.object(p, "request", side_effect=request), \
             patch.object(p, "drift", return_value=[]), \
             patch.object(p, "verify_client", return_value="verified"), \
             patch.object(p, "configure_mappers") as configure, \
             patch.object(p, "assign_only_service_role") as assign, \
             patch.object(p, "injected_secret", return_value=SYNTHETIC):
            result, _ = self.quietly(p.create_missing,
                ["zentro-tracking-service", "zentro-notification-service"])
        self.assertEqual(result, 0)
        created_bodies = [body for method, path, body in calls if method == "POST" and path.endswith("/clients")]
        self.assertEqual({body["clientId"] for body in created_bodies},
                         {"zentro-tracking-service", "zentro-notification-service"})
        self.assertFalse(any(method in {"PUT", "DELETE"} for method, _, _ in calls))
        self.assertEqual({call.args[2]["clientId"] for call in configure.call_args_list},
                         {"zentro-tracking-service", "zentro-notification-service"})
        self.assertEqual({call.args[1] for call in assign.call_args_list},
                         {"id-zentro-tracking-service", "id-zentro-notification-service"})
        self.assertFalse(any(cid in {"zentro-parcel-service", "zentro-dispatch-service"}
                             for call in configure.call_args_list for cid in [call.args[2]["clientId"]]))

    def test_create_missing_dry_run_never_sends_mutating_requests(self):
        preserved = {
            cid: {**managed(cid), **p.desired_body(next(s for s in p.MANIFEST["clients"] if s["clientId"] == cid), None)}
            for cid in ("zentro-parcel-service", "zentro-dispatch-service")
        }
        calls = []
        def request(method, path, token=None, body=None, form=None):
            calls.append(method)
            return 200, []
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=lambda _t, cid: preserved.get(cid)), \
             patch.object(p, "request", side_effect=request), patch.object(p, "drift", return_value=[]), \
             patch.object(p, "verify_client", return_value="verified"), \
             patch.object(p, "injected_secret", return_value=SYNTHETIC):
            result, _ = self.quietly(p.create_missing,
                ["zentro-tracking-service", "zentro-notification-service"], dry_run=True)
        self.assertEqual(result, 0)
        self.assertTrue(all(method == "GET" for method in calls))

    def notification_preserved_clients(self):
        return {
            cid: {**managed(cid), **p.desired_body(
                next(s for s in p.MANIFEST["clients"] if s["clientId"] == cid), None)}
            for cid in ("zentro-parcel-service", "zentro-dispatch-service", "zentro-tracking-service")
        }

    def test_notification_only_dry_run_requires_healthy_preserved_clients_and_writes_nothing(self):
        preserved = self.notification_preserved_clients()
        verified = []
        calls = []
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=lambda _t, cid: preserved.get(cid)), \
             patch.object(p, "drift", return_value=[]), \
             patch.object(p, "mapper_issues", return_value=[]), \
             patch.object(p, "verify_client", side_effect=lambda _t, spec, _id, _secret: verified.append(spec["clientId"])), \
             patch.object(p, "request", side_effect=lambda method, *_args, **_kwargs: calls.append(method)), \
             patch.object(p, "injected_secret", return_value=SYNTHETIC):
            result, _ = self.quietly(p.create_notification_only, dry_run=True)
        self.assertEqual(result, 0)
        self.assertEqual(set(verified), set(preserved))
        self.assertEqual(calls, [])
        self.assertFalse(p.READ_ONLY)

    def test_notification_only_refuses_preserved_client_mapper_or_role_drift(self):
        preserved = self.notification_preserved_clients()
        for drift_kind in ("role", "mapper"):
            with self.subTest(drift_kind=drift_kind):
                calls = []
                with patch.object(p, "admin_token", return_value="admin-token"), \
                     patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
                     patch.object(p, "find_client", side_effect=lambda _t, cid: preserved.get(cid)), \
                     patch.object(p, "drift", return_value=["role drift"] if drift_kind == "role" else []), \
                     patch.object(p, "mapper_issues", return_value=["mapper drift"] if drift_kind == "mapper" else []), \
                     patch.object(p, "verify_client", return_value="verified"), \
                     patch.object(p, "request", side_effect=lambda method, *_args, **_kwargs: calls.append(method)), \
                     patch.object(p, "injected_secret", return_value=SYNTHETIC):
                    result, _ = self.quietly(p.create_notification_only, dry_run=False)
                self.assertIsInstance(result, p.ProvisioningError)
                self.assertEqual(calls, [])

    def test_notification_only_live_path_targets_only_notification(self):
        preserved = self.notification_preserved_clients()
        created = {}
        calls = []
        def find(_token, cid): return created.get(cid) or preserved.get(cid)
        def request(method, path, token=None, body=None, form=None):
            calls.append((method, path, body))
            if method == "POST" and path.endswith("/clients"):
                cid = body["clientId"]
                created[cid] = {**managed(cid), **body}
            return 201, None
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=find), \
             patch.object(p, "request", side_effect=request), \
             patch.object(p, "drift", return_value=[]), patch.object(p, "mapper_issues", return_value=[]), \
             patch.object(p, "verify_client", return_value="verified"), \
             patch.object(p, "configure_mappers") as configure, \
             patch.object(p, "assign_only_service_role") as assign, \
             patch.object(p, "injected_secret", return_value=SYNTHETIC):
            result, _ = self.quietly(p.create_notification_only)
        self.assertEqual(result, 0)
        posted = [body["clientId"] for method, path, body in calls
                  if method == "POST" and path.endswith("/clients")]
        self.assertEqual(posted, ["zentro-notification-service"])
        self.assertEqual([call.args[2]["clientId"] for call in configure.call_args_list],
                         ["zentro-notification-service"])
        self.assertEqual([call.args[1] for call in assign.call_args_list],
                         ["id-zentro-notification-service"])
        self.assertEqual(set(preserved), {"zentro-parcel-service", "zentro-dispatch-service", "zentro-tracking-service"})

    def test_create_missing_partial_failure_reports_created_without_rollback(self):
        preserved = {cid: {**managed(cid), **p.desired_body(next(s for s in p.MANIFEST["clients"] if s["clientId"] == cid), None)}
                     for cid in ("zentro-parcel-service", "zentro-dispatch-service")}
        created = {}
        calls = []
        def find(_token, cid): return created.get(cid) or preserved.get(cid)
        def request(method, path, token=None, body=None, form=None):
            calls.append((method, path, body))
            if method == "POST" and path.endswith("/clients"):
                created[body["clientId"]] = {**managed(body["clientId"]), **body}
            return 201, None
        def configure(*_): raise p.ProvisioningError("simulated mapper failure")
        with patch.object(p, "admin_token", return_value="admin-token"), \
             patch.object(p, "realm_info", return_value=({"name": "SERVICE"}, "default-roles-zentro")), \
             patch.object(p, "find_client", side_effect=find), patch.object(p, "request", side_effect=request), \
             patch.object(p, "drift", return_value=[]), patch.object(p, "verify_client", return_value="verified"), \
             patch.object(p, "configure_mappers", side_effect=configure), patch.object(p, "assign_only_service_role"), \
             patch.object(p, "injected_secret", return_value=SYNTHETIC):
            result, err = self.quietly(p.create_missing,
                ["zentro-tracking-service", "zentro-notification-service"])
        self.assertIsInstance(result, p.ProvisioningError)
        self.assertIn("zentro-tracking-service", err)
        self.assertEqual(len([c for c in calls if c[0] == "POST" and c[1].endswith("/clients")]), 1)
        self.assertFalse(any(method == "DELETE" for method, _, _ in calls))


if __name__ == "__main__":
    unittest.main()
