# Development configuration

Copy templates without overwriting existing local files:

```sh
cp -n .env.example .env
cp -n .env.common.example .env.common
for service in api-gateway identity-partner-service parcel-pricing-service dispatch-courier-service tracking-reporting-service notification-integration-service; do
  cp -n "backend/$service/.env.example" "backend/$service/.env"
done
cp -n infra/keycloak/.env.example infra/keycloak/.env
cp -n frontend/zentro-web/.env.example frontend/zentro-web/.env
```

Populate private local values before running anything. The templates deliberately do not provide working database credentials. Provision five separate databases and runtime users plus a reachable Kafka broker; root Compose does not provision either. S1/S4 now require explicit DB_URL, DB_USER and DB_PASSWORD in their own environment; S1 does not load the Keycloak environment. Supply an explicit newly provisioned INTERNAL_SERVICE_TOKEN only if using shared-token gRPC authentication; otherwise use the existing JWT path.

Start development Keycloak separately when needed (after setting its bootstrap credentials), then the application stack. Root Compose expects its external Keycloak network; inspect the network name in Compose before starting. These are operator commands, not audit validation steps:

```sh
docker compose -f infra/keycloak/docker-compose.keycloak.yml config -q
docker compose -f infra/keycloak/docker-compose.keycloak.yml up -d
docker compose config -q
docker compose up -d --build
docker compose ps
```

Use `config -q` to avoid printing resolved credentials. All published ports default to 127.0.0.1; the full [port table and security rules](../security/production-configuration.md#development-ports) cover all three Compose files. Same-machine URLs remain localhost (frontend 80, gateway 8080, Keycloak 8180). Internal service ports are development diagnostics, not a path for application clients to bypass Gateway.

To reach the stack from another device, explicitly export `BIND_ADDRESS=0.0.0.0` for trusted private LAN/phone testing and align VITE URLs, CORS, Keycloak issuer and redirect URIs; never use this on a shared or public network. A root `.env` setting alone is not guaranteed to configure a Compose command launched from another directory: export the same variable for each project.

After starting a stack, verify loopback publication with:

```sh
docker ps --format '{{.Names}}\t{{.Ports}}'
```

The audit found no tracked host.docker.internal/host-gateway/extra_hosts dependency, LAN frontend proxy or non-loopback realm redirect. Custom ignored environment values were not inspected. Production requires separate deployment configuration and owner verification; see [production configuration](../security/production-configuration.md).
