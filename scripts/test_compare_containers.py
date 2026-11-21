import copy
import contextlib
import importlib.util
import io
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("compare_containers.py")
spec = importlib.util.spec_from_file_location("compare_containers", MODULE_PATH)
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


def fixture():
    env = {
        "SPRING_FLYWAY_ENABLED": "false",
        "SPRING_FLYWAY_VALIDATE_ON_MIGRATE": "true",
        "POD_WORKER_ENABLED": "false",
        "OUTBOX_ENABLED": "false",
        "POD_CLIENT_SECRET": "<redacted len=64>",
        "SERVER_PORT": "8083",
        "DB_URL": "<redacted len=72>",
    }
    networks = {
        "zentro-backend": {"Aliases": ["dispatch-courier-service", "zentro-dispatch-courier-service"]},
        "keycloak_default": {"Aliases": ["dispatch-courier-service", "zentro-dispatch-courier-service"]},
    }
    common = {
        "Image": "zentro-dispatch-courier-service:flyway-safe",
        "ImageID": "sha256:" + "a" * 64,
        "RepoDigests": [], "RepoTags": ["zentro-dispatch-courier-service:flyway-safe"],
        "Entrypoint": ["java"], "Cmd": ["-jar", "app.jar"], "User": "10001",
        "WorkingDir": "/app", "ImageEntrypoint": ["java"], "ImageCmd": ["-jar", "app.jar"],
        "ImageUser": "10001", "ImageWorkingDir": "/app", "ImageHealthcheck": None,
        "Healthcheck": {"Test": ["CMD", "health"]}, "ImageEnvKeys": ["PATH"],
        "ExposedPorts": ["8083/tcp"], "Labels": {"com.docker.compose.service": "dispatch-courier-service"},
        "StopSignal": "SIGTERM", "StopTimeout": 10, "Tty": False, "OpenStdin": False,
        "Hostname": "s3", "Domainname": "", "PortBindings": {"8083/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8083"}]},
        "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}, "NetworkMode": "zentro-backend",
        "Networks": networks, "Mounts": [], "Binds": [], "Tmpfs": {}, "VolumesFrom": [],
        "Dns": [], "DnsOptions": [], "DnsSearch": [], "ExtraHosts": [], "LogConfig": {"Type": "local", "Config": {}},
        "Resources": {key: None for key in ("Memory", "MemoryReservation", "MemorySwap", "NanoCpus", "CpuShares", "CpuQuota", "CpuPeriod", "CpusetCpus", "PidsLimit", "Ulimits", "ShmSize", "OomKillDisable")},
        "Security": {key: None for key in ("Privileged", "CapAdd", "CapDrop", "SecurityOpt", "ReadonlyRootfs", "UsernsMode", "IpcMode", "PidMode", "Init", "AutoRemove", "Devices", "GroupAdd", "Runtime", "Isolation", "CgroupnsMode")},
        "Env": env,
    }
    return {compare.SERVICE: copy.deepcopy(common)}, common


class IndependentSnapshotTests(unittest.TestCase):
    def test_saved_snapshot_matches_candidate(self):
        snapshot, candidate = fixture()
        candidate["Env"]["POD_CLIENT_SECRET"] = "x" * 64
        candidate["Env"]["DB_URL"] = "x" * 72
        self.assertEqual(compare.validate_snapshot(snapshot, candidate), [])

    def test_original_empty_pod_secret_matches_redacted_snapshot(self):
        snapshot, candidate = fixture()
        snapshot[compare.SERVICE]["Env"]["POD_CLIENT_SECRET"] = "<redacted len=0>"
        candidate["Env"]["POD_CLIENT_SECRET"] = ""
        candidate["Env"]["DB_URL"] = "x" * 72
        self.assertEqual(compare.validate_snapshot(snapshot, candidate), [])

    def test_null_and_empty_optional_docker_fields_are_normalized(self):
        snapshot, candidate = fixture()
        for key in ("Binds", "Dns", "DnsOptions", "DnsSearch", "ExtraHosts", "VolumesFrom"):
            snapshot[compare.SERVICE][key] = None
            candidate[key] = []
        snapshot[compare.SERVICE]["Tmpfs"] = None
        candidate["Tmpfs"] = {}
        candidate["Env"]["DB_URL"] = "x" * 72
        self.assertEqual(compare.validate_snapshot(snapshot, candidate), [])

    def test_snapshot_mismatch_is_rejected(self):
        snapshot, candidate = fixture()
        candidate["Cmd"] = ["unexpected"]
        self.assertIn("Cmd", compare.validate_snapshot(snapshot, candidate))

    def test_image_id_mismatch_is_rejected(self):
        snapshot, candidate = fixture()
        candidate["ImageID"] = "sha256:" + "b" * 64
        self.assertIn("ImageID", compare.validate_snapshot(snapshot, candidate))

    def test_missing_network_alias_is_rejected(self):
        snapshot, candidate = fixture()
        candidate["Networks"]["keycloak_default"]["Aliases"].remove("dispatch-courier-service")
        self.assertIn("Networks.keycloak_default.Aliases", compare.validate_snapshot(snapshot, candidate))

    def test_incorrect_safety_flags_are_rejected(self):
        for key, wrong in (("SPRING_FLYWAY_ENABLED", "true"),
                           ("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "false"),
                           ("POD_WORKER_ENABLED", "true"),
                           ("OUTBOX_ENABLED", "true")):
            with self.subTest(key=key):
                snapshot, candidate = fixture()
                candidate["Env"][key] = wrong
                self.assertIn("Env." + key, compare.validate_snapshot(snapshot, candidate))

    def test_incorrect_restart_policy_is_rejected(self):
        snapshot, candidate = fixture()
        candidate["RestartPolicy"]["Name"] = "unless-stopped"
        self.assertIn("RestartPolicy.required-no", compare.validate_snapshot(snapshot, candidate))

    def test_published_port_mismatch_is_rejected(self):
        snapshot, candidate = fixture()
        candidate["PortBindings"]["8083/tcp"][0]["HostPort"] = "18083"
        self.assertIn("PortBindings", compare.validate_snapshot(snapshot, candidate))

    def test_env_names_and_nonsecret_values_are_checked(self):
        snapshot, candidate = fixture()
        candidate["Env"]["SERVER_PORT"] = "8084"
        self.assertIn("Env.SERVER_PORT", compare.validate_snapshot(snapshot, candidate))
        snapshot, candidate = fixture()
        candidate["Env"].pop("OUTBOX_ENABLED")
        self.assertIn("Env.names", compare.validate_snapshot(snapshot, candidate))

    def test_secret_values_never_appear_in_diagnostics(self):
        snapshot, candidate = fixture()
        secret = "synthetic-secret-" + "x" * 64
        candidate["Env"]["POD_CLIENT_SECRET"] = secret
        issues = compare.validate_snapshot(snapshot, candidate)
        self.assertNotIn(secret, " ".join(issues))

    def test_cli_has_no_second_live_container_reference(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                compare.main(["--snapshot", "snapshot.json", "--snapshot-sha256", "0" * 64,
                              "--candidate", "same", "--reference", "same"])


if __name__ == "__main__":
    unittest.main()
