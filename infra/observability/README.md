# Backend metrics foundation

Prometheus configuration scrapes only the backend actuator Prometheus endpoint
on the private service network. Deploy only behind an operator approved network
and authentication boundary; this repository does not configure ingress,
credentials, Grafana authentication or production access. Do not publish
actuator endpoints to the public internet. The selected low-cardinality rules
cover scrape health, HTTP server errors and heap pressure. Database, broker,
outbox, retry/replay and consumer-lag metrics depend on service instrumentation
and must be added only after their metric names and labels are verified.

Grafana dashboards are intentionally not provisioned until an operator selects
the approved authentication and access model. These files provide a starting
scrape and alert rule set, not production observability acceptance evidence.
