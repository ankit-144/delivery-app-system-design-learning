# R6 — Pub/sub vs Streams

> **Redis deep dive.** Two ways to send messages through Redis. One forgets, one remembers. This is
> the bridge to Kafka (Phase 6).

Hands-on with `redis-cli`. Phase 3 must be running.

## Pub/sub: fire and forget

The two-tier product cache (3.3) uses pub/sub to tell every instance "product 7 changed".

```bash
# terminal 1
docker exec -it qd-redis redis-cli SUBSCRIBE price-changes

# terminal 2
docker exec qd-redis redis-cli PUBLISH price-changes "product 7: 25052 -> 30052"   # → (integer) 1   one receiver
# now stop terminal 1 (Ctrl-C) and publish again:
docker exec qd-redis redis-cli PUBLISH price-changes "product 8: 1000 -> 900"      # → (integer) 0   nobody got it
```

`PUBLISH` returns how many subscribers received the message: 1, then **0**. Nobody stores the second
message. A subscriber that's restarting, or whose connection blipped for a second, **misses it
forever**.

For cache invalidation that's acceptable, because the TTL is the safety net: a missed invalidation means
"stale until the TTL expires", not "stale forever". For anything that must be processed (send this SMS,
charge this order), it's not.

## Streams: a log with consumer groups

A stream is an append-only log stored in Redis. Consumers read from it and **acknowledge** what
they've processed.

```bash
R="docker exec qd-redis redis-cli"
$R XADD order-events '*' type ORDER_PLACED order 101           # → 1791676431831-0  (an ID: time-sequence)
$R XADD order-events '*' type ORDER_PLACED order 102
$R XGROUP CREATE order-events sms-sender 0                       # a consumer group that starts from the beginning

# consumer c1 reads two messages… and crashes before acknowledging them
$R XREADGROUP GROUP sms-sender c1 COUNT 2 STREAMS order-events '>'
$R XPENDING order-events sms-sender
# → 2 1791676431831-0 1791676431853-0 c1 2     (2 messages delivered to c1, never acknowledged)

# consumer c2 takes over messages that have been pending too long (here: any age)
$R XAUTOCLAIM order-events sms-sender c2 0 0 COUNT 10
# → both messages, now owned by c2

$R XACK order-events sms-sender 1791676431831-0 1791676431853-0   # → 2
$R XPENDING order-events sms-sender                               # → 0
```

What a stream gives you that pub/sub doesn't:

| | Pub/sub | Streams |
|---|---|---|
| Message stored? | no | yes, until trimmed (`MAXLEN`, `MINID`) |
| Consumer offline | misses messages | reads them when it comes back |
| Consumer crashes mid-work | message lost | stays **pending**; another consumer can claim it |
| Several workers share the load | every subscriber gets every message | a **consumer group** splits messages among its consumers |
| Several independent readers | — | several groups, each with its own position |
| Replay old messages | impossible | `XRANGE`, or a new group starting at `0` |

**At-least-once delivery.** c2 might send the SMS for order 101 even though c1 already sent it just before
crashing, without acknowledging. Every reliable messaging system has this property, and consumers must be
**idempotent** (Phase 7, K3).

## Why not stop here?

Redis Streams are a good fit for moderate volumes inside one system. Phase 6 moves to **Kafka**, which
takes the same ideas (an append-only log, consumer groups, offsets instead of acks) and adds:
partitioning across many machines, retention measured in days or terabytes rather than RAM,
replication with configurable durability (K6), and an ecosystem (connectors, stream processing). The
mental model you just built carries over directly.

## Discussion

1. Why is pub/sub good enough for cache invalidation but not for sending SMS?
2. c1 crashed after sending the SMS but before `XACK`. What does the customer experience, and how
   would you prevent it?
3. A stream grows forever unless trimmed. Where does that memory come from, and what happens at
   `maxmemory` (R3)?
