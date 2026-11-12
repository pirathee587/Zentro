# Local observability validation — 2026-11-12

- Prometheus 2.55.1 loaded `infra/observability/prometheus.yml` and the
  configured alert rules successfully.
- `promtool test rules infra/observability/tests/alerts.test.yml` passed for a
  synthetic unavailable scrape, a synthetic elevated 5xx rate, and recovery
  after the scrape target returned. This verifies rule behavior with fixtures;
  it is not a real backend outage drill.
- Grafana 11.4.0 started with the checked-in provisioning mounts. Its local
  authenticated API returned the `Zentro Backend Overview` dashboard and
  `Zentro Backend` folder.

No backend stack was running during these checks, so datasource connectivity,
protected scrape authorization, database/broker health, outbox/retry/replay,
consumer lag, and trace correlation under outage remain unverified. The local
Grafana test password and container were disposable and removed.
