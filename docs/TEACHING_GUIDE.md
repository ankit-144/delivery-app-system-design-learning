# Teaching QuickDrop: the session guide

A step-by-step plan for teaching with this repo: ten sessions, the commands to type, what to show on screen, what the
audience should see, and the point to land. Every demo links to its runbook in [scenarios/](../scenarios/README.md),
which has the full story, the measured results and discussion questions.

> **Quick links:** [architecture page](architecture.html) (open in a browser) · [cheat sheet](CHEATSHEET.md) ·
> [all scenarios](../scenarios/README.md) · [roadmap](../ROADMAP.md) · [failure tracker](FAILURE_TRACKER.md)

## At a glance

| #   | Session                       | Start with        | Core demos                   | Time    |
| --- | ----------------------------- | ----------------- | ---------------------------- | ------- |
| 1   | Eyes on                       | `make up PHASE=0` | 0.1, 0.2                     | ~75 min |
| 2   | Correctness under concurrency | `make up PHASE=1` | 1.1, 1.2, 1.3, 1.4           | ~2 h    |
| 3   | One box hits the wall         | `make up PHASE=2` | 2.1, 2.2, 2.4, 2.7           | ~2.5 h  |
| 4   | Scale out                     | `make up PHASE=3` | 3.1, 3.2, 3.3, 3.8, 3.9      | ~2.5 h  |
| 5   | Redis deep dive               | `make up PHASE=3` | R1, R3, R5                   | ~1.5 h  |
| 6   | Live schema changes           | `make up PHASE=3` | 4.1, 4.2, 4.4, 4.5           | ~2 h    |
| 7   | Scaling the database          | `make up PHASE=5` | 5.1, 5.2, 5.6                | ~2 h    |
| 8   | Notifications and Kafka       | `make up PHASE=6` | 6.1, 6.2, 6.5, 6.6, 6.8      | ~2.5 h  |
| 9   | Kafka deep dive               | `make up PHASE=6` | K1, K2, K3, K4, K6           | ~2.5 h  |
| 10  | Operating it                  | `make up PHASE=8` | 8.1, 8.2, 8.3, 8.4, game day | ~3 h    |

⭐ marks a core demo. Each session also lists extra demos for when there's time or interest.

## Before the first session

1. **Machine:** Docker with Compose v2, 16 GB RAM (Phase 8 uses ~8 GB), ~15 GB free disk.
2. **Build everything once** (pulls and builds every image; takes a while the first time):

   ```bash
   make up PHASE=8 && make ps        # then: make down
   ```
3. **Dry run one demo per session** the day before, so nothing surprises you (first runs seed ~1.5M history rows, ~20 s).
4. **Screen layout:** terminal on the left, Grafana on the right (time range *Last 15 minutes*, refresh *5s*), the runbook
   on your own screen. Increase the terminal font: the verdicts printed at the end are part of the show.
5. **Know the reset buttons:** every load script resets toggles and fixtures itself. If something looks off:
   `make reset-toggles`, `make clear-toxics`, then rerun.
6. **Phases only go up cleanly.** `make up PHASE=n` adds containers but never stops the ones a higher phase started.
   Going back down (say, rehearsing Session 2 after Session 8)? Run `make down` first.

## How to run every demo

The same six steps every time. The audience's prediction is what makes the result stick.

| Step       | What you do                                                                      | Time    |
| ---------- | -------------------------------------------------------------------------------- | ------- |
| 1. Story   | Read the runbook's story in your own words: the business situation, not the bug  | 1 min   |
| 2. Predict | Ask the question in **Ask the room first**. Write the guesses down               | 2 min   |
| 3. Break   | Run the broken mode. Narrate the dashboard while it runs; read the verdict aloud | 2–5 min |
| 4. Explain | Walk through the runbook's *Why* section, and the code if there's a pointer      | 5 min   |
| 5. Fix     | Run the fixed mode. Same dashboards, same time window: point at the difference   | 2–5 min |
| 6. Discuss | One or two questions from the runbook's *Discussion* section                     | 5 min   |

**Tricks that work well**

- **Flip it live.** Start a scenario with `MODE=keep` so the script leaves toggles alone, then flip a toggle mid-run
  (`make toggle NAME=inventory-reservation VALUE=atomic`). The graph changes in front of everyone, and every flip is
  drawn as an orange line on the dashboards.
- **Show what's running:** `make toggles` lists the strategy each instance uses right now.
- **Break the network by hand:** `make toxics` shows the Toxiproxy proxies; the runbooks have the `curl` commands to
  add latency or cut a connection.
- **Let someone else drive.** From Session 3 on, have an audience member type the commands while you narrate.

---

## Session 1 — Eyes on: reading a running system

**~75 min** · **Goal:** everyone can read the dashboards before anything breaks.

**Set up** (before people arrive):

```bash
make down                    # stop anything left from a higher phase
make up PHASE=0
make ps                      # all Up, core-1 and postgres (healthy)
```

**Keep open:** [Overview](http://localhost:3000/d/quickdrop-overview), [Service saturation](http://localhost:3000/d/quickdrop-saturation), [Containers & logs](http://localhost:3000/d/quickdrop-containers)

**Opening story (2 min):** QuickDrop is a 10-minute grocery app: one service, one database, an SMS vendor. Today nothing is broken. We learn to see what "healthy" looks like, so we recognise "sick" later.

Start with a 5-minute tour of [docs/architecture.html](architecture.html) (open it in a browser): the request path, the monitoring pipeline, how phases add pieces.

#### ⭐ 0.1 — A normal day · [runbook](../scenarios/phase-0/0.1-baseline.md)

- **Ask the room first:** out of every 100 requests, how many are orders?
- **Run:**

  ```bash
  make load ID=0.1           # 5 minutes of mixed traffic, ~90 req/s
  ```

- **Show:** Overview → *Requests per second*, *p99 latency*, *Invariant violations* (all zero); Service saturation → *Busy threads vs max*
- **Expect:** orders are ~10% of traffic; threads, pool and CPU far below their limits.
- **Land it:** RED (rate, errors, duration) for each endpoint, USE (utilisation, saturation, errors) for each resource. Little's law: in-flight = rate × latency.

#### ⭐ 0.2 — Averages lie · [runbook](../scenarios/phase-0/0.2-averages-lie.md)

- **Ask the room first:** the vendor is slow for 2% of calls. What happens to the average? To p99?
- **Run:**

  ```bash
  make load ID=0.2              # vendor with a 2% slow tail
  make load ID=0.2 MODE=fixed   # same vendor, no tail
  ```

- **Show:** Overview → *Order placement latency* (four lines), *k6 request duration (client side)*
- **Expect:** average 71 ms, p50 40 ms, p95 45 ms, **p99 1,505 ms**.
- **Land it:** averages and p50 hide the customers who wait; alert and plan on percentiles.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [0.3 Flyway basics](../scenarios/phase-0/0.3-flyway-basics.md) | hands-on with `make psql`, follow the runbook | versioned migrations, checksums, what happens when you edit an applied one |

**Wrap-up (10 min):** Ask everyone to point at the panel that would show "we're out of threads" and "the database is the bottleneck". Hand out [docs/CHEATSHEET.md](CHEATSHEET.md).

---
## Session 2 — Correctness under concurrency

**~2 h** · **Goal:** see that code which is correct for one request is wrong for two at once, and fix it in the database.

**Set up** (before people arrive):

```bash
make up PHASE=1
make reset-toggles
```

**Keep open:** [Overview](http://localhost:3000/d/quickdrop-overview) (*Invariant violations*), [Postgres](http://localhost:3000/d/quickdrop-postgres)

**Opening story (2 min):** A flash sale: 200 units of a product and 100 orders per second. Every test passed. What could go wrong?

#### ⭐ 1.1 — Overselling the last units · [runbook](../scenarios/phase-1/1.1-oversell.md)

- **Ask the room first:** 200 in stock, ~100 orders/s for 30 s. How many units get sold?
- **Run:**

  ```bash
  make load ID=1.1 MODE=broken     # read stock, check, write
  make load ID=1.1 MODE=fixed      # one atomic conditional UPDATE
  make load ID=1.1 MODE=broken RACE_WINDOW=5   # make it dramatic (~120 oversold)
  ```

- **Show:** Overview → *Invariant violations*, *Orders per second by result*; the verdict printed at the end
- **Expect:** broken: **~227 sold of 200** (25–30 oversold, never 0); fixed: exactly 200.
- **Land it:** check-then-act is a race. Put the condition into the write: `UPDATE … SET qty = qty - n WHERE qty >= n`. Also compare `MODE=synchronized`, `pessimistic`, `optimistic`, `sync-in-tx` (all in the runbook).
- **Show the code:** [`inventory/strategy/`](../services/core-service/src/main/java/dev/quickdrop/core/inventory/strategy/): `NaiveReservation` vs `AtomicReservation`

#### ⭐ 1.2 — Two orders, one rider · [runbook](../scenarios/phase-1/1.2-double-rider.md)

- **Ask the room first:** 200 riders, 50 orders/s. Can one rider end up with two orders?
- **Run:**

  ```bash
  make load ID=1.2 MODE=broken
  make load ID=1.2 MODE=fixed
  ```

- **Show:** Overview → *Invariant violations* (rider_double_booked), *Riders*, *Rider assignment*
- **Expect:** broken: **~63 riders double-booked**; fixed: 0.
- **Land it:** the same race on a different table. Conditional update on the rider's status, plus a partial unique index as the last line of defence.
- **Show the code:** [`rider/strategy/`](../services/core-service/src/main/java/dev/quickdrop/core/rider/strategy/): `NaiveAssignment` vs `ConditionalAssignment`

#### ⭐ 1.3 — Double tap / client retry · [runbook](../scenarios/phase-1/1.3-duplicate-orders.md)

- **Ask the room first:** the app retries a request that timed out. How many orders does the customer get?
- **Run:**

  ```bash
  make load ID=1.3 MODE=broken
  make load ID=1.3 MODE=fixed
  ```

- **Show:** Overview → *Invariant violations* (duplicate orders), *Orders per second by result* (replayed)
- **Expect:** broken: **600 duplicate orders** in 30 s; fixed: 0.
- **Land it:** retries are normal; make them safe with an idempotency key claimed in the database.
- **Show the code:** [`order/IdempotencyDao.java`](../services/core-service/src/main/java/dev/quickdrop/core/order/IdempotencyDao.java)

#### ⭐ 1.4 — Deadlocks on carts · [runbook](../scenarios/phase-1/1.4-deadlock.md)

- **Ask the room first:** two carts with the same items in a different order. What can happen?
- **Run:**

  ```bash
  make load ID=1.4 MODE=broken
  make load ID=1.4 MODE=fixed
  ```

- **Show:** Postgres → *Deadlocks*, *Sessions waiting for a lock*; Overview → *API errors by type*
- **Expect:** broken: **65 deadlocks in 40 s**, p95 9 s, 8% failed; fixed (lock rows in product-id order): 0 deadlocks, p95 24 ms.
- **Land it:** lock resources in one global order. Postgres detects a deadlock after 1 s and kills one side.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [1.5 Hot item](../scenarios/phase-1/1.5-hot-item.md) | `MODE=optimistic`, `MODE=pessimistic`, `MODE=fixed` | optimistic retries melt down under contention (avg 3.66 s, 4,379 conflicts); pessimistic 6.9 ms; atomic 6.8 ms |

**Wrap-up (10 min):** Which of these would a unit test catch? (None: they need concurrency.) Where else in your systems is there a read-then-write?

---
## Session 3 — One box hits the wall

**~2.5 h** · **Goal:** find the resource that runs out (threads, connections, CPU, the database) and learn why "make it bigger" rarely helps.

**Set up** (before people arrive):

```bash
make up PHASE=2
make reset-toggles && make clear-toxics
```

**Keep open:** [Service saturation](http://localhost:3000/d/quickdrop-saturation), [Postgres](http://localhost:3000/d/quickdrop-postgres), [Containers](http://localhost:3000/d/quickdrop-containers)

**Opening story (2 min):** Business is growing. Each demo has the same shape: something slow or blocking, one finite resource, and a queue in front of it.

#### ⭐ 2.1 — Slow vendor, SMS sent inline · [runbook](../scenarios/phase-2/2.1-slow-vendor.md)

- **Ask the room first:** the SMS vendor takes 1 s. 60 orders/s. What happens to *browsing*, which never calls the vendor?
- **Run:**

  ```bash
  make load ID=2.1 MODE=broken    # SMS inline in the request
  make load ID=2.1 MODE=timeout   # give up after 300 ms
  make load ID=2.1 MODE=async     # in-memory queue
  ```

- **Show:** Service saturation → *Busy threads vs max* (pinned at 50/50), *Vendor call latency*
- **Expect:** broken: order p50 4.3 s, browse 3.3 s, even health checks 3.4 s; timeout: 303 ms but 2,264 SMS failed; async: 2 ms, but the queue grows and a restart loses it.
- **Land it:** one slow dependency eats a shared pool, and everything queues behind it. Every fix moves the problem; know where it went.

#### ⭐ 2.2 — Vendor call inside the transaction · [runbook](../scenarios/phase-2/2.2-transaction-holds-connection.md)

- **Ask the room first:** what does a transaction hold while it waits for the vendor?
- **Run:**

  ```bash
  make load ID=2.2 MODE=broken
  make load ID=2.2 MODE=fixed
  ```

- **Show:** Service saturation → *Connections: active / idle / waiting* (10/10), *Time to get a connection / time holding one*; Postgres → *Longest open transaction*
- **Expect:** broken: order p50 **26 s**, browse 20 s; fixed (call after commit): order 1.03 s, browse 1 ms.
- **Land it:** never make remote calls inside a transaction: it holds a connection and row locks for the whole call.

#### ⭐ 2.4 — Missing index · [runbook](../scenarios/phase-2/2.4-missing-index.md)

- **Ask the room first:** order history gets slow as the table grows. Do we add servers?
- **Run:**

  ```bash
  make load ID=2.4 MODE=broken
  make load ID=2.4 MODE=fixed     # CREATE INDEX CONCURRENTLY
  ```

- **Show:** Postgres → *Rows read by sequential scans, per table*, *Top queries by total time*, *Postgres container CPU* (2.0 of 2)
- **Expect:** broken: history p50 **20 s**, Postgres CPU 2.0 of 2; fixed: **2 ms** (the index builds in ~1 s).
- **Land it:** one missing index costs more than any amount of hardware. Read the query plan first.

#### ⭐ 2.7 — Silent vendor, no timeout · [runbook](../scenarios/phase-2/2.7-no-timeouts.md)

- **Ask the room first:** the vendor stops answering (no error, nothing). How long do we wait?
- **Run:**

  ```bash
  make load ID=2.7 MODE=broken
  make load ID=2.7 MODE=fixed
  ```

- **Show:** Service saturation → *Busy threads vs max*, *JVM threads by state*, *Vendor call latency*
- **Expect:** broken: **0% of orders succeed**, 49 of 50 threads stuck forever; fixed (500 ms timeout): 100% at ~506 ms.
- **Land it:** every remote call needs a timeout. The default is often "forever".

**If you have time**

| Demo                                                                          | Run                                                              | Expect                                                                                |
| ----------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| [2.3 Just make the pool bigger](../scenarios/phase-2/2.3-bigger-pool.md)      | `MODE=pool10`, `pool40`, `pool120`                               | pool120 hits `too many clients`, p99 26 s; Postgres CPU 2.0 in every run              |
| [2.5 N+1 queries](../scenarios/phase-2/2.5-n-plus-one.md)                     | `MODE=broken` then `fixed`                                       | 54.5 SQL statements per request vs 2                                                  |
| [2.6 Matching loads every rider](../scenarios/phase-2/2.6-scan-all-riders.md) | `MODE=broken` then `fixed`                                       | core pinned at 1 CPU, order p50 36 s, metrics go dark (scrapes fail); fixed 4 ms      |
| [2.8 Virtual threads](../scenarios/phase-2/2.8-virtual-threads.md)            | follow the runbook (restarts core-1 with `VIRTUAL_THREADS=true`) | the thread wall disappears (12 s → 1 s); then the DB pool and pinning become the wall |

**Wrap-up (10 min):** Draw the request path on a whiteboard and mark every pool and queue on it. Which one ran out in each demo?

---
## Session 4 — Scale out: three instances, nginx, Redis

**~2.5 h** · **Goal:** see what breaks when one process becomes three, and use Redis correctly to fix it.

**Set up** (before people arrive):

```bash
make up PHASE=3                # core-2, core-3 and Redis join
make ps
```

**Keep open:** [Overview](http://localhost:3000/d/quickdrop-overview) (*Requests per second by instance*), [Redis & scale-out](http://localhost:3000/d/quickdrop-redis)

**Opening story (2 min):** We added two more instances behind nginx. Anything that lived in one JVM's memory (locks, caches, counters, schedules) just got copied three times.

#### ⭐ 3.1 — The JVM lock on three instances · [runbook](../scenarios/phase-3/3.1-jvm-lock-scaled.md)

- **Ask the room first:** 1.1's `synchronized` fix worked. Does it still work with three instances?
- **Run:**

  ```bash
  make load ID=3.1 MODE=broken      # synchronized
  make load ID=3.1 MODE=redis-lock  # distributed lock
  make load ID=3.1 MODE=fixed       # no lock: atomic UPDATE
  ```

- **Show:** Overview → *Invariant violations*, *Requests per second by instance*
- **Expect:** synchronized: **161 oversold** of 200; Redis lock and atomic UPDATE: 0.
- **Land it:** a JVM lock only locks one JVM. Best of all: don't need a lock (atomic statement).

#### ⭐ 3.2 — Redis lock expires under a frozen holder · [runbook](../scenarios/phase-3/3.2-redis-lock-expiry.md)

- **Ask the room first:** a lock with a 2 s TTL, and a holder that freezes for 3 s (GC pause). Then?
- **Run:**

  ```bash
  make load ID=3.2 MODE=broken
  make load ID=3.2 MODE=fixed     # fencing tokens checked by Postgres
  ```

- **Show:** Redis & scale-out → *Distributed lock*; Overview → *Invariant violations*
- **Expect:** broken: 31 locks expired under their holder, **249 oversold**; fixed: 19 stale writes refused, 0 oversold.
- **Land it:** a lease is not a lock. The resource itself must reject stale holders (fencing tokens).

#### ⭐ 3.3 — Price change with local caches · [runbook](../scenarios/phase-3/3.3-stale-cache.md)

- **Ask the room first:** we change a price. What do the three instances show?
- **Run:**

  ```bash
  make load ID=3.3 MODE=broken   # each JVM caches for 5 min
  make load ID=3.3 MODE=redis    # one shared copy
  make load ID=3.3 MODE=fixed    # two-tier + pub/sub invalidation
  ```

- **Show:** Redis & scale-out → *Product cache*
- **Expect:** local: **67% stale reads**, 192 orders charged the old price; Redis / two-tier: 0 stale.
- **Land it:** a cache is a copy, and copies disagree. Decide who invalidates it and how fast.

#### ⭐ 3.8 — Cache stampede · [runbook](../scenarios/phase-3/3.8-cache-stampede.md)

- **Ask the room first:** the best-sellers cache expires every 10 s and 100 requests/s want it. What hits Postgres?
- **Run:**

  ```bash
  make load ID=3.8 MODE=broken   # cache-aside
  make load ID=3.8 MODE=fixed    # single-flight + serve stale
  ```

- **Show:** Redis & scale-out → *Best sellers: queries running at once*; Postgres → *Postgres container CPU* (sawtooth)
- **Expect:** cache-aside: **1,411 expensive queries/min**, Postgres 2.0 CPU, 78% ok; fixed: 6 queries, Postgres 0.12, p99 1 ms.
- **Land it:** an expiring hot key turns into a thundering herd. Let one request recompute while the rest serve the stale copy.

#### ⭐ 3.9 — Redis goes away · [runbook](../scenarios/phase-3/3.9-redis-down.md)

- **Ask the room first:** Redis disappears for 30 s. Which features should still work?
- **Run:**

  ```bash
  make load ID=3.9 MODE=broken    # everything Redis-backed fails
  make load ID=3.9 MODE=degrade   # fall back to Postgres
  make load ID=3.9 MODE=fixed     # breaker + cheap fallbacks
  ```

- **Show:** Redis & scale-out → *Redis circuit breaker*; Postgres → *Postgres container CPU*
- **Expect:** fail: ~50% errors; naive fallbacks melt Postgres (2.0 CPU, order p95 21 s); fixed: 100% ok, order p50 8 ms, OTP fails closed.
- **Land it:** a fallback must be cheaper than the thing it replaces. Decide per feature: fail open or fail closed.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [3.4 Rider positions in memory](../scenarios/phase-3/3.4-rider-locations.md) | `MODE=broken`, `fixed`, `db` | memory: 87% of matches find nobody; Redis GEO: 0 |
| [3.5 Rate limiting an OTP bot](../scenarios/phase-3/3.5-rate-limiting.md) | `MODE=off`, `broken`, `redis-fixed`, `fixed`, `token-bucket` | limit 5/min: off 1,178, per-instance 30, fixed window 10, sliding log 5 |
| [3.6 `@Scheduled` on every instance](../scenarios/phase-3/3.6-scheduler-on-every-instance.md) | `MODE=broken`, `redis`, `fixed` | 29 of 50 riders ghost-busy; scheduler lock / SKIP LOCKED: 0 |
| [3.7 Slow and dying instances](../scenarios/phase-3/3.7-load-balancer.md) | `MODE=slow`, then `make lb ALGO=least-conn`; `MODE=crash`, `MODE=graceful` | round-robin keeps feeding the slow instance; graceful shutdown loses 1 request vs 5 |

**Wrap-up (10 min):** Make a list of everything your own services keep in memory. Which of it breaks with two instances?

---
## Session 5 — Redis deep dive

**~1.5 h** · **Goal:** understand the one fact behind most Redis incidents: one thread runs every command, in order.

**Set up** (before people arrive):

```bash
make up PHASE=3
```

**Keep open:** [Redis & scale-out](http://localhost:3000/d/quickdrop-redis), and a terminal with `docker exec -it qd-redis redis-cli`

**Opening story (2 min):** Redis is fast because it is simple. The same simplicity makes a single bad command everyone's problem.

#### ⭐ R1 — KEYS on a million keys · [runbook](../scenarios/redis/R1-keys-blocks-everyone.md)

- **Ask the room first:** a debug endpoint runs `KEYS *`. What happens to product reads?
- **Run:**

  ```bash
  make load ID=R1 MODE=broken    # KEYS
  make load ID=R1 MODE=fixed     # SCAN … COUNT 1000
  ```

- **Show:** Redis & scale-out → *Average time per command*; `SLOWLOG GET 5` in redis-cli
- **Expect:** KEYS: product p99 **177 ms**, max 1 s; SCAN: p99 1 ms.
- **Land it:** Redis runs one command at a time. One slow command blocks every client.

#### ⭐ R3 — Memory full · [runbook](../scenarios/redis/R3-eviction.md)

- **Ask the room first:** Redis is full. Which keys get evicted, and what breaks?
- **Run:**

  ```bash
  make load ID=R3 MODE=broken       # allkeys-lru
  make load ID=R3 MODE=volatile     # volatile-lru
  make load ID=R3 MODE=fixed        # noeviction + enough memory
  ```

- **Show:** Redis & scale-out → *Memory used vs maxmemory*, *Evicted and expired keys per second*, *Rate limiter decisions*
- **Expect:** volatile-lru evicted the rate limiter's state: the OTP bot got **1,170** codes instead of 5.
- **Land it:** keep cache data and coordination data (limits, locks) apart; eviction policy is a correctness decision.

#### ⭐ R5 — Round trips · [runbook](../scenarios/redis/R5-round-trips.md)

- **Ask the room first:** 1 ms of network latency. How many operations per second can one client do?
- **Run:**

  ```bash
  # hands-on: follow the runbook (adds 1 ms of latency with Toxiproxy)
  ```

- **Show:** redis-cli `--latency`; Redis & scale-out → *Commands per second by command*
- **Expect:** 146k ops/s with no latency → **926 ops/s** with 1 ms.
- **Land it:** latency, not Redis, is the limit. Batch: `MGET`, pipelining, Lua.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [R2 A million-field hash](../scenarios/redis/R2-big-keys.md) | `MODE=broken` then `fixed` | HGETALL 1.3 s, DEL 140 ms; UNLINK 0 ms |
| [R4 kill -9 Redis](../scenarios/redis/R4-persistence.md) | hands-on, follow the runbook | RDB loses minutes and resurrects deleted keys; AOF survives |
| [R6 Pub/sub vs Streams](../scenarios/redis/R6-pubsub-vs-streams.md) | hands-on, follow the runbook | pub/sub drops messages with no subscriber; streams keep them, with consumer groups |

**Wrap-up (10 min):** What's stored in your Redis today? Which of it would hurt if evicted or lost on restart?

---
## Session 6 — Changing the schema while the system is live

**~2 h** · **Goal:** ship schema changes without freezing production.

**Set up** (before people arrive):

```bash
make up PHASE=3                # 4.4 needs three instances; the rest work on any phase
```

**Keep open:** [Postgres](http://localhost:3000/d/quickdrop-postgres) (*Sessions waiting for a lock*, *Longest open transaction*), [Overview](http://localhost:3000/d/quickdrop-overview)

**Opening story (2 min):** Every change works on a laptop with no traffic. Production has traffic, long transactions, and old versions of the code still running.

#### ⭐ 4.1 — An instant ALTER behind an idle transaction · [runbook](../scenarios/phase-4/4.1-alter-behind-long-transaction.md)

- **Ask the room first:** `ALTER TABLE … ADD COLUMN` is instant. Can it take the site down?
- **Run:**

  ```bash
  make load ID=4.1 MODE=broken
  make load ID=4.1 MODE=fixed     # lock_timeout + retries
  ```

- **Show:** Postgres → *Sessions waiting for a lock*, *Longest open transaction*; Overview → *p99 latency*
- **Expect:** broken: orders 79% ok, p95 11.7 s; even browsing (which never touches orders) suffers; fixed: orders 94%, browse 100%.
- **Land it:** the ALTER waits for a lock, and everything queues behind *it*. Always set `lock_timeout` for DDL.

#### ⭐ 4.2 — Index on a busy table · [runbook](../scenarios/phase-4/4.2-create-index.md)

- **Ask the room first:** we add an index to a table that takes writes all day. Do writes stop?
- **Run:**

  ```bash
  make load ID=4.2 MODE=broken        # CREATE INDEX
  make load ID=4.2 MODE=fixed         # CREATE INDEX CONCURRENTLY
  make load ID=4.2 MODE=interrupted   # what's left behind when it fails
  ```

- **Show:** Overview → *p99 latency*; Postgres → *Sessions waiting for a lock*
- **Expect:** plain: orders p99 1.8 s; CONCURRENTLY: p99 56 ms; interrupted: a 48 MB INVALID index left behind.
- **Land it:** CONCURRENTLY for anything with traffic, and check for INVALID leftovers.

#### ⭐ 4.4 — Rename a column during a rolling deploy · [runbook](../scenarios/phase-4/4.4-rename-column.md)

- **Ask the room first:** old and new code run side by side for a minute. What breaks?
- **Run:**

  ```bash
  make load ID=4.4 MODE=broken
  make load ID=4.4 MODE=fixed     # expand → migrate → contract
  ```

- **Show:** Overview → *5xx error ratio*, *Requests per second by instance*
- **Expect:** naive rename: **27% of requests fail** for 20 s; expand/contract: 100% ok.
- **Land it:** every schema change must work with the previous version of the code too.

#### ⭐ 4.5 — Backfill 1.5M rows · [runbook](../scenarios/phase-4/4.5-backfill.md)

- **Ask the room first:** one UPDATE to fill a new column. What does it lock, and for how long?
- **Run:**

  ```bash
  make load ID=4.5 MODE=broken    # one transaction
  make load ID=4.5 MODE=fixed     # batches by primary-key range
  ```

- **Show:** Postgres → *Longest open transaction*, *Dead rows per table*; Overview → *API errors by type*
- **Expect:** one UPDATE: 51 s, orders 75% ok, 1.59M dead rows; batched: 100% ok.
- **Land it:** big data changes go in small batches, keyed by primary-key range.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [4.3 Which DDL rewrites the table](../scenarios/phase-4/4.3-add-column-rewrite.md) | `MODE=fixed`, `broken`, `widen` | constant default instant; volatile default rewrites (3.4 s); INT → BIGINT rewrites |
| [4.6 Flyway with several instances](../scenarios/phase-4/4.6-flyway-in-production.md) | read and discuss the runbook | migrations as a pipeline step, not at startup |

**Wrap-up (10 min):** Write a checklist for your next migration: lock_timeout, CONCURRENTLY, expand/contract, batches, rollback plan.

---
## Session 7 — Scaling the database

**~2 h** · **Goal:** read replicas and sharding: what you gain, and the bugs you sign up for.

**Set up** (before people arrive):

```bash
make up PHASE=5                # adds a streaming replica and two shards
make ps
```

**Keep open:** [Postgres](http://localhost:3000/d/quickdrop-postgres) (*Replica lag*, *History reads: primary vs replica*, *Sharded queries per shard*)

**Opening story (2 min):** One Postgres is at its limit. Option one: send reads to a copy. Option two: split the data. Both change what "consistent" means.

#### ⭐ 5.1 — Read replica · [runbook](../scenarios/phase-5/5.1-read-replica.md)

- **Ask the room first:** a customer places an order and opens "My orders" a second later. Is it there?
- **Run:**

  ```bash
  make load ID=5.1 MODE=primary   # everything on the primary
  make load ID=5.1 MODE=broken    # reads on the replica (1.5 s behind)
  make load ID=5.1 MODE=fixed     # read-your-writes for 5 s
  ```

- **Show:** Postgres → *Replica lag*, *History reads: primary vs replica*
- **Expect:** replica: **100% of customers miss their new order**; read-your-writes: 0%, and still 2/3 of reads offloaded.
- **Land it:** replicas are always behind. Route reads that must see your own writes to the primary.
- **Show the code:** [`db/ReadRouting.java`](../services/core-service/src/main/java/dev/quickdrop/core/db/ReadRouting.java)

#### ⭐ 5.2 — Choosing a shard key · [runbook](../scenarios/phase-5/5.2-shard-key.md)

- **Ask the room first:** shard by city or by customer? Which screen gets slow?
- **Run:**

  ```bash
  make load ID=5.2 MODE=city
  make load ID=5.2 MODE=customer
  ```

- **Show:** Postgres → *Sharded queries per shard*; the per-screen latency the script prints
- **Expect:** city key: "My orders" asks both shards (p50 52 ms), store screen 3 ms; customer key: the reverse.
- **Land it:** the shard key decides which queries are cheap. Every other query becomes a scatter-gather.
- **Show the code:** [`sharding/ShardRouter.java`](../services/core-service/src/main/java/dev/quickdrop/core/sharding/ShardRouter.java)

#### ⭐ 5.6 — Resharding · [runbook](../scenarios/phase-5/5.6-resharding.md)

- **Ask the room first:** we go from 2 shards to 3. How much data moves?
- **Run:**

  ```bash
  # hands-on with curl: follow the runbook
  ```

- **Show:** the numbers the endpoint returns
- **Expect:** modulo moves **67%** of keys (89% for 8 → 9); consistent hashing ~36%; 256 logical buckets ~33%.
- **Land it:** plan for resharding on day one: logical buckets mapped to physical shards.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [5.3 How requests find their shard](../scenarios/phase-5/5.3-routing.md) | code walkthrough with curl | router, pools, app-side vs proxy vs distributed SQL |
| [5.4 Ids across shards](../scenarios/phase-5/5.4-global-ids.md) | `MODE=broken` then `fixed` | per-shard sequences collide (447/901 ambiguous); Snowflake ids; the JavaScript 2^53 trap |
| [5.5 Hot shard](../scenarios/phase-5/5.5-hot-shard.md) | `MODE=broken` then `fixed` | 80% of traffic from Bengaluru → 9× load on one shard |
| [5.7 Cross-shard move](../scenarios/phase-5/5.7-cross-shard-move.md) | `MODE=broken` then `fixed` | no transaction across shards: 61 orders on both; idempotent steps fix it |
| [5.8 Migrating N shards](../scenarios/phase-5/5.8-migrating-shards.md) | hands-on with curl | one bad row leaves shards on different schema versions |

**Wrap-up (10 min):** When would you shard, and when would you buy a bigger box or add a replica? What's the first query you'd lose?

---
## Session 8 — Notifications: from a sync call to Kafka

**~2.5 h** · **Goal:** decouple work from the request, and learn what each kind of queue gives and costs.

**Set up** (before people arrive):

```bash
make up PHASE=6                # Kafka, Kafka UI, notification-service ×2
make ps
```

**Keep open:** [Kafka & notifications](http://localhost:3000/d/quickdrop-kafka), [Overview](http://localhost:3000/d/quickdrop-overview), Kafka UI http://localhost:8085

**Opening story (2 min):** SMS sending moved into its own service. Now an order is a chain of services, and every link can be slow, down, or rate-limited.

#### ⭐ 6.1 — The synchronous chain · [runbook](../scenarios/phase-6/6.1-sync-service-chain.md)

- **Ask the room first:** core waits for notification-service, which waits for a 2 s vendor. What does a customer feel?
- **Run:**

  ```bash
  make load ID=6.1
  ```

- **Show:** Overview → *p99 latency*; Service saturation → *Busy threads vs max*
- **Expect:** order p50 2.9 s; browsing and health checks ~0.9 s.
- **Land it:** in a synchronous chain latencies add up and capacity is the smallest link.

#### ⭐ 6.2 — Timeouts, breaker, bulkhead · [runbook](../scenarios/phase-6/6.2-timeouts-breaker-bulkhead.md)

- **Ask the room first:** we add a 300 ms timeout. Did the SMS fail?
- **Run:**

  ```bash
  make load ID=6.2 MODE=broken
  make load ID=6.2 MODE=timeout
  make load ID=6.2 MODE=fixed     # timeout + circuit breaker + bulkhead
  ```

- **Show:** Overview → *SMS sent by mode and result*; Kafka & notifications → *Vendor: accepted vs rejected*
- **Expect:** timeout: order 309 ms, core counted 1,414 dropped, but the vendor received **all** of them; fixed: order p50 7 ms.
- **Land it:** a timeout means "unknown", not "failed". Protect the caller, then deal with the uncertainty.
- **Show the code:** [`notification/NotificationClient.java`](../services/core-service/src/main/java/dev/quickdrop/core/notification/NotificationClient.java)

#### ⭐ 6.5 — The blast through Kafka · [runbook](../scenarios/phase-6/6.5-kafka-backpressure.md)

- **Ask the room first:** marketing sends 60,000 promos at 3,000/s. What happens to checkout?
- **Run:**

  ```bash
  make load ID=6.5                # ~5 min: watch the lag drain
  ```

- **Show:** Kafka & notifications → *Consumer lag (committed offsets)*, *Where the backlog lives*; Overview → *Order placement latency*
- **Expect:** orders p95 **10 ms** throughout; lag peaks at ~55,000 and drains in ~200 s.
- **Land it:** a queue turns overload into lag. Lag can be waited out; dropped requests can't.

#### ⭐ 6.6 — The dual write and the outbox · [runbook](../scenarios/phase-6/6.6-dual-write.md)

- **Ask the room first:** commit the order, then publish the event. What if we crash in between?
- **Run:**

  ```bash
  make load ID=6.6 MODE=broken    # commit, then publish
  make load ID=6.6 MODE=in-tx     # publish, then commit
  make load ID=6.6 MODE=fixed     # transactional outbox
  ```

- **Show:** Kafka & notifications → *Outbox relay*; the reconciliation the script prints
- **Expect:** commit-then-publish: 26 orders without an SMS; publish-then-commit: 26 SMS for orders that don't exist; outbox: 0 and 0.
- **Land it:** there is no safe order for two writes to two systems. Write the event in the same transaction and relay it.
- **Show the code:** [`notification/Outbox.java`](../services/core-service/src/main/java/dev/quickdrop/core/notification/Outbox.java)

#### ⭐ 6.8 — An OTP stuck behind a promo blast · [runbook](../scenarios/phase-6/6.8-otp-priority.md)

- **Ask the room first:** a login OTP arrives during a 40,000-promo blast. When is it delivered?
- **Run:**

  ```bash
  make load ID=6.8 MODE=broken    # one shared topic
  make load ID=6.8 MODE=fixed     # priority topic + own consumer group
  ```

- **Show:** Kafka & notifications → *Consumer lag*, *Delivery delay by type (p95)*
- **Expect:** shared: **0 of ~120 OTPs** delivered in a minute; priority: all of them, p95 0.06 s.
- **Land it:** Kafka has no priorities. Separate lines for separate urgency.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [6.3 In-memory queue vs a crash](../scenarios/phase-6/6.3-memory-queue-crash.md) | `MODE=broken` then `fixed` | 306 SMS silently lost on a crash; a durable queue loses only the in-flight HTTP hop |
| [6.4 Postgres as the queue](../scenarios/phase-6/6.4-db-as-queue.md) | `MODE=broken` | fine at moderate volume; at production CPU ratio, the blast competes with checkout |
| [6.7 Vendor allows 100/s](../scenarios/phase-6/6.7-vendor-rate-limit.md) | `MODE=broken`, `local`, `fixed` | 19,400 × 429 lost; per-instance limits still over; a shared Redis bucket: 31 |

**Wrap-up (10 min):** For each message your systems send, which delivery guarantee does it need, and what happens on a duplicate?

---
## Session 9 — Kafka deep dive

**~2.5 h** · **Goal:** the practical Kafka knobs: partitions, keys, commits, poison messages, acks.

**Set up** (before people arrive):

```bash
make up PHASE=6
open http://localhost:8085      # Kafka UI: topics, groups, messages
```

**Keep open:** Kafka UI, [Kafka & notifications](http://localhost:3000/d/quickdrop-kafka). Most K scenarios print their result as a table in the terminal

**Opening story (2 min):** Kafka is a log, not a queue. Each demo changes one setting and shows what it really means.

#### ⭐ K1 — Partitions are the unit of parallelism · [runbook](../scenarios/kafka/K1-partitions.md)

- **Ask the room first:** six consumers, one partition. How many do work?
- **Run:**

  ```bash
  make load ID=K1
  ```

- **Show:** the terminal table; Kafka UI → Consumers
- **Expect:** 1 partition: 62 s for 30,000 messages (5 consumers idle); 6 partitions: **12 s**.
- **Land it:** max useful consumers = partitions. Choose the count with headroom up front.

#### ⭐ K2 — Ordering exists only within a partition · [runbook](../scenarios/kafka/K2-ordering.md)

- **Ask the room first:** events PLACED → ASSIGNED → DELIVERED with no key. In order?
- **Run:**

  ```bash
  make load ID=K2 MODE=broken    # no key
  make load ID=K2 MODE=fixed     # key = order id
  ```

- **Show:** the terminal table
- **Expect:** no key: **9,930 of 30,000** out of order; keyed: 0.
- **Land it:** the message key is the unit of ordering.

#### ⭐ K3 — At-most-once, at-least-once, idempotent · [runbook](../scenarios/kafka/K3-delivery-semantics.md)

- **Ask the room first:** a consumer crashes between processing and committing. Duplicate or lost?
- **Run:**

  ```bash
  make load ID=K3 MODE=broken    # process, then commit
  make load ID=K3 MODE=before    # commit, then process
  make load ID=K3 MODE=fixed     # process, commit, dedupe
  ```

- **Show:** the terminal table (vendor-side duplicate count)
- **Expect:** after: **551 duplicate SMS**; before: **559 lost**; dedupe: 0 and 0.
- **Land it:** pick at-least-once and make processing idempotent. "Exactly-once" stops at Kafka's edge.

#### ⭐ K4 — One message nobody can process · [runbook](../scenarios/kafka/K4-poison-pill.md)

- **Ask the room first:** one malformed message on a partition. What happens to the messages behind it?
- **Run:**

  ```bash
  make load ID=K4 MODE=broken    # retry forever
  make load ID=K4 MODE=skip
  make load ID=K4 MODE=fixed     # dead-letter topic
  ```

- **Show:** Kafka & notifications → *Lag per partition* (one line climbs)
- **Expect:** retry forever: one partition stuck, 42 orders without SMS; skip: the message is silently gone; DLT: nothing blocked, nothing lost.
- **Land it:** transient errors deserve retries, permanent ones a dead-letter topic, and someone must watch it.

#### ⭐ K6 — What does an acknowledged write promise? · [runbook](../scenarios/kafka/K6-acks-and-replication.md)

- **Ask the room first:** the producer got 400,000 success callbacks. Are all 400,000 messages safe?
- **Run:**

  ```bash
  make load ID=K6 MODE=broken    # acks=1   (starts its own 3-broker cluster, ~1.6 GB)
  make load ID=K6 MODE=fixed     # acks=all + min.insync.replicas=2
  make kafka-cluster-down
  ```

- **Show:** the terminal output (it kills brokers on purpose)
- **Expect:** acks=1: **59,860 acknowledged messages lost** when the leader died; acks=all: 0.
- **Land it:** `acks=all`, RF 3, `min.insync.replicas=2` for anything that matters, and accept that losing 2 brokers stops writes.

**If you have time**

| Demo | Run | Expect |
|---|---|---|
| [K5 A rebalance storm](../scenarios/kafka/K5-rebalance-storm.md) | `MODE=broken` then `fixed` | batches longer than `max.poll.interval.ms`: 18 rebalances, nothing commits |
| [K7 Replay](../scenarios/kafka/K7-replay.md) | `make load ID=K7` | reset offsets to earliest, rebuild a read model from the same messages |
| [K8 Batching and compression](../scenarios/kafka/K8-batching-compression.md) | `make load ID=K8` | no batching 67k msg/s and 532 ms queued; big batches 338k/s; zstd 3.4× fewer bytes |
| [K9 Fan-out](../scenarios/kafka/K9-fan-out.md) | `make load ID=K9` | one group down for 15 s, the other unaffected |
| [K10 Compaction](../scenarios/kafka/K10-compaction.md) | `make load ID=K10` (~2.5 min) | 200,000 rider updates compact to ~2,000; the active segment is never compacted |
| [K11 Transactions](../scenarios/kafka/K11-transactions.md) | `MODE=broken` then `fixed` | plain copier writes 1,507 duplicates; transactional: exactly 20,000 for read_committed |

**Wrap-up (10 min):** Pick one of your Kafka topics: partitions, key, acks, commit strategy, DLT. Which would you change?

---
## Session 10 — Operating it

**~3 h (including a 60 min game day)** · **Goal:** find problems fast: traces, alerts that page on the right thing, and the overload patterns that cause real outages.

**Set up** (before people arrive):

```bash
make up PHASE=8                # adds Tempo and Alertmanager; tracing on everywhere
make ps
```

**Keep open:** [Operating (Phase 8)](http://localhost:3000/d/quickdrop-operating), Grafana → Explore (Tempo and Loki), Alertmanager http://localhost:9093

**Opening story (2 min):** Everything we broke so far, we broke on purpose and knew where to look. In production you don't know, and the clock is running.

#### ⭐ 8.1 — Where did the time go? Tracing · [runbook](../scenarios/phase-8/8.1-tracing.md)

- **Ask the room first:** a customer waited 5 s for an order SMS. Which service was slow?
- **Run:**

  ```bash
  make load ID=8.1 MODE=broken    # trace context dropped at the outbox
  make load ID=8.1 MODE=fixed     # traceparent stored with the outbox row
  ```

- **Show:** Grafana → Explore → Tempo: `{ rootName = "http post /api/orders" }`; click the slow trace the script prints
- **Expect:** broken: 901 orders, **0** traces reach the vendor (901 orphans); fixed: **901 of 901** connected. The slowest: 8.5 ms HTTP, 2.95 s waiting in Kafka, 2 s vendor.
- **Land it:** traces break at every async boundary unless you carry the context. Most of a slow request is often waiting, not working.

#### ⭐ 8.2 — Which alerts should page? · [runbook](../scenarios/phase-8/8.2-alerts.md)

- **Ask the room first:** notification-1 runs at 90% CPU, customers are fine. Should someone wake up?
- **Run:**

  ```bash
  make load ID=8.2                # ~8 min: a busy machine, then a bad deploy on core-2
  ```

- **Show:** Operating → *Alerts firing*, *Checkout error ratio vs the SLO*, *Error budget burn rate*
- **Expect:** HighCpu fires while checkout p99 stays at 17 ms; the bad deploy pages after **74 s** via the burn-rate alert and resolves 74 s after the fix.
- **Land it:** page on symptoms measured against an SLO; causes belong on dashboards.

#### ⭐ 8.3 — A retry storm · [runbook](../scenarios/phase-8/8.3-retry-storm.md)

- **Ask the room first:** the vendor hiccups for 10 s. When does the system recover?
- **Run:**

  ```bash
  make load ID=8.3 MODE=none      # no retries
  make load ID=8.3 MODE=broken    # 3 immediate retries at 2 layers
  make load ID=8.3 MODE=fixed     # 1 layer, backoff + jitter, retry budget
  ```

- **Show:** the printed timeline; Operating → *Retry amplification*, *SMS vendor: work vs queue*
- **Expect:** broken: **never**, 0 SMS/s until the load stops and 16,985 duplicate OTPs; fixed: recovered 15 s after the hiccup.
- **Land it:** retries multiply across layers. Retry at one layer, with backoff, jitter and a budget.
- **Show the code:** [`resilience/Retrier.java`](../libs/quickdrop-common/src/main/java/dev/quickdrop/common/resilience/Retrier.java)

#### ⭐ 8.4 — Load shedding in a flash sale · [runbook](../scenarios/phase-8/8.4-load-shedding.md)

- **Ask the room first:** browsing surges 7×. Should checkout suffer?
- **Run:**

  ```bash
  make load ID=8.4 MODE=broken
  make load ID=8.4 MODE=fixed     # priority shedding
  ```

- **Show:** Operating → *Load shedding*, *Checkout vs browsing success*
- **Expect:** off: **41.7%** of checkouts succeed (30 s timeouts); priority shedding: **100%**, p50 138 ms, browsing gets fast 503s.
- **Land it:** decide what to drop before overload decides for you, and drop it fast.
- **Show the code:** [`web/LoadShedder.java`](../services/core-service/src/main/java/dev/quickdrop/core/web/LoadShedder.java)

#### ⭐ 8.5 — Game day (60 min) · [runbook](../scenarios/phase-8/8.5-game-day.md)

Split into pairs: one breaks, one diagnoses. Rules and the 10 faults are in the runbook.

```bash
make load ID=8.5          # terminal 1: 15 minutes of healthy traffic
make gameday              # terminal 2, after ~1 minute: one hidden fault
make gameday-reveal       # when the diagnosing person has an answer
make gameday-end          # undo it; swap roles, run again
```

Rules: only Grafana, Explore, Alertmanager and `curl localhost:8091/api/alerts`. No `make toggles`, no `docker ps`. Write down impact → where → cause → evidence → mitigation. Finish with a 10-minute blameless postmortem per pair.

**Wrap-up (10 min):** Read [docs/FAILURE_TRACKER.md](FAILURE_TRACKER.md) together: what a laptop can't reproduce, and the real bugs we hit while building this repo (a deadlock from virtual threads, monitoring that silently went blank, a test broken for three phases).

---
## After the course

- **Take-home:** each person picks one runbook, reruns it with a different knob (`RATE`, `DURATION`, `RACE_WINDOW`,
  `SLOW_PERCENT`; the list is at the top of each load script) and explains what changed.
- **Make it yours:** add a scenario from your own production history: a toggle with a broken and a fixed strategy, a
  load script in `loadtest/k6/scenarios/`, a runbook in `scenarios/`. The structure is described in the
  [README](../README.md).
- **Clean up:** `make down` keeps the data; `make clean` deletes everything.
