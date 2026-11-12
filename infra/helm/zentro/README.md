# Backend Helm chart

This chart is a backend-only deployment scaffold. Supply an approved immutable
image for every service and create a separate `<service>-runtime` Kubernetes
Secret in the target namespace with the service's DB_URL, DB_USER, DB_PASSWORD,
JWT settings, and any required broker/provider configuration. The chart never
creates or embeds credentials. All services are ClusterIP; ingress, TLS,
network policies, Kafka/Keycloak/POD connectivity and database role provisioning
remain deployment-owner decisions. Do not deploy until those decisions and
secret values are provisioned. Test with `helm lint` and `helm template` after
providing approved images.
