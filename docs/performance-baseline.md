# OpsFlow performance baseline

Baseline date: 2026-10-14 (Asia/Shanghai)

## Environment

- OpsFlow Spring Boot application running in Docker Desktop
- MySQL 8.4 and Redis 7.4 in the same Docker Compose stack
- Grafana k6 v2.2.0 running on the Windows host
- Single developer workstation; no network latency or production traffic contention
- Workload script: `quality/k6/opsflow-smoke.js`

## Workload

The test registers one disposable requester, creates one seed ticket, and then ramps toward 10
virtual users over 25 seconds. Each iteration reads four representative endpoints:

- Public service status page
- Authenticated paged ticket queue
- Authenticated operations dashboard overview
- Authenticated SLA summary

The test deliberately keeps writes in the one-time setup phase so the measured section represents
a repeatable read-heavy operations-console workload.

## Results

| Metric | Result | Gate |
|---|---:|---:|
| HTTP requests | 1,126 | Informational |
| Throughput | 43.40 requests/second | Informational |
| Completed iterations | 281 | Informational |
| Functional checks | 2,251 / 2,251 passed | > 99% |
| HTTP failure rate | 0.00% | < 1% |
| Overall response time p95 | 11.65 ms | < 800 ms |
| Overall response time p99 | 18.20 ms | Informational |
| Ticket queue p95 | 13.65 ms | < 600 ms |
| Dashboard overview p95 | 9.85 ms | < 700 ms |
| Public status page p95 | 7.76 ms | < 500 ms |
| Maximum observed response time | 257.79 ms | Informational |

All configured thresholds passed.

## Interpretation and limits

This is a regression baseline, not a production capacity claim. It proves that the current build can
serve a small concurrent workload without functional errors on a local environment and gives CI a
clear signal when latency or error rate changes materially. Production sizing still requires a
dedicated environment, representative data volume, realistic network latency, longer soak tests,
write-heavy scenarios, and database/CPU/memory telemetry.
