# K2 — Ordering exists only within a partition

> **Kafka deep dive.** "Kafka is ordered" is true per partition and false per topic. The message key
> decides which messages share a partition.

## Run it

```bash
make load ID=K2 MODE=broken    # no key
make load ID=K2 MODE=fixed     # key = order id
```

10,000 orders × 3 lifecycle events each (`PLACED → RIDER_ASSIGNED → DELIVERED`), produced in order, on a
6-partition topic read by 6 consumers. A consumer counts an event as **out of order** if it sees an earlier event
of an order *after* a later one (say `RIDER_ASSIGNED` after `DELIVERED`).

| Key | Events processed | Processed out of order |
|---|---|---|
| none | 30,000 | **9,930** (one in three) |
| order id | 30,000 | **0** |

## Why

The producer picks the partition:

- **With a key**: `partition = murmur2(key) % partitions`. Same key → same partition, every time.
- **Without a key**: anywhere (round-robin or "sticky" batches). Our lab picks a random partition, like a
  service that forgot to set a key.

Each partition is a separate log, read by a separate consumer at its own speed:

```
 order 42, no key:     PLACED → p1 (consumer 1, busy)    RIDER_ASSIGNED → p4 (consumer 4, idle)    DELIVERED → p2
                       consumer 4 and 2 finish first: the app sees "delivered" before "placed"

 order 42, key=42:     PLACED, RIDER_ASSIGNED, DELIVERED → all p3, read by one consumer in offset order
```

## Choosing the key

The key is the **unit of ordering**: pick the entity whose events must stay in order.

| Topic | Key | Why |
|---|---|---|
| `notifications` (core) | customer phone number | a customer's SMS arrive in the order they were sent |
| order lifecycle | order id | a status can't go backwards |
| `rider-status` (K10) | rider id | the latest location wins; compaction keeps one per key |

Too coarse a key (say, city id) gives you a **hot partition**: all of Bengaluru on one consumer. Too fine
(a random UUID) gives you no ordering at all. And remember K1: adding partitions remaps keys.

## Ordering can still break with a key

- **Producer retries** reorder messages if `max.in.flight.requests.per.connection > 1` *and* idempotence is off:
  batch 1 fails, batch 2 succeeds, batch 1's retry lands after it. `enable.idempotence=true` (the default since
  Kafka 3.0) prevents this. Our lab producer keeps it on.
- **Processing in parallel inside a consumer** (a thread pool per partition) throws the order away again.
- **Rebalances** (K5) move a partition to a new consumer that resumes from the last commit: events get
  *re-processed*, still in order, but twice.
- **Two topics** have no ordering between them. In 6.8, OTPs and order SMS go to different topics on purpose.

## Discussion

1. A consumer stores "latest order status" in Postgres. With no key, what does the customer see?
2. Why might you key rider location updates by rider id rather than order id?
3. You change the key of an existing topic from customer id to order id. What happens to in-flight ordering during the switch?
