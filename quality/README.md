# OpsFlow API quality gates

This directory contains three executable quality gates that run against a live OpsFlow stack.

## OpenAPI contract guard

`openapi-contract.mjs` downloads `/api-docs` and verifies the API identity, JWT security scheme,
public endpoint exemptions, critical business operations, ticket query parameters, request-body
metadata, response metadata, minimum path count, and globally unique operation IDs.

```bash
node quality/openapi-contract.mjs
```

Set `OPSFLOW_BASE_URL` to test another environment.

## Observability smoke test

`observability-smoke.mjs` verifies that request and trace identifiers reach the HTTP response,
Micrometer metrics are exposed, Prometheus can scrape the application, Tempo has received an
OpsFlow trace, Alloy has delivered correlated logs to Loki, and Grafana is healthy with all three
data sources plus the OpsFlow overview dashboard provisioned.

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
node quality/observability-smoke.mjs
```

Each backend URL can be overridden with `OPSFLOW_PROMETHEUS_URL`, `OPSFLOW_TEMPO_URL`,
`OPSFLOW_LOKI_URL`, and `OPSFLOW_GRAFANA_URL`.
Grafana credentials can be overridden with `OPSFLOW_GRAFANA_USER` and
`OPSFLOW_GRAFANA_PASSWORD`; the smoke test also recognizes the Compose variables
`GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD`.

## k6 performance baseline

The k6 smoke workload registers one isolated test user, creates one seed ticket, and then exercises
the public status page plus authenticated ticket, dashboard, and SLA reads. It ramps to 10 virtual
users and enforces error-rate, check-rate, and p95 latency thresholds.

```bash
docker compose up -d --build
docker compose --profile quality run --rm --no-deps k6
```

The workload uses only disposable test data and should not be pointed at production without an
explicit test-data and capacity plan.

The first measured result is documented in [`docs/performance-baseline.md`](../docs/performance-baseline.md).
