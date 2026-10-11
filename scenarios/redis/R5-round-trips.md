# R5 — Round trips, pipelining and Lua

> **Redis deep dive.** Redis is fast. The network between you and Redis usually isn't.

Hands-on with `redis-benchmark` (inside the Redis container). Phase 3 must be running.

## Experiment: one command at a time vs pipelined

```bash
B="docker exec qd-redis redis-benchmark -q"
$B -h localhost -n 100000 -c 1 -t set -P 1      # one client, one command at a time
$B -h localhost -n 300000 -c 1 -t set -P 32     # one client, 32 commands per round trip
```

Now add 1 ms of network latency per reply (a Redis in another availability zone) with Toxiproxy, and
benchmark *through* it:

```bash
curl -X POST localhost:8474/proxies/redis/toxics -H 'Content-Type: application/json' \
  -d '{"name":"r5","type":"latency","stream":"downstream","attributes":{"latency":1}}'
$B -h toxiproxy -p 16379 -n 2000  -c 1  -t set -P 1
$B -h toxiproxy -p 16379 -n 50000 -c 1  -t set -P 32
$B -h toxiproxy -p 16379 -n 20000 -c 50 -t set -P 1
curl -X DELETE localhost:8474/proxies/redis/toxics/r5
```

What we measured:

| Setup | SETs/s | Time per command |
|---|---|---|
| Same host, 1 connection, unpipelined | 146,628 | 0.007 ms |
| Same host, 1 connection, pipelined ×32 | **1,630,435** | |
| **+1 ms latency**, 1 connection, unpipelined | **926** | 1.08 ms |
| +1 ms, 1 connection, pipelined ×32 | 29,198 | |
| +1 ms, 50 connections, unpipelined | 38,610 | |

**The same Redis went from 146,000 to 926 operations per second** because of one millisecond. Redis
spends microseconds on a `SET`; the client spends a millisecond waiting for the reply before sending
the next one. Throughput per connection is `1 / round-trip time`. (Same arithmetic as 2.5's N+1, and
Little's law from 0.1.)

## Three ways to pay fewer round trips

**1. Multi-key commands.** Our product cache reads many products with one `MGET`, not one `GET` per
product (`ProductCache.fromRedis`). Same for `HMGET`, `MSET`, and `GEOADD` with many members (`geo-sync`).

**2. Pipelining.** Send N commands without waiting, then read N replies. Not atomic (other clients'
commands can run in between), just fewer waits. In Spring: `redisTemplate.executePipelined(...)`.

**3. Lua scripts.** Run logic *inside* Redis: one round trip, and **atomic**. Nothing else runs while a
script runs (R1). Every check-then-act we do in Redis is a script for this reason:

- lock acquire: `SET NX PX` + `INCR fence` (3.2)
- lock release: `GET` == token → `DEL` (3.2)
- rate limiters: read, decide, update (3.5)

Without a script, "read the counter, compare, increment" is three round trips, and another
instance can act between them, which is 1.1's race condition in Redis.

### What about `MULTI`/`EXEC`?

A Redis transaction queues commands and runs them together, atomically. But you can't **read**
inside it and decide what to write based on the result, because nothing executes until `EXEC`. The
`WATCH` + `MULTI` pattern adds optimistic locking (1.5 again: retries on contention). Lua is usually
simpler.

## How our app talks to Redis

Spring Data Redis uses **Lettuce**, which multiplexes all commands from all threads over **one shared
connection** per instance, pipelining them automatically. That's very efficient, but one slow command
or one huge reply (R1, R2) delays every thread in the instance. (Blocking commands like `BLPOP` get a
dedicated connection.)

## Discussion

1. Why did 50 connections help (926 → 38,610/s) without pipelining?
2. Our sliding-window limiter does 4 Redis commands per request. How many round trips does it cost?
   What if it weren't a script?
3. Pipelining isn't atomic. Name a case in this codebase where that would be a bug.
