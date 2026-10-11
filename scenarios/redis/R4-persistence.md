# R4 — Persistence: what survives `kill -9`?

> **Redis deep dive.** Redis is in-memory first. What you get back after a crash depends on settings
> most people never look at.

This one is hands-on: copy the commands. (Phase 3 must be running.)

## Setup: create some state

```bash
make toggle NAME=rate-limiter VALUE=redis-fixed
make toggle NAME=rate-limit-otp-per-minute VALUE=3
curl -X POST localhost:8081/admin/riders/geo-sync                                    # 20,000 riders in GEO sets
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8080/api/auth/otp \
  -H 'Content-Type: application/json' -d '{"phone":"+919999000444"}'; done            # 202 202 202 429

docker exec qd-redis redis-cli CONFIG GET save           # "60 1000": snapshot if ≥1000 changes in 60 s
docker exec qd-redis redis-cli CONFIG GET appendonly     # "no"
docker exec qd-redis redis-cli INFO persistence | grep -E 'rdb_changes_since_last_save|rdb_last_save_time'
```

## Experiment 1: RDB snapshots only (the default here)

```bash
docker kill --signal=KILL qd-redis && docker start qd-redis
```

What we saw:

- **The rate-limit counter was gone.** The bot's next OTP was accepted. Every limit reset at once.
- **Deleted data came back.** 450,000 junk keys from R3, deleted 40 seconds before the crash, were in
  Redis again. The last snapshot had been taken *before* the delete, and everything after it was lost,
  **deletes included**.
- **The GEO sets went back in time**: positions and availability from the snapshot, not from now.
  A rider assigned since then appears free.

An RDB snapshot is a point-in-time copy (`fork()` and write the whole dataset). A crash loses
everything since the last one: up to 60 s here, much more under a light write load.

## Experiment 2: "turn on AOF" (the wrong way)

```bash
docker exec qd-redis redis-cli CONFIG SET appendonly yes      # OK
docker kill --signal=KILL qd-redis && docker start qd-redis
docker exec qd-redis redis-cli CONFIG GET appendonly          # "no" (!)
```

`CONFIG SET` changes the **running** server. The container restarted with `--appendonly no` on its
command line, so AOF was off again and the old snapshot was loaded. `CONFIG REWRITE` persists runtime
changes when Redis has a config file. Ours is configured on the command line in
`docker-compose.yml`, the usual container setup. Runtime config changes quietly undone by a restart
are a classic incident cause.

## Experiment 3: AOF enabled at startup

```bash
REDIS_APPENDONLY=yes make up PHASE=3
docker exec qd-redis redis-cli CONFIG GET appendonly          # "yes"
# create state again (OTPs as above), then:
docker kill --signal=KILL qd-redis && docker start qd-redis
```

Redis replays the append-only log of every write (`DB loaded from append only file`):

- The rate-limit counter survived (`GET rl:fixed:otp:…` → `4`).
- A key written **milliseconds before** `kill -9` survived too.

That last point is worth understanding. With `appendfsync everysec`, Redis `write()`s each command to
the AOF file before answering, and `fsync()`s once a second. `kill -9` kills the **process**: anything
already handed to the kernel is safe. Only a **machine** crash or power loss loses the last second.
(`appendfsync always` fsyncs every write: no loss, much slower.)

> Right after Redis restarts, the app sees a few `503 redis_unavailable` while Lettuce reconnects
> (`Connection closed prematurely`). Restarts are never invisible.

## Which to choose

| Data | Lose 60 s of it? | Setting |
|---|---|---|
| Cache | sure, it's a cache | RDB only, or no persistence at all |
| Rate limits | a reset is tolerable (bots get a free window) | RDB is OK |
| Locks | an empty lock table after restart = two holders possible (3.2!) | fencing in the DB, whatever Redis does |
| Rider positions / availability | stale data is *worse* than none | rebuild from Postgres on restart (`geo-sync`) |
| Anything you can't rebuild | no | AOF `everysec` or `always`, plus replicas, **or don't keep it only in Redis** |

Persistence is not replication: a crashed **machine** still takes the data with it if the disk goes too.
Replicas protect against that, but replication is asynchronous, so a failover can lose the last writes,
including a freshly granted lock (FAILURE_TRACKER: Redis failover).

## Discussion

1. Why is "deleted data came back" more dangerous than "new data was lost"?
2. After a Redis restart, the GEO sets are an hour old. What would our app do with them? What should
   happen on startup?
3. `appendfsync everysec` vs `always`: which failures does each protect against?
