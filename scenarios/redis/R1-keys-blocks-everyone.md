# R1 — One `KEYS` command blocks every client

> **Redis deep dive.** The one fact about Redis that explains half its failure modes: it runs
> commands **one at a time, on a single thread**.

## Why single-threaded is a feature

Every Redis command executes alone. No other command runs in the middle of it. That's why `INCR` is
atomic, why a Lua script is atomic (3.2, 3.5), and why Redis needs no locks internally. Most commands
take microseconds, so one thread handles hundreds of thousands per second (R5).

The catch: **a slow command makes everyone wait.** There's no preemption and no "let the small
commands through".

## Run it

```bash
make up PHASE=3
make load ID=R1 MODE=broken    # an admin "debug" endpoint runs KEYS three times, 10 s apart
make load ID=R1 MODE=fixed     # same search with SCAN … COUNT 1000
```

Setup loads **1,000,000 junk keys** (`DEBUG POPULATE`, ~143 MB). Background traffic is 300 product
reads/s through the Redis product cache.

| | KEYS | SCAN |
|---|---|---|
| Each search took | ~55 ms (one command) | ~200 ms (in ~1,000 small steps) |
| Product reads p99 | **177 ms** | **1 ms** |
| Product reads max | 1,001 ms | 27 ms |

SCAN is *slower* in total, and that's fine: between its steps, every other client's commands run.
KEYS is faster in total, and for those 55 ms Redis serves nobody else. With 10 million keys it would
be over half a second, several times a minute.

## Observe

```bash
docker exec -it qd-redis redis-cli
SLOWLOG GET 5          # commands slower than 10 ms (slowlog-log-slower-than in docker-compose.yml)
LATENCY LATEST         # latency spikes above 50 ms (latency-monitor-threshold)
INFO commandstats      # calls and total time per command
```

**Redis & scale-out → Average time per command** shows `keys` at tens of milliseconds next to
`get` at microseconds.

## The usual suspects

| Blocking command | Use instead |
|---|---|
| `KEYS pattern` | `SCAN 0 MATCH pattern COUNT 1000`, or keep your own index (a set of the keys you care about) |
| `HGETALL` / `SMEMBERS` / `LRANGE 0 -1` on a huge collection | `HSCAN` / `SSCAN`, or smaller collections (R2) |
| `DEL` of a huge key | `UNLINK` (R2) |
| `FLUSHALL` / `FLUSHDB` | `FLUSHALL ASYNC` |
| Long Lua scripts | Keep scripts short; they block like any command |
| `SORT`, `SUNION` on large sets | Precompute, or do it in the app |

Many teams disable the dangerous ones in production with ACLs (`ACL SETUSER app -keys -flushall …`)
or `rename-command` in the config.

## Discussion

1. Why did requests on core-2 and core-3 slow down too, when only core-1 ran `KEYS`?
2. SCAN may return a key twice, or miss keys added during the scan. When does that matter?
3. A Lua script that loops over 100,000 items: is it "one command"? What does that mean for everyone else?
