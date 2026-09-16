# Observability

Metrics, tracing and request correlation for the Banking Platform. The telemetry
stack runs from its own Compose file; the application behaves the same whether or
not it is running.

```bash
docker compose up -d                                      # platform
docker compose -f docker-compose.observability.yml up -d  # Prometheus, Grafana, Zipkin
```

| Concern | Tool | Endpoint |
|---|---|---|
| Metrics | Micrometer → Prometheus | <http://localhost:9090> |
| Dashboards | Grafana (provisioned) | <http://localhost:3001> |
| Traces | Micrometer Tracing (Brave) → Zipkin | <http://localhost:9411> |
| Request correlation | `X-Request-Id` | Response header and log lines |

## Metrics

Each service exposes three actuator endpoints — `health`, `info` and `prometheus`.
Everything else (`env`, `beans`, `heapdump`, `loggers`) stays closed.

Metrics are tagged with `application`, so one scrape configuration and one
dashboard cover all 13 services:

```bash
curl -s http://localhost:8083/actuator/prometheus | grep http_server_requests_seconds_count
```

Prometheus scrapes every service every 10s. Target status is at
<http://localhost:9090/targets>.

Latency histogram buckets are enabled explicitly
(`management.metrics.distribution.percentiles-histogram.http.server.requests`).
Without them Prometheus cannot compute a latency quantile.

## Dashboard

Grafana provisions its datasources and one dashboard from `observability/grafana/`.
No manual import is required.

**Banking Platform — Service Health** covers:

- Target health, request rate, 5xx rate, p99 latency, and circuit-breaker state.
- Request rate, p95 latency and the ten slowest endpoints, broken down by service.
- JVM heap, process CPU, and active/pending HikariCP connections.

Error-rate panels count 5xx responses only; expected 4xx business rejections are
excluded.

![Grafana service-health dashboard](screenshots/07-observability.png)

*Capture from a local run with 5 of the 13 services started; the target-health
tile reads 38% for that reason. The remaining panels show live data from the
same run. Docker on the capture machine had 6 GB allocated, which is not enough
to run all 13 services alongside Prometheus, Grafana and Zipkin.*

## Request correlation

Every request carries an `X-Request-Id` from the edge through to the database:

```
browser → BFF → gateway (mints or reuses) → service → Feign → downstream service
```

The gateway mints an id when a request arrives without one and reuses a valid
inbound id. The header is untrusted input, so it is bounded to 64 characters and
restricted to `[A-Za-z0-9_-]`; values outside that are replaced rather than
rejected, so a malformed header does not fail the request.

Propagation uses Micrometer Tracing baggage rather than the MDC. Spring Cloud
CircuitBreaker may execute a Feign call on a different thread from the one
serving the request, where a thread-local MDC lookup returns nothing. Baggage
travels with the trace context across both the thread hop and the service
boundary.

Log lines carry `[service, requestId, traceId, spanId]`:

```bash
curl -si http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"x","password":"y"}' | grep -i x-request-id

docker compose logs user-service | grep <that-id>
```

## Viewing a trace

Micrometer Tracing (Brave) instruments the gateway, each service and the Feign
calls between them, reporting to Zipkin.

1. Start both Compose files and seed a customer (`./scripts/seed-demo.sh`).
2. Perform a transfer in the console at <http://localhost:3000>, or any
   authenticated API call.
3. Open <http://localhost:9411>, select **Run Query**, and open the newest trace.

A transfer produces a single trace spanning the gateway, `transaction-service`
and the nested `account-service` calls for the debit and the credit.

Sampling is set to 1.0 for local use. A production deployment would sample well
below that.

## Not covered

- Logs are plain text with correlation fields, written to stdout. There is no log
  aggregator; `docker compose logs` is the query interface.
- Traces are stored in memory and are lost when Zipkin restarts.
- Kafka consumers inherit trace context from Spring Kafka instrumentation; the
  asynchronous hops are not separately verified here.
- There are no alerting rules.
