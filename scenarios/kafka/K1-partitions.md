# K1 — Partitions are the unit of parallelism

> **Kafka deep dive.** You can't speed up a consumer group by adding consumers. Only by adding
> partitions (up to the point where something else is the bottleneck).

## Run it

```bash
make up PHASE=6
make load ID=K1
```

30,000 messages go to `lab-1p` (1 partition) and 30,000 to `lab-6p` (6 partitions). Each topic gets its own
consumer group of **6 consumers**; processing a message takes 2 ms.

| Topic | Consumers that got work | Time for 30,000 | Throughput |
|---|---|---|---|
| `lab-1p` (1 partition) | **1 of 6** | 62.2 s | ~483/s |
| `lab-6p` (6 partitions) | 6 of 6 | **12.1 s** | **~2,490/s** |

The script prints `partitions per consumer` while both groups are busy. On `lab-1p` one consumer owns `[0]` and
the other five own `[]`: they're connected, heartbeating and doing nothing.

## Why

Within a consumer group, **each partition is read by exactly one consumer**. That's how Kafka keeps per-partition
order (K2) without locks: one reader per partition, in offset order.

```
 lab-1p:  [partition 0] ──▶ consumer 0          consumers 1–5: no partition, idle

 lab-6p:  [p0] ──▶ c0   [p1] ──▶ c1   [p2] ──▶ c2   [p3] ──▶ c3   [p4] ──▶ c4   [p5] ──▶ c5
```

So:

- **Max useful consumers = partitions.** A 7th consumer on `lab-6p` would be idle too.
- **Throughput per partition is capped by one consumer's speed.** 2 ms per message ≈ 500/s per partition. With
  6 partitions you get ≈ 3,000/s at best (we measured 2,490/s; the rest is fetch and commit overhead).
- **Partitions are cheap to add and impossible to remove.** And adding them changes which partition a key maps
  to (`hash(key) % partitions`): for a while, events for the same order land in two partitions and can be
  processed out of order. Pick a count with headroom up front (common: 12–48 for a busy topic, never 1).

## Observe

```bash
docker exec qd-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group from the output>
```

`CONSUMER-ID` is empty for partitions nobody owns. On `lab-1p` you'll see one row. **Kafka UI** (http://localhost:8085)
→ Consumers shows the same thing.

## What partitions don't fix

- **A slow downstream.** 6.7: the vendor allows 100/s whatever your partition count is.
- **One hot key.** All events for one key go to one partition. If one store produces half the traffic, its
  partition is the bottleneck and the other consumers idle.
- **Too many partitions** cost broker memory and file handles, and make rebalances slower (K5). Tens to hundreds per
  topic is normal. Tens of thousands per cluster needs planning.

## Discussion

1. notification-service runs 2 instances × 3 consumers on a 6-partition topic. What happens if you scale it to 4 instances?
2. Why can't Kafka let two consumers in the same group share one partition?
3. You need per-order ordering and 20,000 messages/s at 2 ms each. How many partitions?
