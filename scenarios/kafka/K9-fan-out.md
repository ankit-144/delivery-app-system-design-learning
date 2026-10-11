# K9 — One topic, many independent readers

> **Kafka deep dive.** Each consumer group has its own offsets. Adding a reader costs the others
> nothing, and a reader that falls behind or goes down doesn't hold anyone else back.

## Run it

```bash
make load ID=K9
```

18,000 order events feed two teams: **analytics** (fast) and **rider incentives** (5 ms per event). Each is its own
consumer group. Five seconds in, the incentives service goes down for 15 s, then comes back.

| | analytics | incentives |
|---|---|---|
| before the outage | done (18,000) | 3,000 processed |
| during the 15 s outage: lag | **0** | **15,000**, flat |
| after it comes back | — | resumes from **its own** committed offsets: 3,000 + 15,000 = 18,000, nothing lost, nothing redone |

## Why

```
                               ┌──▶ group "analytics"    offsets: p0=3000 p1=3000 …   (caught up)
 topic (lab-6p in the lab) ────┤
 (the log, stored once)        └──▶ group "incentives"   offsets: p0=500  p1=500  …   (down; its lag waits)
```

The log is stored once; each group is just a set of numbers in `__consumer_offsets`. Compare with the other
"queues" in this repo:

| | Kafka | Postgres queue (6.4) | in-memory queue (6.3) |
|---|---|---|---|
| A second reader | new group id, reads everything | add a column per reader, or copy rows | — |
| Reader down for an hour | lag grows, nothing lost | rows pile up | **lost** |
| Readers affect each other | no (separate offsets, same disk) | yes (same table, locks, vacuum) | yes |

That's why companies put "business events" (order placed, rider assigned, payment captured) on Kafka: search
indexing, analytics, fraud, notifications and incentives each read the same topic at their own pace, and a new team
can start tomorrow by reading from the beginning (K7).

## The limits

- **Shared broker resources.** A group replaying 7 days of history reads from disk, not page cache. That can slow
  the brokers for everyone. Quotas (`consumer_byte_rate`) cap a client.
- **Schema is now a contract.** Five teams parse your event. Renaming a field breaks them. Use a schema registry
  and compatibility rules (Avro/Protobuf), or at least additive-only changes.
- **Retention is global for the topic.** A team that's down for longer than retention loses data.

## Discussion

1. The incentives team wants to recompute last week's payouts. What do they need, and does it affect analytics?
2. Why is "add a reader" cheap in Kafka but expensive in the Postgres queue from 6.4?
3. You rename `riderId` to `rider_id` in the order event. Who breaks, and how would you roll it out safely?
