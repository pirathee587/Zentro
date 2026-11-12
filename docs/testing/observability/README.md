# Observability verification

`infra/observability/prometheus.yml` and `alerts.yml` are a starting
configuration for an operator-approved private backend monitoring network.
Before deployment, validate the files with the selected Prometheus version,
verify each actuator path and network ACL, inject an unavailable scrape target
and a controlled 5xx series, and observe alert firing and recovery. No actual
outage/recovery evidence is claimed by this repository change. DB/broker health,
outbox/retry/replay and consumer-lag metrics require service instrumentation
with stable, low-cardinality names. Trace/correlation IDs must not be labels.
