#!/usr/bin/env python3
"""Compare the preserved S3 inspect snapshot with one candidate container.

The snapshot is the independent baseline. This command intentionally accepts no
second container reference, so comparing the same live container twice cannot pass.
It prints field names and verdicts only; environment values are never printed.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import subprocess
import sys
from pathlib import Path

SERVICE = "zentro-dispatch-courier-service"
REQUIRED_FLAGS = {
    "SPRING_FLYWAY_ENABLED": "false",
    "SPRING_FLYWAY_VALIDATE_ON_MIGRATE": "true",
    "POD_WORKER_ENABLED": "false",
    "OUTBOX_ENABLED": "false",
}
REQUIRED_NETWORKS = {"zentro-backend", "keycloak_default"}
REQUIRED_ALIASES = {"dispatch-courier-service", "zentro-dispatch-courier-service"}
SECRETISH = re.compile(r"SECRET|PASSWORD|PASSWD|TOKEN|CREDENTIAL|PRIVATE|DSN|AUTH|DB_URL|DB_USER", re.I)
REDACTED_LENGTH = re.compile(r"^<redacted len=(\d+)>$")

# Fields which must match the saved original configuration. State/time counters
# are deliberately omitted because starting the preserved container changes them.
CONFIG_FIELDS = (
    "Image", "ImageID", "RepoDigests", "Entrypoint", "Cmd", "User",
    "WorkingDir", "ImageEntrypoint", "ImageCmd", "ImageUser", "ImageWorkingDir",
    "ImageHealthcheck", "Healthcheck", "ImageEnvKeys", "ExposedPorts", "Labels",
    "StopSignal", "StopTimeout", "Tty", "OpenStdin", "Hostname", "Domainname",
    "PortBindings", "RestartPolicy", "NetworkMode", "Mounts", "Binds", "Tmpfs",
    "VolumesFrom", "Dns", "DnsOptions", "DnsSearch", "ExtraHosts", "LogConfig",
    "Resources", "Security",
)


def _canonical(value):
    if isinstance(value, dict):
        return {key: _canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [_canonical(item) for item in value]
    return value


def _same(left, right):
    empty_equivalent = ([], {})
    if left is None and right in empty_equivalent:
        return True
    if right is None and left in empty_equivalent:
        return True
    return _canonical(left) == _canonical(right)


def _snapshot_summary(snapshot):
    if SERVICE in snapshot and isinstance(snapshot[SERVICE], dict):
        return snapshot[SERVICE]
    if isinstance(snapshot, dict) and "ImageID" in snapshot:
        return snapshot
    raise ValueError("snapshot does not contain the expected S3 service record")


def validate_snapshot(snapshot, candidate):
    """Return safe mismatch labels; never include configuration values."""
    expected = _snapshot_summary(snapshot)
    issues = []
    required = set(CONFIG_FIELDS) | {"Env", "Networks"}
    missing = sorted(required - set(expected))
    if missing:
        return ["snapshot missing required fields: " + ", ".join(missing)]
    missing_candidate = sorted(required - set(candidate))
    if missing_candidate:
        return ["candidate missing required fields: " + ", ".join(missing_candidate)]

    for field in CONFIG_FIELDS:
        if not _same(expected[field], candidate[field]):
            issues.append(field)

    expected_networks = expected["Networks"] or {}
    candidate_networks = candidate["Networks"] or {}
    if set(expected_networks) != set(candidate_networks):
        issues.append("Networks.names")
    else:
        for network in sorted(expected_networks):
            if sorted(set(expected_networks[network].get("Aliases") or [])) != sorted(set(candidate_networks[network].get("Aliases") or [])):
                issues.append("Networks." + network + ".Aliases")
    if not REQUIRED_NETWORKS.issubset(set(expected_networks)):
        issues.append("snapshot required networks")
    if not REQUIRED_ALIASES.issubset({alias for item in candidate_networks.values() for alias in (item.get("Aliases") or [])}):
        issues.append("required network aliases")

    expected_env, candidate_env = expected["Env"], candidate["Env"]
    if set(expected_env) != set(candidate_env):
        issues.append("Env.names")
    for key in sorted(set(expected_env) & set(candidate_env)):
        if key in REQUIRED_FLAGS:
            continue
        value = expected_env[key]
        redacted = REDACTED_LENGTH.fullmatch(str(value))
        if SECRETISH.search(key):
            # The baseline intentionally stores only secret lengths. The one
            # authorized delta is POD_CLIENT_SECRET; all other secret-shaped
            # environment entries must retain their recorded lengths.
            if key != "POD_CLIENT_SECRET":
                if redacted and len(str(candidate_env[key])) != int(redacted.group(1)):
                    issues.append("Env." + key)
                elif not redacted and value != candidate_env[key]:
                    issues.append("Env." + key)
            continue
        if redacted:
            if len(str(candidate_env[key])) != int(redacted.group(1)):
                issues.append("Env." + key)
        elif value != candidate_env[key]:
            issues.append("Env." + key)

    for key, value in REQUIRED_FLAGS.items():
        if expected_env.get(key) != value or candidate_env.get(key) != value:
            issues.append("Env." + key)
    if expected.get("RestartPolicy", {}).get("Name") != "no" or candidate.get("RestartPolicy", {}).get("Name") != "no":
        issues.append("RestartPolicy.required-no")
    if len(candidate.get("PortBindings") or {}) != 1 or "8083/tcp" not in (candidate.get("PortBindings") or {}):
        issues.append("PortBindings.required-8083")
    return sorted(set(issues))


def _run_json(args):
    try:
        raw = subprocess.check_output(args, stderr=subprocess.DEVNULL, text=True)
        return json.loads(raw)
    except Exception as error:
        raise RuntimeError("Docker read-only inspection failed; no configuration values were emitted") from None


def inspect_candidate(name):
    data = _run_json(["docker", "inspect", name])
    if not isinstance(data, list) or len(data) != 1:
        raise RuntimeError("candidate did not resolve to exactly one container")
    d = data[0]
    c, h, net = d["Config"], d["HostConfig"], d["NetworkSettings"]
    image = _run_json(["docker", "image", "inspect", d["Image"]])[0]
    env = {}
    for entry in c.get("Env") or []:
        key, sep, value = entry.partition("=")
        if not sep or key in env:
            raise RuntimeError("candidate environment is malformed")
        env[key] = value
    networks = {key: {"Aliases": sorted(set(value.get("Aliases") or []))}
                for key, value in (net.get("Networks") or {}).items()}
    return {
        "Image": c.get("Image"), "ImageID": d.get("Image"),
        "RepoDigests": image.get("RepoDigests"),
        "Entrypoint": c.get("Entrypoint"), "Cmd": c.get("Cmd"), "User": c.get("User"),
        "WorkingDir": c.get("WorkingDir"), "ImageEntrypoint": image["Config"].get("Entrypoint"),
        "ImageCmd": image["Config"].get("Cmd"), "ImageUser": image["Config"].get("User"),
        "ImageWorkingDir": image["Config"].get("WorkingDir"),
        "ImageHealthcheck": image["Config"].get("Healthcheck"), "Healthcheck": c.get("Healthcheck"),
        "ImageEnvKeys": sorted(item.split("=", 1)[0] for item in image["Config"].get("Env") or []),
        "Env": env, "ExposedPorts": sorted((c.get("ExposedPorts") or {}).keys()),
        "Labels": c.get("Labels") or {}, "StopSignal": c.get("StopSignal"),
        "StopTimeout": c.get("StopTimeout"), "Tty": c.get("Tty"), "OpenStdin": c.get("OpenStdin"),
        "Hostname": c.get("Hostname"), "Domainname": c.get("Domainname"),
        "PortBindings": h.get("PortBindings") or {}, "RestartPolicy": h.get("RestartPolicy") or {},
        "NetworkMode": h.get("NetworkMode"), "Networks": networks, "Mounts": d.get("Mounts") or [],
        "Binds": h.get("Binds") or [], "Tmpfs": h.get("Tmpfs") or {},
        "VolumesFrom": h.get("VolumesFrom") or [], "Dns": h.get("Dns") or [],
        "DnsOptions": h.get("DnsOptions") or [], "DnsSearch": h.get("DnsSearch") or [],
        "ExtraHosts": h.get("ExtraHosts") or [], "LogConfig": h.get("LogConfig") or {},
        "Resources": {key: h.get(key) for key in ("Memory", "MemoryReservation", "MemorySwap", "NanoCpus",
            "CpuShares", "CpuQuota", "CpuPeriod", "CpusetCpus", "PidsLimit", "Ulimits", "ShmSize", "OomKillDisable")},
        "Security": {key: h.get(key) for key in ("Privileged", "CapAdd", "CapDrop", "SecurityOpt", "ReadonlyRootfs",
            "UsernsMode", "IpcMode", "PidMode", "Init", "AutoRemove", "Devices", "GroupAdd", "Runtime", "Isolation", "CgroupnsMode")},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, type=Path, help="saved inspect_s3.json file")
    parser.add_argument("--snapshot-sha256", required=True, help="owner-verified SHA-256 of the saved snapshot")
    parser.add_argument("--candidate", required=True, help="single container name or ID to inspect")
    args = parser.parse_args(argv)
    if not args.snapshot.is_file():
        parser.error("snapshot file does not exist")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.snapshot_sha256):
        parser.error("snapshot SHA-256 must be a 64-character hexadecimal digest")
    if not args.candidate or args.candidate.startswith("-"):
        parser.error("one candidate container name or ID is required")
    try:
        snapshot_bytes = args.snapshot.read_bytes()
        actual_hash = hashlib.sha256(snapshot_bytes).hexdigest()
        if not hmac.compare_digest(actual_hash.lower(), args.snapshot_sha256.lower()):
            raise RuntimeError("independent snapshot SHA-256 does not match the owner-verified digest")
        snapshot = json.loads(snapshot_bytes.decode("utf-8"))
        candidate = inspect_candidate(args.candidate)
        issues = validate_snapshot(snapshot, candidate)
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 2
    if issues:
        print("FAIL: independent S3 snapshot mismatch: " + ", ".join(issues))
        return 1
    print("PASS: candidate matches the independent original S3 snapshot and required safety flags")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
