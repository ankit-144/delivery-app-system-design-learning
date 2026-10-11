# Failure tracker

Things that break real systems that we **can't (or can't honestly) reproduce on a laptop**.
Each entry says what happens, why the lab can't show it, how you'd notice it in production, and what
teams do about it. Entries link back to the scenario that gets closest.

There's also a section of **lessons we hit while building this lab**. They're real and they're
reproducible, just not big enough to be scenarios.

---

## Correctness and coordination

### Distributed transactions (sagas)
*Closest scenarios: 1.1, 5.7.*
Once stock lives in an inventory service and orders in an order service, there's no single database
transaction. "Reserve stock, create order, charge card" becomes three calls, any of which can fail
after the others succeeded.
**Why not here:** our services share one Postgres until Phase 5, and even then a two-shard demo hides
the hard parts: partial failures across teams, versioned APIs, retries across days.
**How you notice:** orders stuck in intermediate states; stock reserved forever for abandoned orders;
reconciliation jobs finding mismatches.
**What teams do:** sagas (each step has a compensating action: release stock, refund), an outbox for
reliable events (Phase 6), idempotent steps (1.3), and nightly reconciliation that compares systems.

### Distributed deadlocks
*Closest scenario: 1.4.*
Service A holds a row lock in its database and calls service B; B holds a lock in its own database and
calls A. No single database sees a cycle, so nothing detects it. Both requests sit until their
timeouts fire.
**How you notice:** requests that always fail at exactly the timeout value; thread dumps show threads
waiting on HTTP calls while holding transactions.
**What teams do:** never hold a lock (or a transaction) across a remote call (2.2), timeouts on every
call (2.7), consistent ordering across services.

### Stale state from the edge
*Closest scenario: 1.2.*
The database says a rider is AVAILABLE, but their phone lost signal 30 seconds ago and they're
actually offline, or already carrying a parcel from another platform. Any state reported by devices
is stale by the time you read it.
**What teams do:** heartbeats with expiry (a rider not heard from in 30 s is treated as offline;
Redis TTLs are good at this, Phase 3), acknowledgement flows (the rider must accept an assignment
within N seconds, or it goes to the next one).

### Hot partitions and sharded counters
*Closest scenarios: 1.5, 5.5.*
One inventory row for a viral product can serve ~155 updates/s in our lab. Real databases do a few
thousand, still far short of a national flash sale.
**Why not here:** our traffic is too small to reach a real database's ceiling.
**What teams do:** split the counter into N rows ("buckets") and spread buyers across them, use
pre-allocated stock tokens (reserve 1,000 units in Redis, sell from there, reconcile later), or put
the sale behind a queue so requests arrive at the rate the row can handle.

### Payments are side effects you can't roll back
*Closest scenario: 1.3.*
A database rollback doesn't un-charge a card. If the order transaction rolls back after the payment
call succeeded, the customer paid for nothing.
**What teams do:** pass the order's idempotency key to the payment provider, record the payment
intent before calling, and reconcile with the provider's records. Call the provider *after* the order
row exists in a `PAYMENT_PENDING` state, never inside a transaction that might roll back.

---

## Infrastructure that a laptop doesn't have

### Multi-region and network partitions
Two data centres lose the link between them. Each side thinks the other is dead. If both keep accepting
writes, you get split-brain: two versions of the truth to reconcile later.
**Why not here:** Toxiproxy can cut one connection (Phase 2–3), but not simulate two regions with
independent failure, latency of 50–200 ms and asymmetric partitions.
**What teams do:** pick a consistency model per data type (CAP in practice), single-writer regions,
fencing tokens, conflict-free data types for things like carts.

### Redis failover loses acknowledged writes
*Closest scenarios: 3.2, R4.*
Redis replicates **asynchronously**: the primary acknowledges a write, then sends it to replicas. If the
primary dies in between and Sentinel (or Redis Cluster) promotes a replica, that write never happened. For a
cache that's fine. For a lock it means a lock that was just granted to client A doesn't exist on the new
primary, so client B can take it too. `WAIT` (wait for N replicas) narrows the window but doesn't close
it; Redlock tries to work around it with a majority of independent nodes, and is controversial for the
reasons in 3.2.
**Why not here:** faking a primary failure at the exact moment of a lock grant on one laptop shows
nothing that 3.2 doesn't already show more clearly.
**What teams do:** fencing tokens checked by the resource (3.2), or a consensus system (etcd,
ZooKeeper) for locks that must be correct, and Redis for locks that are only an optimisation.

### Redis Cluster: slots, resharding, cross-slot commands
Redis Cluster splits keys across 16,384 hash slots spread over shards. Multi-key commands (`MGET`, Lua
scripts touching several keys) only work if all keys are in the same slot. That's why keys use hash
tags like `{store:1}:lock`. Moving slots between nodes during resharding makes some commands briefly
redirect (`MOVED`, `ASK`), and clients must handle that. A hot key can't be split across shards.
**Why not here:** a meaningful cluster needs several nodes and traffic large enough to need them.
*Closest: 5.6 (resharding Postgres), R2 (big keys).*

### DNS caching in load balancers
*Closest scenario: 3.7. We hit it for real in Phase 5.*
nginx resolves `core-1` once, at startup. If the container comes back with a new IP, nginx keeps
sending traffic to the old one. When `make up PHASE=5` recreated the three core containers, every request
through nginx failed with `502 no live upstreams` until nginx was restarted. QuickDrop's upstreams now use
`resolve` with Docker's DNS server (`resolver 127.0.0.11 valid=5s`). In clouds, a load balancer's IP changes and clients that cached DNS
forever keep hitting a dead address. (Java's own DNS cache used to default to "forever".)
**What teams do:** honour DNS TTLs, use `resolve` in nginx upstreams, service discovery that pushes
changes.

### Noisy neighbours, burst credits, disk throttling
Cloud disks and CPUs are shared. A neighbour's batch job, or your own exhausted "burst credits", can make
the same query 10× slower for hours with no change on your side.
**Why not here:** your laptop has one tenant and a fast SSD.
**How you notice:** latency up, CPU *down* (waiting on I/O), cloud metrics such as `VolumeQueueLength`
or `CPUCreditBalance` at zero.

### Kernel and OS limits
Ephemeral port exhaustion (too many outbound connections in `TIME_WAIT`), file descriptor limits,
the conntrack table filling up on a busy NAT gateway. These appear at connection counts a laptop
never reaches.
**How you notice:** `Cannot assign requested address`, `Too many open files`, packets silently dropped.
**What teams do:** connection pooling and keep-alive (we use both), raising limits deliberately,
monitoring them.

### Certificate expiry, clock skew, DST and leap seconds
Classic outage causes with nothing to do with load: a TLS certificate expires at 3 a.m.; a server's
clock drifts and tokens appear "not yet valid" or distributed locks expire early; a job scheduled at
2:30 a.m. runs twice (or never) on a daylight-saving change.
**Note:** our invariant checker compares Java timestamps (`created_at`) with database timestamps
(`stock_reset_at`). On one machine they share a clock. On two machines with skew, a check like that
can produce false alarms.

---

## Databases at sizes a laptop doesn't reach

### Autovacuum falling behind, transaction ID wraparound
Postgres needs VACUUM to reclaim dead rows. On huge, constantly updated tables autovacuum can fall
behind: tables bloat, queries slow down. In the extreme, Postgres stops accepting writes to protect
itself from transaction ID wraparound.
*Closest: Phase 4.5 (bloat from a backfill), Postgres dashboard → Dead rows per table.*

### Long queries on replicas
A long analytics query on a read replica conflicts with replication. Postgres either cancels the query
or delays applying changes (replica lag grows), depending on `hot_standby_feedback`.
*Closest: 5.1.*

---

## Lessons we hit while building this lab

### Evenly spaced load tests hide race conditions
Our first version of scenario 1.2 sent orders exactly every 20 ms and found **no bug**. Real customers
arrive at random (a Poisson process), so some arrive within a millisecond of each other. Switching
k6 to random arrivals took the same code from 0 double-booked riders to ~60. Most load-testing tools
pace requests evenly by default. A load test that doesn't look like production can prove a broken
system correct.

### A warm JVM races less than a cold one
Right after a restart, the JIT hasn't compiled hot paths yet. Requests are slower, so they overlap more
and race more. We saw 64 oversold units on a cold start and ~0 on a warm JVM with evenly spaced traffic.
Production restarts (deploys) are when latent race conditions show up.

### Counters that appear on first use hide their first burst
Prometheus' `rate()` and `increase()` need two samples. A counter created only when the first error
happens has no "before" sample, so the first burst of errors is invisible on the graph, even though
`/actuator/prometheus` shows the count. We hit this with `quickdrop_api_errors_total` and now
register every error code at 0 on startup (`ApiExceptionHandler`).

### With 1 CPU the JVM silently changes garbage collector
The JVM picks its GC from the hardware it sees. With fewer than 2 CPUs (or under ~1.8 GB RAM) it
chooses SerialGC, which pauses everything for every collection, instead of G1. Our core instances get
1 CPU, so the Dockerfile sets `-XX:+UseG1GC` explicitly. Many teams discover this from latency spikes
after moving to small containers.

### The server keeps working after the client gives up
*Scenario 2.2.* k6 abandoned hundreds of requests after its timeout, and core-1 spent another ~70
seconds finishing them: reserving stock, assigning riders and sending SMS for customers who had left.
HTTP has no built-in way to tell the server "never mind". Real systems pass a **deadline** with each
request (gRPC does this natively) and check it before expensive steps, and they shed load early
rather than queueing it.

### Monitoring goes dark when the app is starved
*Scenario 2.6.* With core-1 pinned at its CPU limit, Prometheus' scrapes of `/actuator/prometheus` timed
out, leaving gaps in exactly the graphs you need during the incident. Alert on missing data
(`up == 0`, `absent()`), not only on values, and keep the metrics endpoint cheap.

### Spring's Open Session In View holds a connection for the whole request
*Scenario 2.2.* `spring.jpa.open-in-view` is **on by default**. It keeps a database connection checked out
from the start of the HTTP request to the end, including any slow remote call your controller makes,
which reproduces 2.2's pool exhaustion without any obvious transaction in the code. QuickDrop turns it off.
Spring logs a warning at startup when it's left on; most people never read it.

### Virtual threads pin on `synchronized` (JDK 21–23)
*Scenario 2.8.* A virtual thread that blocks inside a `synchronized` block can't release its carrier
thread. With one CPU there's one carrier, so everything stops, including health checks. Fixed in JDK 24
(JEP 491). On older JDKs, use `ReentrantLock` and run with `-Djdk.tracePinnedThreads=full` before going
to production.

### 64-bit ids break in JavaScript
*Scenario 5.4.* Snowflake ids use ~57 bits; JavaScript numbers (and JSON parsers built on them) are exact
only up to 2^53. k6 silently turned `234838981009739776` into a neighbouring number, and every lookup by id
missed. Browsers do the same. APIs that expose 64-bit ids send them as strings (`"id": "…"`, Twitter's
`id_str`). It never shows up in Java tests, only in the first JavaScript client.

### `CONFIG SET` is undone by a restart
*Scenario R4.* Runtime configuration changes (Redis `CONFIG SET`, our own toggles, `ALTER SYSTEM`
without a reload, feature flags held in memory) disappear on restart unless written back to the startup
configuration. "We fixed it in prod last month" and the fix silently reverts on the next deploy.

### Your monitoring is also load
The invariant checker runs several aggregate queries every 5 seconds on every instance. Once the orders
table held 1.5M rows, it was quietly costing Postgres **half a CPU with no traffic at all**, and every
Postgres measurement in Phases 2–6 included that background load until we noticed (Phase 6). Three bugs, all
classics:
- `created_at >= (SELECT started_at FROM scenario_marker)`: a subquery hides the value from the planner, which
  assumed a third of the table matched and scanned all of it (41 ms). Passing the value as a parameter: index
  scan, 0.7 ms.
- A join from a few thousand recent orders to 2.9M order lines was planned as a full scan of the lines;
  `CROSS JOIN LATERAL` forces an index lookup per order.
- A self-join to find overlapping rider assignments grew quadratically; a window function is linear.
Result: 0.53 → 0.02 Postgres cores at idle, 723 ms → 11 ms per check. Observability has a cost, it grows
with data and instances, and nobody profiles their monitoring.

### The dead-letter topic that didn't exist
*Scenario K4.* We configured Spring Kafka's `DeadLetterPublishingRecoverer` with its defaults and created a topic
called `notifications.DLT`, the name in most tutorials. The library version we run publishes to
`notifications-dlt` instead. Topic auto-creation is off (as it should be in production), so every dead-lettering
blocked for `max.block.ms` (60 s), failed, and the error handler retried the poison message. The partition stayed
stuck, exactly like retry-forever: 48 orders without an SMS in "fixed" mode. Lessons: name the DLT explicitly, and
**test the failure path end to end**. A recovery path that has never run is a guess. An alert on "DLT publish
failures" or on a single partition's lag would have caught it.

### "Waited for the consumers to finish" — but the wait had timed out
*Scenario K3.* A test waited up to 300 s for consumer lag to reach zero, then printed results. The vendor took
100 ms per call, so 6 consumers needed ~6 minutes. The wait timed out silently, and the run reported "4,200 lost
messages" for a strategy that loses none. Two fixes: a timeout is now loud (`!!! … unfinished run`), and lag
counts partitions the group hasn't committed on yet (a lag query over committed offsets only reports 0 for a
partition nobody has committed). The same trap exists in production health checks and drain scripts: "no
lag" for a group that hasn't started yet looks identical to "caught up".

### Virtual threads deadlocked a Kafka consumer service (JDK 21)
*Found while building 8.3.* notification-2 stopped answering HTTP, health checks and Prometheus scrapes, while
Docker said "Up" and its Kafka consumers kept logging. Half of core's calls (client-side load balancing picks an
instance at random) waited for their 2 s timeout. A virtual-thread dump (`jcmd 1 Thread.dump_to_file`, attached from
a JDK container with `--pid=container:qd-notification-2`, since the runtime image has no jcmd) showed why:
- `spring.threads.virtual.enabled=true` also puts Spring Kafka's listener containers on virtual threads.
- `KafkaConsumer.poll()` blocks in epoll inside a `synchronized` block. On JDK 21 that **pins** the carrier thread.
- Four consumers pinned all four carriers. One of them was waiting for Logback's appender lock, which was held by a
  virtual thread that needed a carrier to finish. Deadlock: nothing scheduled on virtual threads ever ran again.

Fix: Kafka consumers on platform threads (`KafkaConsumerThreads.java`); HTTP keeps virtual threads. JDK 24+ (JEP 491)
removes pinning on `synchronized`. Lessons: virtual threads help with many short blocking tasks, not with a few
long-lived pollers; a deadlocked JVM looks "Up" to Docker unless the health check actually exercises a request
thread; and client-side load balancing without health checks keeps sending traffic to a dead instance (the
InstanceDown alert from 8.2 fires because the scrape itself hangs).

### A retry storm ran a service out of memory — and a thread dump stopped it restarting
*Scenario 8.3.* During the broken run, notification-2 died with `OutOfMemoryError: Java heap space`. With virtual
threads, Tomcat's thread limit no longer bounds concurrency: every accepted connection (up to `max-connections`,
default 8,192) gets a thread, and the retry storm filled them, each holding request state, an outgoing connection
and tracing data. Bounded concurrency is a memory limit as much as a CPU limit: notification-service now sets
`server.tomcat.max-connections: 1000`. Extra connections are refused and callers fail fast.

It also didn't come back, despite `restart: unless-stopped`. Earlier we had taken a thread dump with
`docker kill --signal=QUIT`, and Docker marks any container that's been `docker kill`ed (whatever the signal) as
manually stopped, so the restart policy ignored its later crash. Take thread dumps with `jcmd` (or `kill -3`
inside the container), not `docker kill`.

### "Half our traces are broken" — no, the trace store split them
*Scenario 8.1.* With trace propagation through the outbox switched on, a search for "order traces that reach the SMS
vendor" found 409 of 901. It looked like propagation failed for half the orders. A controlled test (30 orders, 30
connected traces) said otherwise. Tempo's ingester treats a trace as complete after `trace_idle_period` (10 s by
default) without new spans and flushes it. An order's SMS spans arrive 10–30 s after its HTTP spans (outbox polling,
Kafka lag, a slow vendor), so they were stored as a *second piece* of the same trace, and a query that needs a
span from each piece matched neither until compaction merged them. Fetching a trace by id always showed the
whole thing. Fix for the lab: `trace_idle_period: 60s` (more memory, and traces become searchable later). Lesson:
long asynchronous flows stretch the assumptions of tracing backends. Before concluding "the instrumentation is
broken", check whether the query engine sees what the store has.

### The only end-to-end test had been broken for three phases
*Found in Phase 8.* `make test` failed: the app connected to `localhost:5432` instead of the Testcontainers
Postgres. Phase 5 added our own `DataSource` beans (primary and replica pools behind a routing DataSource). Defining
them switches off Spring Boot's DataSource auto-configuration, and with it the code that applies
`@ServiceConnection` (Testcontainers) connection details. The fix was a few lines in `DataSourceConfig` (use
`JdbcConnectionDetails` when one exists). The lesson is older than Spring: a test nobody runs isn't a test. Run it
on every change (CI), or it will tell you nothing exactly when you need it.

### Postgres metrics were gone for 3.5 hours, and nothing said so
*Found while writing the quick-start guide.* postgres-exporter answered every scrape with HTTP 500. A custom query
labelled tables by name only; Phase 6 gave notification-service its own `notify.flyway_schema_history`, two tables
now had the same label set, and the exporter refused to serve *any* metric ("collected before with the same name and
label values"). The Postgres dashboard simply went blank. Our `InstanceDown` alert only watched the application jobs.
Fixes: `schemaname` is now a label, and a `ScrapeTargetDown` ticket fires for any target that's down for 2 minutes.
Lesson: monitoring breaks silently too. Alert on the monitoring itself (target health, Watchdog), not only on what
it measures.
