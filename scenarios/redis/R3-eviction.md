# R3 — Memory full: what does Redis throw away?

> **Redis deep dive.** `maxmemory-policy` decides which of your keys disappear, and coordination data
> (locks, rate limits) is often what goes first.

## Story

Redis is capped at 64 MB. A batch job filled it with cache junk (450,000 keys, **no TTL**). Our app
keeps running: a bot hammers the OTP endpoint (limit 5/min, the sliding-window limiter from 3.5) while
customers browse products through the Redis cache.

## Run it

```bash
make load ID=R3 MODE=broken       # allkeys-lru
make load ID=R3 MODE=volatile     # volatile-lru
make load ID=R3 MODE=noeviction   # noeviction
make load ID=R3 MODE=fixed        # noeviction and no junk
```

| Policy | Bot's OTPs accepted in 60 s (limit 5/min) | What else happened |
|---|---|---|
| `allkeys-lru` | **6** | Evicted ~30,000 keys: mostly junk and product cache entries |
| `volatile-lru` | **1,170**: no limit at all | Thousands of writes rejected with OOM once nothing evictable was left |
| `noeviction` | 5 | ~12,000 product-cache writes rejected: `OOM command not allowed`, product pages failed with 503 |
| `noeviction`, no junk | 5 | nothing |

## What the policies do

When memory is full and a write arrives:

| Policy | Evicts |
|---|---|
| `noeviction` | nothing: the write fails with an `OOM` error |
| `allkeys-lru` / `allkeys-lfu` | least recently / least frequently used key, any key |
| `volatile-lru` / `volatile-lfu` / `volatile-ttl` | only keys **with a TTL** |
| `allkeys-random` / `volatile-random` | a random key |

## Why each result

**`allkeys-lru` (6).** The rate limiter's key for the bot's number was used every 50 ms, so it was the
*most* recently used key, and LRU kept it. The junk and cold cache entries went first. The one extra OTP
is when the limiter's key did get evicted once. LRU protects hot coordination keys, but only
statistically: a lock that's held quietly for a few seconds is cold and evictable.

**`volatile-lru` (1,170).** This policy only evicts keys with a TTL. The junk has none. **Every key our
app writes has one**: rate limits, locks, cache entries. So Redis evicted the rate limiter's state as
fast as it was written, and the bot had no limit at all. Any app that "expires everything, like a good
citizen" sharing Redis with something that doesn't is in this position.

**`noeviction` (5, but product pages failing).** Nothing is ever silently lost; writes fail loudly
instead. The product cache's `SET` got `OOM command not allowed`, so product pages returned 503
(`redis-failure-mode=fail`). The rate limiter **kept working**, and the reason is subtle:

```bash
# memory full, noeviction:
EVAL "redis.call('ZADD', KEYS[1], 1, 'x')" 1 k                                        # → OOM
EVAL "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, 0); redis.call('ZADD', KEYS[1], 1, 'x')" 1 k   # → ok
```

Redis checks for OOM on the **first** write of a script and lets later writes through, because
aborting a script halfway would leave it half-applied. Our sliding-window script starts with
`ZREMRANGEBYSCORE`, so its `ZADD` passed. The fixed-window limiter (`INCR` first) and the lock script
(`SET NX` first) would have been refused. That's luck, not design. Don't rely on it.

## What to do

1. **Separate cache and coordination data.** Put locks, rate limits and queues on a Redis with
   `noeviction` and room to spare. Caches go on another one with `allkeys-lru` (or `allkeys-lfu`).
   The two need opposite eviction policies.
2. **Alert before it's full**: `redis_memory_used_bytes / redis_memory_max_bytes > 0.8`, and on
   `redis_evicted_keys_total` rising.
3. **Everything cached gets a TTL**, so a cache flushes itself even under `noeviction`.
4. Know how your code behaves on an OOM error: fail open, fail closed, or fall back (3.9).

## Discussion

1. Why is `volatile-lru` worse than `allkeys-lru` in this experiment, even though it sounds safer?
2. A Redis lock (3.2) is evicted while held. What happens next?
3. Which policy would you choose for a Redis that holds only a product cache? Only rate limits?
