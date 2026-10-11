# Scenarios

Each scenario is a runbook (here) plus a load script (`loadtest/k6/scenarios/<id>-*.js`).

```bash
make load ID=<id> MODE=<broken|fixed|...>
```

Every runbook follows the same shape: **Story → Run it → Observe → Why → Fix → What can still go wrong → Discussion.**
Numbers in the runbooks were measured on the reference setup (each core instance capped at 1 CPU,
Postgres at 2). Yours will differ a little; the shape won't.

## Phase 0 — Baseline

| ID | Scenario | Teaches |
|---|---|---|
| [0.1](phase-0/0.1-baseline.md) | A normal day | Reading RED and USE dashboards, Little's law |
| [0.2](phase-0/0.2-averages-lie.md) | Averages lie | p50 / p95 / p99, histograms, tail latency |
| [0.3](phase-0/0.3-flyway-basics.md) | Flyway basics | Versioned migrations, checksums, transactional DDL |

## Phase 1 — Correctness under concurrency

| ID | Scenario | Broken | Fixed |
|---|---|---|---|
| [1.1](phase-1/1.1-oversell.md) | Overselling the last units | read-check-write (lost update) | atomic conditional `UPDATE` (+ 4 other strategies compared) |
| [1.2](phase-1/1.2-double-rider.md) | Two orders, one rider | check-then-act | conditional `UPDATE` + optional unique index |
| [1.3](phase-1/1.3-duplicate-orders.md) | Double tap / client retry | no idempotency | idempotency key claimed in the DB |
| [1.4](phase-1/1.4-deadlock.md) | Deadlocks on carts | rows locked in cart order | rows locked in product-id order |
| [1.5](phase-1/1.5-hot-item.md) | Hot item | optimistic locking under contention | atomic / pessimistic |

## Phase 2 — One box hits the wall

| ID | Scenario | What runs out | Fix |
|---|---|---|---|
| [2.1](phase-2/2.1-slow-vendor.md) | Slow vendor, inline SMS | Tomcat threads (even health checks queue) | timeout / async (and the queue it creates) |
| [2.2](phase-2/2.2-transaction-holds-connection.md) | Vendor call inside the transaction | DB connections, row locks | remote calls outside transactions |
| [2.3](phase-2/2.3-bigger-pool.md) | "Just make the pool bigger" | `max_connections`, DB memory, latency | the index (2.4), bulkheads |
| [2.4](phase-2/2.4-missing-index.md) | Missing index | Postgres CPU | `CREATE INDEX CONCURRENTLY` |
| [2.5](phase-2/2.5-n-plus-one.md) | N+1 queries | round trips (1 ms each in the cloud) | page ids + fetch join |
| [2.6](phase-2/2.6-scan-all-riders.md) | Matching loads every rider | app CPU (and your metrics go dark) | filter in the database + partial index |
| [2.7](phase-2/2.7-no-timeouts.md) | Silent vendor, no timeout | every thread, forever | timeouts on every call |
| [2.8](phase-2/2.8-virtual-threads.md) | Virtual threads | the next limit: DB pool, pinned carriers | explicit concurrency limits, `ReentrantLock` |

## Phase 3 — Scale out: three instances, nginx, Redis

Start it with `make up PHASE=3`.

| ID | Scenario | Broken | Fixed |
|---|---|---|---|
| [3.1](phase-3/3.1-jvm-lock-scaled.md) | The JVM lock on three instances | `synchronized` (161 oversold) | Redis lock, or no lock: atomic `UPDATE` |
| [3.2](phase-3/3.2-redis-lock-expiry.md) | Redis lock expires under a frozen holder | 249 oversold | fencing tokens checked by Postgres |
| [3.3](phase-3/3.3-stale-cache.md) | Price change, local caches | 67% stale reads, stale charges | Redis cache / two-tier + pub/sub |
| [3.4](phase-3/3.4-rider-locations.md) | Rider positions in memory | 87% of matches find nobody | Redis GEO of available riders |
| [3.5](phase-3/3.5-rate-limiting.md) | Rate limiting an OTP bot | per-instance counters, fixed windows | sliding log / token bucket in Lua |
| [3.6](phase-3/3.6-scheduler-on-every-instance.md) | `@Scheduled` on every instance | ghost riders | scheduler lock / `SKIP LOCKED` |
| [3.7](phase-3/3.7-load-balancer.md) | Slow and dying instances | round-robin, crash | least-conn, graceful shutdown |
| [3.8](phase-3/3.8-cache-stampede.md) | Cache stampede | cache-aside (1,411 queries/min) | single-flight + stale (6) |
| [3.9](phase-3/3.9-redis-down.md) | Redis goes away | fail / fallbacks that melt Postgres | breaker + cheap fallbacks |

## Redis deep dive

| ID | Scenario | Lesson |
|---|---|---|
| [R1](redis/R1-keys-blocks-everyone.md) | `KEYS` on a million keys | single thread: one slow command blocks everyone; `SCAN` |
| [R2](redis/R2-big-keys.md) | A million-field hash | `HGETALL`/`DEL` stalls; `HSCAN`, `UNLINK`, sharded keys |
| [R3](redis/R3-eviction.md) | Memory full | `volatile-lru` silently disabled the rate limiter; separate cache from coordination data |
| [R4](redis/R4-persistence.md) | `kill -9` Redis | RDB loses minutes (and resurrects deletes); AOF; `CONFIG SET` vs restart |
| [R5](redis/R5-round-trips.md) | Round trips | 146k → 926 ops/s from 1 ms latency; `MGET`, pipelining, Lua |
| [R6](redis/R6-pubsub-vs-streams.md) | Pub/sub vs Streams | fire-and-forget vs consumer groups, pending messages, at-least-once |

## Phase 4 — Changing the schema while the system is live

Works with any phase running; 4.4 needs Phase 3 (three instances).

| ID | Scenario | Broken | Fixed |
|---|---|---|---|
| [4.1](phase-4/4.1-alter-behind-long-transaction.md) | Instant `ALTER` behind an idle transaction | lock queue freezes the table (and, via threads, everything) | `lock_timeout` + retry, `idle_in_transaction_session_timeout` |
| [4.2](phase-4/4.2-create-index.md) | Index on a busy table | `CREATE INDEX` blocks writes | `CONCURRENTLY` (and its INVALID leftovers) |
| [4.3](phase-4/4.3-add-column-rewrite.md) | Which DDL rewrites the table | volatile default, type change | constant default, `NOT VALID` constraints |
| [4.4](phase-4/4.4-rename-column.md) | Rename during a rolling deploy | 27% of requests fail | expand → migrate → contract |
| [4.5](phase-4/4.5-backfill.md) | Backfill 1.5M rows | one transaction locks old rows for 51 s | batches by primary-key range |
| [4.6](phase-4/4.6-flyway-in-production.md) | Flyway with several instances | migrations at startup | migrations as a pipeline step; checklist |

## Phase 5 — Scaling the database

Start it with `make up PHASE=5` (adds a streaming read replica and two shard databases).

| ID | Scenario | Lesson |
|---|---|---|
| [5.1](phase-5/5.1-read-replica.md) | Read replica | 100% of customers missed their own new order; read-your-writes fixes it and keeps 2/3 of reads offloaded |
| [5.2](phase-5/5.2-shard-key.md) | Choosing a shard key | city vs customer: each makes one screen a scatter-gather |
| [5.3](phase-5/5.3-routing.md) | How requests find their shard | code walkthrough: router, pools, app-side vs proxy vs distributed SQL |
| [5.4](phase-5/5.4-global-ids.md) | Ids across shards | per-shard sequences collide (447/901); Snowflake ids; JS 2^53 |
| [5.5](phase-5/5.5-hot-shard.md) | Hot shard | 80% Bengaluru → 9× load on one shard |
| [5.6](phase-5/5.6-resharding.md) | Resharding | modulo moves 67–89% of keys; consistent hashing; logical buckets |
| [5.7](phase-5/5.7-cross-shard-move.md) | Cross-shard move | no transaction across shards; idempotent steps + retry |
| [5.8](phase-5/5.8-migrating-shards.md) | Migrating N shards | one bad row → shards on different schema versions |

## Phase 6 — Notifications: from a sync call to Kafka

Start it with `make up PHASE=6` (Kafka, Kafka UI on :8085, notification-service ×2).

| ID | Scenario | Broken | Fixed |
|---|---|---|---|
| [6.1](phase-6/6.1-sync-service-chain.md) | Sync chain core → notification → vendor | vendor latency = checkout latency, threads exhausted | decouple |
| [6.2](phase-6/6.2-timeouts-breaker-bulkhead.md) | Protecting the caller | no protection | timeout + breaker + bulkhead (and "timeout ≠ failed") |
| [6.3](phase-6/6.3-memory-queue-crash.md) | In-memory queue vs crash | 306 SMS silently lost | durable queue (53 lost on the HTTP hop) |
| [6.4](phase-6/6.4-db-as-queue.md) | Postgres as the queue | blast competes with checkout (p99 10× at production ratio) | it's fine at moderate volume; know the cost |
| [6.5](phase-6/6.5-kafka-backpressure.md) | The blast through Kafka | — | 55k lag absorbed, orders unaffected; measuring lag correctly |
| [6.6](phase-6/6.6-dual-write.md) | Dual write | lost events / ghost SMS | transactional outbox + reconciliation |
| [6.7](phase-6/6.7-vendor-rate-limit.md) | Vendor allows 100/s | 19,400 × 429 | shared Redis token bucket: overload becomes lag |
| [6.8](phase-6/6.8-otp-priority.md) | OTP behind a promo blast | 0 OTPs delivered in a minute | separate topic + consumer group |

## Kafka deep dive

Needs Phase 6+ (`make up PHASE=6`). The lab endpoints live in notification-service (`/admin/kafka-lab/*`) and use
their own topics (`lab-1p`, `lab-6p`, `lab-out`, `rider-status`), except K4, which uses the real `notifications` topic.

| ID | Scenario | Lesson |
|---|---|---|
| [K1](kafka/K1-partitions.md) | 6 consumers, 1 partition | one consumer per partition: 5 idle, 5× slower |
| [K2](kafka/K2-ordering.md) | Events without a key | 1 in 3 processed out of order; key = unit of ordering |
| [K3](kafka/K3-delivery-semantics.md) | Crash between process and commit | at-least-once (duplicates) vs at-most-once (losses); idempotent consumer |
| [K4](kafka/K4-poison-pill.md) | One unparseable message | blocks its partition forever; skip loses it; dead-letter topic |
| [K5](kafka/K5-rebalance-storm.md) | Slow batches | `max.poll.interval.ms` exceeded → rebalance loop, nothing commits |
| [K6](kafka/K6-acks-and-replication.md) | Kill the leader (3 brokers) | `acks=1` loses acknowledged writes; `acks=all` + `min.insync.replicas` |
| [K7](kafka/K7-replay.md) | Rebuild a read model | consuming doesn't delete: reset offsets and replay |
| [K8](kafka/K8-batching-compression.md) | Producer tuning | `linger.ms`, `batch.size`, compression: throughput vs latency |
| [K9](kafka/K9-fan-out.md) | Two teams, one topic | consumer groups are independent: one down doesn't slow the other |
| [K10](kafka/K10-compaction.md) | Rider status, latest only | compacted topic keeps the last value per key |
| [K11](kafka/K11-transactions.md) | Read-process-write with crashes | transactions: exactly-once inside Kafka, `read_committed` |

## Phase 8 — Operating it

Start it with `make up PHASE=8` (adds Tempo on :3200 and Alertmanager on :9093, turns tracing on everywhere).
Dashboard: **QuickDrop — Operating (Phase 8)**.

| ID | Scenario | Broken | Fixed |
|---|---|---|---|
| [8.1](phase-8/8.1-tracing.md) | Where did the time go? | the trace ends at the outbox INSERT; SMS spans are orphans | `traceparent` stored with the outbox row: one trace, HTTP → SQL → Kafka → vendor |
| [8.2](phase-8/8.2-alerts.md) | Which alerts should page? | cause alerts (CPU) fire while customers are fine | SLO burn-rate alerts on symptoms → Alertmanager → webhook |
| [8.3](phase-8/8.3-retry-storm.md) | Retry storm | 3 immediate retries at 2 layers: a 10 s hiccup becomes an outage until load stops | one layer, backoff + jitter, retry budget |
| [8.4](phase-8/8.4-load-shedding.md) | Flash sale | browsing takes every connection; checkout times out | priority shedding: browsing gets fast 503s, checkout 100% |
| [8.5](phase-8/8.5-game-day.md) | Game day | — | one hidden fault (`make gameday`), found with dashboards, traces, logs and alerts |

The whole curriculum is now built. [ROADMAP.md](../ROADMAP.md) lists what each phase adds, and
[docs/FAILURE_TRACKER.md](../docs/FAILURE_TRACKER.md) covers what a laptop can't reproduce and what we hit while
building it.
