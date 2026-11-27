#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
case "$1" in
  caddy) docker run --rm --env-file release.env.example -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" "$2" caddy validate --config /etc/caddy/Caddyfile ;;
  keycloak)
    trap 'docker rm -fv fixture >/dev/null' EXIT
    docker run -d --name fixture -p 127.0.0.1:8080:8080 "$2" start-dev --http-host=0.0.0.0 --http-relative-path=/auth
    curl --fail --retry 40 --retry-delay 3 --retry-all-errors http://127.0.0.1:8080/auth/realms/master/.well-known/openid-configuration >/dev/null || { docker logs --tail 80 fixture; exit 1; } ;;
  kafka)
    docker network create fixture
    trap 'docker rm -fv kafka >/dev/null; docker network rm fixture >/dev/null' EXIT
    docker run -d --name kafka --network fixture -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENERS=INTERNAL://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:9092 -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL "$2"
    timeout 120 bash -c 'until docker exec kafka kafka-broker-api-versions --bootstrap-server kafka:9092 >/dev/null 2>&1; do sleep 3; done' || { docker logs --tail 60 kafka; docker exec kafka kafka-broker-api-versions --bootstrap-server kafka:9092; exit 1; }
    docker exec kafka kafka-topics --bootstrap-server kafka:9092 --create --topic fixture --partitions 1 --replication-factor 1 ;;
esac
