# Build progress

Working log for building QuickDrop. Each task is small enough to finish in one sitting.
`[x]` = done and verified, `[~]` = written but not yet verified against the running stack.

Conventions decided so far (read before continuing):
- Java 21 target, Spring Boot 3.5.16, Maven wrapper (`./mvnw`), packages `dev.quickdrop.*`.
- Docker is used through `sg docker -c "..."` (the Claude process predates the docker group).
- Nothing is ever pushed anywhere. No commits unless the user asks.
- Only 1–2 integration tests (happy path). Effort goes into scenarios, scripts, dashboards.
- Every wrong/right approach lives in code behind a toggle (`quickdrop.toggles.*`), switchable at runtime.
- Containers get CPU and memory caps so bottlenecks are reachable on a laptop.

## Phase 0 — foundation

- [x] T0.1 Maven wrapper, parent pom, module skeletons (common, core-service, vendor-mock)
- [x] T0.2 quickdrop-common: toggle registry, strategy selector, admin endpoints, Grafana annotations
- [x] T0.3 core-service schema (Flyway V1) + seed data (V2), entities, repositories
- [x] T0.4 core-service APIs: place order, get order, order history, rider location/status, OTP
- [x] T0.5 vendor-mock: SMS/push endpoints with latency, error-rate, rate-limit knobs, duplicate detection
- [x] T0.6 metrics, invariant checker, fixtures admin API
- [x] T0.7 Dockerfile + compose base stack (postgres, core, vendor, nginx, toxiproxy, prometheus, grafana, exporters, cadvisor, loki, alloy)
- [x] T0.8 Grafana dashboards (overview, service saturation, postgres, containers)
- [x] T0.9 k6 helpers, Makefile, scripts/toggle.sh
- [x] T0.10 Bring the stack up, smoke-test the happy path, check dashboards populate
- [x] T0.11 One Testcontainers integration test for the happy path
- [x] T0.12 Runbooks 0.1–0.3, docs/FAILURE_TRACKER.md skeleton, README

## Phase 1 — correctness under concurrency

- [x] T1.1 Inventory strategies: naive, synchronized-in-tx, synchronized, pessimistic, optimistic, atomic
- [x] T1.2 Rider assignment strategies: naive, conditional
- [x] T1.3 Idempotency keys
- [x] T1.4 Lock ordering for multi-item carts
- [x] T1.5 k6 scripts 1.1–1.5, verify each reproduces and each fix works
- [x] T1.6 Runbooks 1.1–1.5

## Phase 2 — one box hits the wall

- [x] T2.1 Runtime infra knobs as toggles: tomcat-max-threads, db-pool-size, db-pool-timeout-ms (applied live via
      Tomcat ProtocolHandler / HikariConfigMXBean); GET /admin/runtime (virtual threads on?, cpus, pool, threads)
- [x] T2.2 History seeding (POST /admin/fixtures/history, ~2M orders, idempotent) + admin endpoint to create/drop
      the orders(customer_id, created_at) index (2.4 broken/fixed without psql)
- [x] T2.3 k6 scripts + verification: 2.1 inline vendor 1 s (threads), 2.2 inline-in-tx (pool + idle in tx),
      2.3 bigger pool on DB-CPU-bound history scans, 2.4 index, 2.5 N+1, 2.6 db-scan locator CPU,
      2.7 toxiproxy blackhole + vendor-timeout-ms, 2.8 virtual threads (VIRTUAL_THREADS=true make up)
- [x] T2.4 Runbooks 2.1–2.8, update scenarios/README.md and FAILURE_TRACKER (VT pinning on JDK 21 with synchronized)

## Phase 3 — scale out + Redis

- [x] T3.1 nginx multi-upstream, core-2/core-3, Redis in compose, Redis dashboard
- [x] T3.2 Redis lock (+ fencing), two-tier cache, Redis GEO locator, rate limiters, scheduler lock, stampede protection, Redis-down behaviour
- [x] T3.3 k6 scripts + verification 3.1–3.9
- [x] T3.4 Redis deep-dive scenarios R1–R8 (scripts + runbooks)
- [x] T3.5 Runbooks 3.1–3.9

## Phase 4 — live schema changes

- [x] T4.1 Scenario tooling (long transaction holder, big table generator, v1/v2 app modes)
- [x] T4.2 Scripts + runbooks 4.1–4.6

## Phase 5 — scaling the database

- [x] T5.1 Replica in compose, read routing + read-your-writes
- [x] T5.2 Shards, routing datasource, global IDs, resharding demo
- [x] T5.3 Scripts + runbooks 5.1–5.8

## Phase 6 — notifications → Kafka

- [x] T6.1 notification-service (sync / async-memory / db-queue / kafka modes)
- [x] T6.2 Kafka in compose, exporter, Kafka UI, Kafka dashboard
- [x] T6.3 Outbox relay, dual-write mode, vendor rate limit, priority topics
- [x] T6.4 Scripts + runbooks 6.1–6.8

## Phase 7 — Kafka deep dive

- [x] T7.1 3-broker profile (kafka-cluster, opt-in via K6), lab endpoints with every producer/consumer knob
- [x] T7.2 Scripts + runbooks K1–K11

## Phase 8 — operating it

- [x] T8.1 OpenTelemetry + Tempo, log correlation, exemplars, service graph, outbox trace propagation
- [x] T8.2 Alert rules (SLO burn rates + cause alerts), Alertmanager → notification webhook
- [x] T8.3 Retry storms (Retrier: retries/backoff/budget), load shedding (LoadShedder), game-day script
- [x] T8.4 Runbooks 8.1–8.5, Operating dashboard

## Notes for the next session

- 2026-10-11: Host has only a JRE (no javac). Build with `scripts/mvn.sh` (JDK 21 container) or `docker compose build`.
- Docker on this host is Fedora moby-engine with SELinux enforcing: every bind mount uses `security_opt: label=disable`.
- cAdvisor must be >= v0.57.0 (older versions cannot read containers with Docker 29's containerd image store).
- Stack verified: all Prometheus targets up, every dashboard query resolves (`make check-dashboards`).
- Scenario 1.1 verified: naive oversells ~64/200 at 100 rps with race-window 0; synchronized-in-tx oversells ~1;
  synchronized/pessimistic/optimistic/atomic are correct.
- Run make via `sg docker -c "make ..."` in this Claude session (shell predates docker group membership).

- Phase 1 verified numbers (single core instance, 1 CPU):
  1.1 naive + race-window 5 ms: 256 oversold of 200; all fixes 0. (Cold JVM, window 0: 64 oversold; warm: ~0-3.)
  1.2 naive + race-window 10 ms, 20 riders: 14 riders double-booked; conditional 0.
  1.3 double tap 20 pairs/s 30 s: 600 duplicates off, 0 on.
  1.4 30/s, per-line-delay 10 ms: 65 deadlocks / 40 s, p95 9 s, 8% failed; sorted: 0 deadlocks, p95 24 ms.
  1.5 150/s hot item, per-line-delay 5 ms: optimistic avg 3.66 s + 4379 conflicts; pessimistic 6.9 ms; atomic 6.8 ms.
- Phase 0: 0.2 shows avg 71 ms / p95 45 ms / p99 1505 ms with a 2% slow vendor tail. 0.1 baseline ~85 req/s mix.
- Integration test passes via `make test` (scripts/mvn.sh adds docker group + privileged Ryuk for SELinux).
- Every scenario's applyMode() first POSTs /admin/toggles/reset (all toggles back to startup values), and
  resetFixtures() resets vendor toggles. MODE=keep skips both.
- k6 constant-arrival-rate paces evenly and hides races: use poissonArrivals()+poissonPause() from lib for
  concurrency scenarios. 1.1/1.2 now race with race-window 0 (≈27 oversold, ≈63 double-booked).
- Counters must be pre-registered at 0 or Prometheus increase() misses the first burst.
- Phase 2 verified numbers (core-1 = 1 CPU, Tomcat 50, pool 10, Postgres 2 CPU, vendor 1 s unless noted):
  2.1 60 orders/s inline: order p50 4.3 s, browse 3.3 s, health 3.4 s; timeout 300 ms: order 303 ms, 2264 SMS failed;
      async: order 2 ms but queue 2237 and only 192 sent (4 workers); restart lost 2177 queued SMS.
  2.2 15 orders/s inline-in-tx: order p50 26 s, browse 20 s, health 20% ok; needed ~70 s to drain abandoned
      requests afterwards; inline (after commit): order 1.03 s, browse 1 ms.
  2.3 history 40/s, no index: pool10 14.7 ok/s (orders 7% ok, starved), pool40 17.1/s, pool120 24.1/s but p99 26 s,
      PG memory 560->830 MB of 1 GB, "FATAL: sorry, too many clients already". PG CPU 2.0 in all runs.
      (Bigger pool raised throughput via synchronized seq scans — runbook must say so honestly.)
  2.4 history 40/s: no index p50 20 s, orders 24 s, PG CPU 2.01; with index p50 2 ms (index build 1 s).
  2.5 60/s: naive 54.5 statements/request avg (p95 96), 3236 stmt/s; two-step 2/request, 186/s. With 1 ms toxiproxy
      DB latency: naive p50 66 ms vs 4 ms. CPU ~4x on core and PG.
  2.6 150 orders/s, 20k riders in city 1 (fixture riderFleet; V3 index for db-nearby): db-scan pins core at 1.0 CPU,
      100% throttled, order p50 36 s, health 3% ok, Prometheus scrapes of core-1 failed (graph gaps!);
      db-nearby 150/s p50 4 ms.
  2.7 vendor blackhole (toxiproxy timeout toxic): timeout 0 -> 0% orders ok, 49/50 threads stuck; 500 ms -> 100% ok
      at 506 ms, 876 SMS failed; 90 stuck SMS were sent when the toxic was removed.
  2.8 100 orders/s: platform order p50 12 s, health 42%; VT order 1.04 s, all fine, 24 JVM threads;
      VT + inline-in-tx: 598 waiting for 10 connections, 4097 db_pool_exhausted; VT + synchronized (pinning, JDK 21,
      1 carrier): order 33% ok, health 15% ok — vs platform threads same mode 100% ok p50 10 ms.
- `docker compose up --build` keeps the OLD container if the build fails: always use `make rebuild S=...`.
- k6 setup may wait for a backlog to drain (waitForQuiet); scripts use setupTimeout 6-10m.
- Phase 3 verified numbers (3 core instances, round-robin nginx, Redis 7.4):
  3.1 synchronized on 3 instances: 161 oversold of 200; redis-lock 0; atomic 0.
  3.2 redis-lock, 5% holders freeze 3 s > TTL 2 s: 31 expired under holder, 249 oversold; fencing: 19 writes refused, 0 oversold.
  3.3 local cache: 66.8% stale reads after price change, 192 orders charged old price; redis / two-tier 0 stale.
  3.4 memory locator: 3699/4255 assignment attempts found no rider (instances knew 29/3/28 free riders), 2.70 km;
      redis-geo (available-only GEO set) 0 / 2.06 km; db-nearby 0 / 1.96 km. First GEO version (all riders) failed: 3470/4026.
  3.5 OTP limit 5/min, worst 60 s span — hammer: off 1178, local 30, redis-fixed 10, sliding 5, token bucket 9 (by design);
      boundary bot: off 15, local 15, fixed 10, sliding 5, bucket 5.
  3.6 scheduler none: 29 of 50 riders ghost-busy in 60 s; redis lock 0; skip-locked 0.
  3.7 slow core-2 (+300 ms): RR p95 308 ms; least-conn sends core-2 752 vs ~3200 others but p95 still ~306 ms;
      crash core-3: 5 failed requests; graceful: 1 (keep-alive race).
  3.8 bestsellers 100/s TTL 10 s: cache-aside 1411 expensive queries/60 s, PG 2.02 CPU, 78% ok, p99 5.3 s;
      single-flight 6 queries + 58 stale, PG 0.12, p99 1 ms; no cache 5837 queries, 1.5% ok.
  3.9 Redis blackhole 30 s: fail ~50% errors; degrade (no breaker) order p95 21 s; breaker+compute fallback PG 2.0 CPU,
      76% orders; breaker + last-known fallback: orders/products/bestsellers 100% ok, order p50 8 ms, OTP fail-closed.
- Redis lab admin endpoints exist (RedisLabController) for R1-R3; Redis runs with --enable-debug-command yes.
- Redis deep dive verified: R1 KEYS 1M keys ~55 ms each, product p99 177 ms vs SCAN 1 ms; R2 HGETALL 1M fields 1.3 s,
  DEL 140 ms, UNLINK 0 ms, product max 645 vs 7 ms; R3 64 MB full of no-TTL junk: allkeys-lru bot 6/5, volatile-lru 1170
  (limiter state evicted), noeviction 5 but ~12k cache writes OOM (scripts: OOM only checked on first write);
  R4 RDB kill -9 lost counters and resurrected 450k deleted keys; CONFIG SET appendonly undone by restart;
  REDIS_APPENDONLY=yes survives kill -9 (even last ms — process vs machine crash); R5 146k -> 926 ops/s with 1 ms;
  R6 pub/sub 0 receivers when subscriber gone; streams XPENDING/XAUTOCLAIM/XACK.
- Phase 4 verified numbers (3 instances; DbLabController runs DDL on its own connection):
  4.1 analyst idle-in-tx 30 s, ALTER ADD COLUMN waited 25 s: orders 79% ok p95 11.7 s, browse (no orders table!) 80% ok;
      lock_timeout 2 s + retries: done after 7 timeouts, orders 94% p95 1.8 s, browse 100%.
  4.2 md5(id) expression index ~2.2 s: plain CREATE INDEX orders p99 1.8 s max 2.3 s; CONCURRENTLY p99 56 ms;
      interrupted (long tx + lock_timeout 5 s) leaves INVALID 48 MB index.
  4.3 volatile default rewrite 3.4 s (orders p99 2.9 s); widen INTEGER->BIGINT on order_items 1.6 s; constant default instant.
  4.4 naive rename: 27% of requests failed for 20 s; expand/contract (v1->v1.5->backfill->v2->v3->drop): 100% ok, drift 0.
  4.5 single UPDATE 1.59M rows 51 s: orders 75%, ratings (old rows) 72% with 30 s timeouts, 1.59M dead rows;
      batched by id range (160 tx, 69 s): orders 100%, ratings 100%. First batched version (IS NULL LIMIT) rescanned and crawled.
      orders table grew 352 MB -> 788 MB over repeated backfills (VACUUM reuses, does not return space).
  4.6 two instances + 15 s migration: one migrates, the other waits ~16 s on Flyway's lock (verified, lab migration removed).
- Phase 5 verified (make up PHASE=5: replica + 2 shards; toxiproxy needs a restart after adding proxies to its JSON):
  5.1 replica 1.5 s behind (recovery_min_apply_delay): read-routing replica -> 100% of 676 customers missed their new
      order; replica-ryw -> 0%, still 1482/2229 history reads on the replica; primary -> 0%.
  5.2 shard 1 +50 ms: city key -> My orders asks 2 shards p50 52 ms, store screen 1 shard p50 3 ms; customer key -> reversed.
  5.4 per-shard sequences -> 447/901 lookups ambiguous; Snowflake -> 900/900. JS precision bug: ids > 2^53 must be JSON
      strings (ShardedOrder.id has @JsonFormat STRING).
  5.5 80% Bengaluru: city key -> shard 0 306 writes/s vs shard 1 34 (16162 vs 1839 rows); customer key -> even.
  5.6 resharding 2->3: modulo 66.5%, consistent hashing 36.1% (needs a good hash: single-multiply mix gave 14.6%),
      256 logical buckets 33.2%; 8->9: 88.8 / 12.6 / 10.9.
  5.7 10% crash mid-move: 61 orders left on both shards; idempotent steps + retry: 73/73 completed, 0 duplicates.
  5.8 legacy negative row on shard 1: V3 CHECK applied on shard 0, failed on shard 1 (versions 3 vs 2); fix data, rerun.
- nginx now uses `resolve` + Docker DNS (127.0.0.11): recreated core containers got new IPs and nginx kept old ones (502).
- Always recreate services through `make up` (the Makefile exports phase env vars like SHARD_URLS, REPLICA_DB_URL).
- Invariant checker fixed in Phase 6 (marker as parameter, LATERAL, window function): idle Postgres 0.53 -> 0.02 cores.
  Postgres CPU numbers measured in Phases 2.3-6.x included ~0.5 core of checker load. Not re-measured: those runs
  overload Postgres by a wide margin (2.0 of 2 CPUs, e.g. 2.4 sends 40/s against ~30/s of capacity), so the extra
  0.5 core doesn't change their outcome; absolute latencies would likely be somewhat lower.
- notification-service: Hikari schema=notify (Flyway created tables in notify; app connections searched public).
- db-queue claim is one atomic UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING, with a 30 s lease.
- Phase 6 verified (make up PHASE=6: Kafka KRaft, notification-1/2, kafka-exporter, kafka-ui :8085):
  6.1 80 orders/s, vendor 2 s, http+sync: order p50 2.9 s, browse 915 ms, health 900 ms (30/s on 3 instances: only +2 s).
  6.2 timeout 300 ms: order 309 ms, core counted 1414 dropped — but the vendor received ALL (601/601 in a 20 s check):
      a timeout means "unknown". timeout+breaker+bulkhead: order p50 7 ms.
  6.3 async-memory + crash notification-1 at 30 s: 306/1350 orders never got SMS; db-queue: 53 lost, all on the HTTP hop
      while notification-1 was down (core dropped 55). Bug found: notify schema; claim must be one atomic UPDATE.
  6.4/6.5 60k blast, PG 2 CPU: db-queue PG 0.71, order p95 39 ms; Kafka PG 0.32, order p95 10 ms. 240k + 32 workers: still
      fine (0.74). PG_CPUS=0.5 (production ratio): db-queue PG pinned, order p95 149 / p99 500 ms, 41k rows pending;
      Kafka PG 58%, p95 39 ms, lag 110k then drained. Default 60k: peak lag 54,744, drained 200 s after the run.
  6.6 5% chaos: kafka-direct 26/600 orders without SMS; kafka-in-tx 26 SMS for orders that do not exist; outbox 601/601.
  6.7 vendor 100/s, 20k promos: no limit 600 ok / 19,400 429; local 90/s x2 instances 6,744 / 5,492; redis shared 6,053 / 31.
  6.8 40k promo blast + 2 OTP/s: shared topic 0 of ~120 OTPs delivered in 60 s; priority topic 130 delivered, p95 0.06 s.
- Kafka lag: kafka-exporter series vanish during rebalances; consumer records_lag excludes prefetched messages (6.8k
  reported vs 29.9k real). notification-service LagReporter exports notify_consumer_lag from committed offsets (matches CLI).
- Kafka scenarios flush leftover backlog in setup (flushNotificationBacklog) — a leftover 55k promo backlog broke 6.6 once.
- Core docker healthcheck now uses bash (sh = dash, no /dev/tcp): containers were "unhealthy" since Phase 0.
- Phase 7 verified (Kafka lab endpoints in notification-service /admin/kafka-lab/*, topics lab-1p/lab-6p/lab-out/rider-status):
  K1 30k msgs, 6 consumers, 2 ms each: 1 partition 62.2 s (~483/s, 5 consumers idle); 6 partitions 12.1 s (~2490/s).
  K2 30k events: no key 9,930 out of order; key=order 0.
  K3 20k msgs, 6 crashes: commit after 551 duplicate SMS / 0 lost; commit before (whole batch) 559 lost / 0 dup;
     after+dedupe 827 redeliveries skipped, 0 dup, 0 lost. (Vendor latency 5 ms in K3: at 100 ms the wait timed out.)
  K4 poison on notifications p3: retry-forever lag 43, 42/301 orders without SMS; skip 0 / poison gone; dlq 0 / 1 in DLT.
     Bug found: Spring Kafka's default DLT name was notifications-dlt (not .DLT) -> dlq mode blocked like retry-forever.
  K5 3 consumers, 20 ms/record, max.poll.interval 5 s: 500/poll 1,500 unique in 60 s, 7,500 redone, 18 commits rejected;
     50/poll 9,000 (max), 0 redone.
  K6 3 brokers, RF3, min ISR 2, 20k/s, followers paused 3 s then leader kill -9: acks=1 59,860 acked-but-lost;
     acks=all 0 lost. Two brokers down: acks=1 9,999/10,000 acked (1 copy); acks=all 0 acked.
  K7 replay 15k: reset to earliest -> lag 15,000 -> reprocessed. K9 incentives down 15 s: lag 15,000 flat, analytics 0.
  K8 200k x 300 B, 1 CPU producer: no batching 67k/s (532 ms queued); 16 KB 225k/s; 256 KB 338k/s; lz4 179k/s (37 MB vs 71);
     zstd 161k/s (23 MB). Compression costs CPU, saves bytes.
  K10 compaction needs closed segments: 1 MB segments + segment.ms 10 s: 200k -> 4,825 -> (+1k) 5,825 -> 2,000.
  K11 copier, 14 crashes: plain 21,507 in lab-out (1,507 dup); transactional read_committed 20,000, read_uncommitted 21,299.
- Lab endpoints gotchas: lag over committed offsets ignores partitions without commits (lag endpoint takes topic=);
  restarting a lab group resets its stats; crashEvery must count records seen, not processed.
- Phase 8 verified (make up PHASE=8: Tempo :3200, Alertmanager :9093, TRACING_ENABLED=true everywhere):
  8.1 20 orders/s via outbox, 5% vendor sends 2 s: drop 901 order traces / 0 connected / 901 orphans; propagate 901 / 901 / 0.
      Slowest connected trace: 8.5 ms HTTP, 2.95 s waiting in Kafka, 2.0 s vendor.
  8.2 export job pegs notification-1 CPU t=60-150: HighCpu warning fired t=137, resolved t=167, checkout p99 17 ms;
      core-2 75% checkout errors t=150-330: slow-burn ticket t=189, fast-burn page t=224, resolved t=404.
  8.3 150 OTP/s, vendor 20 workers x 50 ms, 2% errors, hiccup 250 ms t=15-25: none recovers t=40 (147/3 steady);
      broken (3+3 immediate retries) 0 ok/s until load stops, vendor 420-515/s, 16,985 duplicate OTPs, p50 8 s;
      fixed (2 retries, jitter, budget, 1 layer) recovers t=40, 150/0 steady, 63 duplicates.
  8.4 40 checkouts/s + 300/s browse surge (no caches): off 41.7% checkout ok, p50 30 s, 7,827 requests unsendable;
      priority shedding 100% checkout ok, p50 138 ms p99 1.06 s, 12,084 browse refused in <= 3 ms.
  8.5 all 10 game-day faults inject/reveal/revert verified; 8.5 traffic baseline 100% ok on every stream.
- Phase 8 bugs found and fixed (see FAILURE_TRACKER): datasource-micrometer proxies vs HikariDataSource injection
  (excluded-datasource-bean-names); TracingNoiseFilter must walk the parent chain (dropped parent = no-op, not null);
  virtual-thread Kafka consumers deadlocked notification-2 on JDK 21 (consumers now platform threads); retry storm
  OOM (notification max-connections 1000); docker kill -s QUIT disables restart policy; Tempo trace_idle_period
  split late spans (60 s now); make test broken since Phase 5 (DataSourceConfig ignored @ServiceConnection) — passes now.

