# K5 — A rebalance storm

> **Kafka deep dive.** A consumer that takes too long between two `poll()` calls is declared dead,
> even while it's working as hard as it can.

## Run it

```bash
make load ID=K5 MODE=broken    # max.poll.records=500
make load ID=K5 MODE=fixed     # max.poll.records=50
```

20,000 messages, 3 consumers, 20 ms of work per message, `max.poll.interval.ms=5000`. Only the batch size
differs.

| `max.poll.records` | Time per batch | Unique messages in 60 s | Processed more than once | Rebalances | Commits rejected |
|---|---|---|---|---|---|
| 500 | **10 s** | **1,500** (~25/s) | **7,500** | 18 | **18** |
| 50 | 1 s | **9,000** (~150/s, the maximum) | 0 | 3 (startup) | 0 |

In the broken run **not a single commit succeeded**. Every consumer processed its first 500 records, got kicked
out, failed to commit, rejoined, was handed the same records again, and repeated. The consumers were at 100% the
whole minute and made almost no progress. Lag never moved.

## Why

Two separate liveness checks keep a consumer in its group:

| Setting | Checked by | Means |
|---|---|---|
| `session.timeout.ms` (10 s here) | heartbeats from a **background thread** | the process is alive |
| `max.poll.interval.ms` (5 s here; default 5 min) | the time between two `poll()` calls | the consumer is making progress |

The heartbeat thread kept saying "alive", but the main thread was inside a 10-second batch. At 5 s the consumer
leaves the group, its partitions go to the others, and when it finally calls `commitSync()` the broker refuses:
`CommitFailedException`, you no longer own these partitions. The new owner starts from the last commit, which
never moved.

```
 t=0    poll → 500 records
 t=5s   max.poll.interval exceeded → leave group → REBALANCE (everyone stops, partitions reassigned)
 t=10s  batch done → commitSync → CommitFailedException
        poll → rejoin → REBALANCE again → same 500 records again
```

Each rebalance also pauses **every** consumer in the group (with the default eager assignor), so one slow
consumer slows everyone.

## Fixes

- **Make a batch fit**: `max.poll.records × worst-case time per record < max.poll.interval.ms`, with a wide margin.
  The worst case matters: a vendor timeout of 30 s turns a 20 ms record into a 30 s one.
- **Raise `max.poll.interval.ms`** if the work really is slow. This also makes it slower to detect a truly
  stuck consumer.
- **Don't do slow work on the poll thread**: hand records to a worker pool, `pause()` the partitions while it's
  busy, keep calling `poll()`, commit when the workers finish. More complex, and ordering per partition is now
  your problem (K2).
- **Cooperative rebalancing** (`assignor=cooperative-sticky` in the lab): only the moved partitions stop, not all
  of them.
- **Static membership** (`group.instance.id`, `staticMembership=true` in the lab): a consumer that restarts within
  `session.timeout.ms` gets its partitions back with no rebalance at all. This makes rolling deploys painless.

## Observe

```bash
docker exec qd-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group> --state
```

During the broken run the state keeps flipping between `PreparingRebalance`, `CompletingRebalance` and `Stable`.
In the logs, Grafana → Explore → Loki:

```
{container=~".*notification.*"} |= "poll timeout"
```

`consumer poll timeout has expired. This means the time between subsequent calls to poll() was longer than the
configured max.poll.interval.ms…`, then `sending LeaveGroup request to coordinator`. The client tells you exactly
what's wrong. Someone has to be looking at the logs to see it.

## Discussion

1. The heartbeat thread said the consumer was alive. Why isn't that enough?
2. The vendor's p99 is 2 s and its timeout is 10 s. Pick `max.poll.records` for `max.poll.interval.ms=300000`.
3. Why do rolling deploys of 20 consumer instances cause 20+ rebalances, and how does static membership help?
