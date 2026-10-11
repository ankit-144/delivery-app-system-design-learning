# QuickDrop cheat sheet

Start, check, watch, stop. Everything runs locally in Docker. The architecture diagram is [architecture.html](architecture.html) (open it in a browser). Running a class? [TEACHING_GUIDE.md](TEACHING_GUIDE.md).

## 1. Start

```bash
make up PHASE=8          # everything (first run builds images: a few minutes). Needs ~8–10 GB free RAM.
make ps                  # what's running
make urls                # print the links below
```

| Phase | `make up PHASE=` | Adds | RAM (approx.) |
|---|---|---|---|
| 0–2 | `0` `1` `2` | 1 core instance, Postgres, nginx, Toxiproxy, vendor-mock, full monitoring | ~3 GB |
| 3–4 | `3` `4` | core-2, core-3, Redis | ~5 GB |
| 5 | `5` | Postgres replica + 2 shards | ~6 GB |
| 6–7 | `6` `7` | Kafka, Kafka UI, notification-service ×2 | ~7 GB |
| 8 | `8` | Tempo (traces), Alertmanager, tracing on in every service | ~8 GB |

Extras: `make up PHASE=8 EXTRA="replica shards"` adds the Phase 5 databases. `make load ID=K6` starts its own
3-broker Kafka (`make kafka-cluster-down` afterwards).

## 2. Check it's healthy

```bash
make ps                                       # every container "Up", core/postgres/kafka "(healthy)"
curl -s localhost:8081/actuator/health        # {"status":"UP"}   (core-1; 8082, 8083 for core-2/3)
open http://localhost:9090/targets            # Prometheus: every target UP
make invariants                               # data correct? all zeros = yes
```

## 3. Watch: Grafana (http://localhost:3000, no login)

| Dashboard | Link | Look here for |
|---|---|---|
| Overview | http://localhost:3000/d/quickdrop-overview | orders/s, errors, p99 per endpoint and per instance, invariants |
| Service saturation | http://localhost:3000/d/quickdrop-saturation | Tomcat threads, DB pool, HTTP clients, vendor |
| Postgres | http://localhost:3000/d/quickdrop-postgres | connections, locks, slow queries, seq scans, replication |
| Redis & scale-out | http://localhost:3000/d/quickdrop-redis | Redis ops/memory, cache hit rate, per-instance load |
| Kafka & notifications | http://localhost:3000/d/quickdrop-kafka | consumer lag, delivery delay, outbox, vendor results |
| Operating (Phase 8) | http://localhost:3000/d/quickdrop-operating | alerts firing, SLO burn, retries, load shedding, service graph |
| Containers & logs | http://localhost:3000/d/quickdrop-containers | CPU/memory per container, warning/error logs |

Orange vertical lines on every dashboard = a toggle was flipped. Dots on latency panels (Phase 8) = exemplars: click
one to open that request's trace.

**Explore** (http://localhost:3000/explore):

| Pick | Try |
|---|---|
| Loki (logs) | `{container="core-1"} \|= "ERROR"` · `{container=~"notification-.*"} \|= "ALERT"` |
| Tempo (traces, Phase 8) | `{ duration > 1s }` · `{ status = error }` · `{ rootName = "http post /api/orders" }` |
| Prometheus (metrics) | `sum by (instance) (rate(http_server_requests_seconds_count{job="core"}[1m]))` |

## 4. Every other tool, from the edge inwards

```
 k6 ──▶ nginx :8080 ──▶ core-1/2/3 :8081-8083 ──▶ Toxiproxy ──▶ Postgres / Redis / vendor-mock
                              │                                  Kafka ──▶ notification-1/2 :8091-8092 ──▶ vendor-mock
   metrics: every service ──▶ Prometheus ──▶ Grafana ◀── Loki ◀── Alloy (container logs)
   traces:  every service ──▶ Tempo ──▶ Grafana          alerts: Prometheus ──▶ Alertmanager ──▶ notification-1
```

| What                       | URL / command                                                                                                                                       | Notes                                                         |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| nginx (load balancer)      | http://localhost:8080 · http://localhost:8080/nginx_status                                                                                          | `make lb ALGO=least-conn` switches algorithm                  |
| core instances             | http://localhost:8081/admin/toggles · `/actuator/health` · `/actuator/prometheus`                                                                   | 8082, 8083 = core-2, core-3                                   |
| notification-service       | http://localhost:8091/api/alerts · http://localhost:8091/admin/toggles                                                                              | 8092 = notification-2                                         |
| vendor-mock (fake SMS)     | http://localhost:8090/v1/stats                                                                                                                      | `make vendor NAME=latency-ms VALUE=1000`                      |
| Toxiproxy (network faults) | http://localhost:8474/proxies                                                                                                                       | `make toxics` · `make clear-toxics`                           |
| Postgres                   | `make psql` · localhost:5432 (`quickdrop`/`quickdrop`)                                                                                              | replica 5433, shards 5434/5435 (Phase 5)                      |
| Redis                      | `docker exec -it qd-redis redis-cli`                                                                                                                | `SLOWLOG GET 5`, `INFO memory`                                |
| Kafka UI                   | http://localhost:8085                                                                                                                               | topics, consumer groups, messages                             |
| Kafka CLI                  | `docker exec qd-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups`                            | lag per partition                                             |
| Prometheus                 | http://localhost:9090 · [/targets](http://localhost:9090/targets) · [/alerts](http://localhost:9090/alerts) · [/rules](http://localhost:9090/rules) | rules in `infra/prometheus/rules/`                            |
| Alertmanager               | http://localhost:9093                                                                                                                               | routes to notification-1's webhook                            |
| Tempo                      | Grafana → Explore → Tempo · http://localhost:3200/ready                                                                                             | `/ready` can say "not ready" for a moment now and then: retry |
| Loki                       | Grafana → Explore → Loki                                                                                                                            | no host port; Alloy ships every container's logs              |
| cAdvisor                   | Containers dashboard                                                                                                                                | no host port                                                  |

## 5. Run a scenario

```bash
make load ID=1.1 MODE=broken      # see scenarios/README.md for every ID and its modes
make load ID=1.1 MODE=fixed
make toggles                      # what each instance runs now
make reset-toggles                # everything back to defaults
```

Runbooks: [scenarios/README.md](../scenarios/README.md). Game day: `make load ID=8.5`, then `make gameday` in a second
terminal.

## 6. Stop

```bash
make down       # stop everything, keep data
make clean      # stop and delete all data (database, metrics, dashboards state)
```

## When something's off

| Symptom | Try |
|---|---|
| a container keeps restarting | `make logs S=core-1` |
| a dashboard panel is empty | http://localhost:9090/targets: is its exporter UP? Is there traffic? |
| scenario numbers look odd | `make reset-toggles` · `make clear-toxics` · rerun |
| port already in use | `PG_PORT=5433 make up` (Postgres), or stop whatever holds 8080/3000 |
| out of memory | run a lower phase: `make up PHASE=3` |
