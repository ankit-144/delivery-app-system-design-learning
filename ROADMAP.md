# QuickDrop — Roadmap

QuickDrop is a tiny quick-commerce backend (think Zepto / Blinkit): a customer orders from a
dark store, stock is reserved, a nearby rider is assigned, and the customer is notified.

The product stays small on purpose. What grows is the **system around it**. Every phase:

1. Starts from a decision that looks reasonable at the time.
2. Gets **broken on purpose** with a k6 load script.
3. Is **diagnosed from Grafana** — metrics first, code second.
4. Is **fixed**, usually by flipping a toggle to a better strategy that already lives in the code.
5. Ends with **"what can still fail"** — things we cannot reproduce on a laptop go to
   [`docs/FAILURE_TRACKER.md`](docs/FAILURE_TRACKER.md).

---

## How the repo is organised

```
services/
  core-service/          orders, inventory, riders, matching (Spring Boot, Postgres)
  notification-service/  extracted in Phase 6
  vendor-mock/           fake SMS/push vendor — latency, error-rate and rate-limit knobs
libs/
  quickdrop-common/      toggles, metrics helpers, event contracts
infra/                   docker-compose (profiles per phase), Prometheus, Grafana,
                         nginx, Loki/Alloy, Tempo, Toxiproxy, Postgres configs
loadtest/k6/             one script per scenario + shared helpers
scenarios/               one runbook per scenario
docs/concepts/           Redis, Kafka, Postgres locking, sharding deep dives
docs/FAILURE_TRACKER.md  failures we can describe but not reproduce locally
Makefile                 make up PHASE=3 / make scenario ID=1.1 / make load ID=1.1 ...
```

### One branch, many strategies

Every "wrong" and "right" way lives in the code at the same time, behind a strategy interface,
selected by a **toggle**:

```
quickdrop.toggles.inventory-reservation = naive | synchronized | pessimistic | optimistic | atomic
quickdrop.toggles.rider-assignment      = naive | conditional
quickdrop.toggles.notification-mode     = inline | async-memory | http | db-outbox | kafka
...
```

- Toggles can be changed **at runtime** (`POST /admin/toggles`, or `scripts/toggle.sh` which hits
  every instance directly), so you can flip one mid-load-test and watch the graph change.
- Every toggle change is written to a Grafana annotation, so the dashboards show *when* you flipped it.
- Friends can open `NaiveInventoryReservation.java` next to `AtomicInventoryReservation.java` and
  diff the two ideas side by side.

### Invariant checker

A background job runs SQL that should always return zero rows (oversold stock, a rider on two
active orders, duplicate orders for one idempotency key, …) and exports
`quickdrop_invariant_violations{type=...}`. Correctness bugs become a **red number on a dashboard**,
not something you have to go and find.

### Every runbook has the same shape

1. **Story** — what's happening in the business ("Diwali flash sale, 10 units of iPhone at ₹1").
2. **Setup** — `make scenario ID=1.1 MODE=broken`.
3. **Break** — `make load ID=1.1`.
4. **Observe** — which dashboard, which panel, what shape to expect, SQL to confirm.
5. **Why** — the mechanism, with pointers into the code.
6. **Fix** — the toggle or code change.
7. **Verify** — rerun and compare.
8. **Trade-offs & what still fails** — links into the failure tracker.
9. **Discussion questions.**

---

## Infrastructure (all local, Docker Compose profiles)

| Component | Introduced | Why |
|---|---|---|
| Postgres 17 + Flyway | Phase 0 | the source of truth; schema migrations are part of the lessons |
| Prometheus + Grafana | Phase 0 | metrics and dashboards (provisioned, nothing to click together) |
| postgres-exporter, cAdvisor | Phase 0 | DB internals, per-container CPU and memory |
| Loki + Grafana Alloy | Phase 0 | logs next to metrics |
| k6 (as a container) | Phase 0 | load and break scripts, metrics pushed into Prometheus |
| Toxiproxy | Phase 2 | inject latency or cut connections to Postgres, Redis, vendor |
| nginx | Phase 3 | load balancer in front of 2–3 core instances |
| Redis 7 + redis-exporter | Phase 3 | locks, rate limits, cache, geo, pub/sub, streams |
| Postgres replica, 2 shards | Phase 5 | read scaling and sharding |
| Kafka (KRaft, 1 broker) + kafka-exporter + Kafka UI | Phase 6 | events, backpressure |
| Kafka 3-broker cluster (opt-in profile) | Phase 7 | replication, acks, ISR |
| Tempo + OpenTelemetry | Phase 8 | traces across HTTP and Kafka |
| Alertmanager | Phase 8 | alerts and SLO burn rates |

Phase profiles are cumulative but optional pieces are separate, so a laptop only runs what the
current phase needs (Phase 1 ≈ 2 GB RAM, everything ≈ 8–10 GB).

---

## Phase 0 — Baseline: one instance, one database, eyes on

The happy path works. Before breaking anything, learn to read the dashboards.

| ID | Scenario | What you learn |
|---|---|---|
| 0.1 | Tour of the order flow and dashboards | RED metrics (rate, errors, duration), USE (utilisation, saturation, errors), what each panel means |
| 0.2 | Averages lie | a few slow requests vanish in the mean; p50 vs p95 vs p99, histograms |
| 0.3 | Flyway basics | versioned migrations, `flyway_schema_history`, why you never edit an applied migration |

## Phase 1 — Correctness under concurrency (still one instance)

Nothing is slow yet. The data is just **wrong**.

| ID | Scenario | Broken | Fixes compared |
|---|---|---|---|
| 1.1 | Overselling the last 10 units | read stock → check → write (lost update) | `synchronized`, `SELECT … FOR UPDATE`, `@Version` optimistic + retry, atomic `UPDATE … WHERE stock >= qty` — throughput and p99 of each on one graph |
| 1.2 | Two orders get the same rider | read "available" rider → assign | conditional update on rider status + partial unique index on active assignments |
| 1.3 | Customer taps "Pay" twice / client retries on timeout | no idempotency | idempotency key with a unique constraint, replaying the stored response |
| 1.4 | Deadlocks on multi-item carts | rows locked in cart order | lock rows in a fixed order (sorted product id); `pg_stat_database.deadlocks` on the dashboard |
| 1.5 | Optimistic locking on a hot item | 500 buyers, one SKU | retry storms and wasted work; when pessimistic or atomic beats optimistic |

> `synchronized` "fixes" 1.1 here. Remember that — Phase 3 breaks it.

## Phase 2 — One box hits the wall

Correct now, but traffic is growing. Find **which resource** is the bottleneck and watch it
**move** when you fix it.

| ID | Scenario | Symptom on the dashboard | Fix |
|---|---|---|---|
| 2.1 | Sync 1 s vendor call inside the request | Tomcat busy threads pinned at max, p99 explodes, even `/actuator/health` times out | take the call off the request path (bounded executor) — then see its own problems in 6.3 |
| 2.2 | Calling the vendor **inside** a DB transaction | Hikari active = max, pending connections climb, `Connection is not available` errors | never hold a connection across remote I/O |
| 2.3 | "Just make the pool bigger" | Postgres `max_connections` hit, DB CPU goes to context switching, latency gets *worse* | pool sizing; the database is shared by every instance |
| 2.4 | Missing index | DB CPU high, seq scans in `pg_stat_statements`, slow order history | `EXPLAIN ANALYZE`, the right index |
| 2.5 | N+1 queries on order history | queries per request jumps from 2 to 50 | fetch join / projection |
| 2.6 | Rider matching scans every rider in Java | app CPU saturated | spatial lookup (Redis GEO in 3.4) |
| 2.7 | No timeouts anywhere | one slow dependency (Toxiproxy) hangs the whole service | timeouts and a latency budget |
| 2.8 | Turn on virtual threads | the thread limit disappears — and Hikari becomes the wall | removing one bottleneck exposes the next |

Conclusion: the instance is CPU-bound and the code is reasonable → scale out.

## Phase 3 — Scale out: 2–3 instances behind nginx, enter Redis

New class of problems: **anything kept in one JVM's memory is now wrong**.

| ID | Scenario | Broken | Fix |
|---|---|---|---|
| 3.1 | The `synchronized` fix from 1.1 | oversell is back: each instance has its own lock | lock outside the JVM |
| 3.2 | Redis distributed lock | `SET NX PX` + Lua compare-and-delete; then pause the holder past the TTL → two holders | fencing token checked by the DB; why the DB constraint is still your real safety net |
| 3.3 | Local product cache | price updated on instance A, instance B still serves the old one | Redis cache, or local cache + pub/sub invalidation (two-tier) |
| 3.4 | Rider locations in a `HashMap` | matching depends on which instance you hit | Redis GEO (`GEOADD`, `GEOSEARCH`) — also fixes the CPU from 2.6 |
| 3.5 | Rate limiting a bot hammering `/orders` and `/otp` | per-instance limiter lets 3× through | Redis fixed window → boundary burst → sliding window (sorted set) → token bucket (Lua) |
| 3.6 | Scheduled "reassign stuck orders" job | runs on every instance → double reassignment | scheduler lock (ShedLock-style, Redis or DB) |
| 3.7 | Load balancer behaviour | one slow instance drags p99 under round-robin; killing an instance mid-traffic drops requests | `least_conn`, passive health checks, retries for idempotent requests, graceful shutdown |
| 3.8 | Cache stampede on a hot key | key expires → every instance hits Postgres at once | single-flight lock, TTL jitter, early refresh |
| 3.9 | Redis goes down | what happens to locks, limits, cache? | fail-open vs fail-closed *per use case*, circuit breaker |

### Redis deep dive (practical)

| ID | Scenario | Lesson |
|---|---|---|
| R1 | `KEYS *` on 1M keys | single-threaded: one slow command blocks every client; `SCAN`, `SLOWLOG` |
| R2 | Big keys and hot keys | a 500k-element hash; `--bigkeys`, `--hotkeys`, latency spikes |
| R3 | `maxmemory` + eviction policies | `noeviction` → write errors; `allkeys-lru` → **your locks get evicted**; `volatile-*` |
| R4 | Persistence | `kill -9` Redis with RDB vs AOF; which state did you lose (rate limits? locks?) |
| R5 | Round trips, pipelining, Lua | 1000 commands one by one vs pipelined; `MULTI` vs Lua atomicity |
| R6 | Pub/Sub vs Streams | subscriber down → pub/sub messages gone; Streams + consumer groups keep them (preview of Kafka) |
| R7 | Client connections | folded into R5: Lettuce multiplexes one connection per instance |
| R8 | Replication + Sentinel failover | moved to docs/FAILURE_TRACKER.md — not reproducible honestly on one machine |

## Phase 4 — Changing the schema while the system is live

With two instances, deploys become rolling: **old code and new schema run at the same time**.

| ID | Scenario | Broken | Fix |
|---|---|---|---|
| 4.1 | `ALTER TABLE` behind one long transaction | the ALTER waits for its lock, every read and write queues behind it → total freeze | `lock_timeout` + retry, keep transactions short |
| 4.2 | `CREATE INDEX` on a busy table | writes blocked for the whole build | `CREATE INDEX CONCURRENTLY` (and its failure mode: invalid indexes) |
| 4.3 | Adding a column | constant default is instant; volatile default rewrites the table | know which DDL rewrites |
| 4.4 | Renaming a column during a rolling deploy | v1 instances crash on the new schema | expand → migrate → contract over three releases |
| 4.5 | Backfilling 5M rows | one giant transaction: locks, WAL burst, dead tuples | batched backfill, `VACUUM`, watching bloat |
| 4.6 | Flyway gotchas | edited migration → checksum failure; two instances migrating at once | Flyway's lock, migrations as a separate step, `outOfOrder` |

## Phase 5 — Scaling the database

| ID | Scenario | Lesson |
|---|---|---|
| 5.1 | Read replica + lag | "I placed an order but it's not in my list" — read-your-writes; route to primary after a write |
| 5.2 | Choosing a shard key | shard by city keeps an order's transaction on one shard, but "my orders" becomes scatter-gather. Every shard key has a losing query |
| 5.3 | Routing datasource with 2 shards | `AbstractRoutingDataSource`, shard resolution, connection pools per shard |
| 5.4 | Global IDs | `BIGSERIAL` collides across shards → Snowflake-style IDs / UUIDv7 |
| 5.5 | Hot shard | one city gets 80% of traffic; per-shard dashboards |
| 5.6 | Resharding | modulo 2→3 moves most keys; consistent hashing; logical buckets mapped to physical shards (code demo) |
| 5.7 | Cross-shard operations | why they hurt; saga intro (mostly concept → tracker) |
| 5.8 | Migrating N shards | one shard fails halfway; schema drift between shards |

## Phase 6 — Notifications: from a sync call to Kafka

The notification code is extracted into `notification-service`. The vendor mock answers in ~1 s.

| ID | Scenario | Broken | Fix |
|---|---|---|---|
| 6.1 | Core calls notification-service synchronously | vendor slow → notification slow → **order placement slow** (cascading latency) | decouple |
| 6.2 | Timeouts + circuit breaker + bulkhead | core is protected — but notifications are silently dropped while the breaker is open | need durability |
| 6.3 | In-memory async queue | restart → queued notifications gone; spike → queue grows until OOM | persist |
| 6.4 | Persist to Postgres, poll with workers | flash sale ×50: notification inserts + polling hammer the **shared DB**, order placement degrades, system falls over | `SKIP LOCKED` helps, but the DB is the wrong buffer |
| 6.5 | Kafka between core and notifications | spike absorbed: consumer lag climbs, then drains; order placement unaffected | backpressure by design |
| 6.6 | Dual write (DB commit, then Kafka publish) | crash between the two → order with no event, or event with no order | transactional outbox |
| 6.7 | Vendor allows 100 req/s | 3 consumers each send 100/s → vendor 429s | shared token bucket in Redis across consumers |
| 6.8 | OTP stuck behind a promo blast | one topic, one lag | separate topics / consumer groups by priority |

## Phase 7 — Kafka deep dive (practical)

| ID | Scenario | Lesson |
|---|---|---|
| K1 | 1 partition, 3 consumers | partitions = max parallelism; extra consumers sit idle |
| K2 | Ordering | no key → "Delivered" arrives before "Placed"; key by order id |
| K3 | Delivery semantics | crash after send, before commit → duplicate SMS; auto-commit → lost messages; idempotent consumer |
| K4 | Poison pill | one bad message blocks a partition forever → retry topics with backoff + DLQ |
| K5 | Rebalance storm | slow processing > `max.poll.interval.ms` → kicked out → reprocess → repeat; tuning, cooperative-sticky, static membership |
| K6 | Producer acks and ISR (3 brokers) | `acks=1` + leader kill → data loss; `acks=all` + `min.insync.replicas=2` |
| K7 | Replay | reset a consumer group's offsets to rebuild a read model; retention; Kafka is a log, not a queue |
| K8 | Batching and compression | `linger.ms`, `batch.size`, compression: throughput vs latency |
| K9 | Fan-out | notification, analytics and rider-incentive consumers read the same topic independently |
| K10 | Compacted topic | latest rider status per key |
| K11 | Idempotent producer and transactions | exactly-once inside Kafka (read-process-write), and where it stops |

## Phase 8 — Operating it

| ID | Scenario | Lesson |
|---|---|---|
| 8.1 | Distributed tracing | one trace across HTTP → Kafka → notification; jump from a slow trace to its logs |
| 8.2 | SLOs and alerts | burn-rate alerts → Alertmanager → webhook into our own notification service |
| 8.3 | Retry storms | retries without backoff multiply load on a struggling dependency; exponential backoff + jitter, retry budgets |
| 8.4 | Load shedding | shed non-critical work during a flash sale to keep checkout alive |
| 8.5 | Game day | a random broken toggle is switched on; diagnose it using only the dashboards |

---

## Build order

Each phase is built and **verified** (the failure shows up reliably, the fix makes it go away)
before moving to the next. Progress is tracked in [`docs/BUILD_PROGRESS.md`](docs/BUILD_PROGRESS.md).
