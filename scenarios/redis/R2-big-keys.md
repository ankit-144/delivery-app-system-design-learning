# R2 — Big keys

> **Redis deep dive.** One key holding a million things is one command away from a stall.

## Story

Someone stores "last order per customer" in **one hash** with a million fields. It's convenient: one
key, `HGET` by customer. Then a reporting job reads the whole thing, and later someone cleans it up.

## Run it

```bash
make load ID=R2 MODE=broken    # HGETALL the hash, then DEL it
make load ID=R2 MODE=fixed     # don't read it whole; UNLINK it
```

Background: 300 product reads/s through the Redis product cache.

| Operation | Took | Effect on everyone else |
|---|---|---|
| `HSET` a million fields (in batches of 10,000) | 0.7 s | small stalls per batch |
| `HGETALL lab:big-hash` | **1.3 s** round trip | Redis serialises a million fields in one go, then pushes ~60 MB over the network |
| `DEL lab:big-hash` | **140 ms** | Redis frees a million allocations, blocking the whole time |
| `UNLINK lab:big-hash` | **0 ms** | the key disappears instantly; memory is freed on a background thread |
| Product reads max latency | broken **645 ms**, fixed **7 ms** | |

## Find big keys

```bash
docker exec qd-redis redis-cli --bigkeys          # samples the keyspace with SCAN; safe to run in production
docker exec qd-redis redis-cli --memkeys          # same, by memory usage
docker exec qd-redis redis-cli MEMORY USAGE lab:big-hash
```

## Fixes

- **Shard the big key**: `last-order:{customer_id % 1024}` gives 1,024 small hashes instead of one
  huge one. Each command touches a small one.
- **Read in pages**: `HSCAN lab:big-hash 0 COUNT 1000`.
- **Delete without blocking**: `UNLINK` (or `lazyfree-lazy-user-del yes`, which makes `DEL` behave like `UNLINK`).
  Also check `lazyfree-lazy-expire`: a big key *expiring* is freed on the main thread unless that's on.
- **Watch value size, too.** A single 10 MB string blocks the network and the client while it's sent,
  however simple the command.

Big keys also break **Redis Cluster**: one key always lives on one shard, so a hot or huge key
can't be spread out (see the hot partition entry in FAILURE_TRACKER).

## Discussion

1. Why does `DEL` take time at all? What is Redis doing for those 140 ms?
2. Our rider GEO set has 20,000 members. Is that "big"? What would make it a problem?
3. How would you migrate the million-field hash to 1,024 small ones without downtime?
