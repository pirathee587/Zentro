#!/usr/bin/env python3
"""Plan by default; explicitly apply only the two local flow-test clients."""
import argparse
import urllib.parse
import provision_service_clients as provisioning

CLIENTS = {"zentro-dispatch-service", "zentro-tracking-service"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Modify the local Keycloak realm using injected existing secrets")
    args = parser.parse_args()
    url = urllib.parse.urlsplit(provisioning.BASE)
    if (url.scheme != "http" or url.hostname != "localhost" or url.port != 8180
            or url.username or url.password or url.path or url.query or url.fragment
            or provisioning.REALM != "zentro"):
        parser.error("Only http://localhost:8180 and realm zentro are allowed.")
    selected = [s for s in provisioning.MANIFEST["clients"] if s["clientId"] in CLIENTS]
    if {s["clientId"] for s in selected} != CLIENTS:
        parser.error("The manifest must contain both required clients.")
    if not args.apply:
        for spec in selected:
            print(f"Plan: {spec['clientId']}; secret source: {spec['secretEnv']}; SERVICE; audience zentro-api")
        print("No network calls or changes. Use --apply only after explicit provisioning approval.")
        return
    for spec in selected:
        if len(provisioning.os.environ.get(spec["secretEnv"], "")) < 32:
            parser.error(f"Inject an approved secret of at least 32 characters in {spec['secretEnv']}.")
    if not provisioning.ADMIN_PASSWORD:
        parser.error("Inject the existing local administrator password.")
    provisioning.MANIFEST = {**provisioning.MANIFEST, "clients": selected}
    provisioning.main()

if __name__ == "__main__":
    main()
