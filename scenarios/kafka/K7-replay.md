# K7 — Kafka is a log, not a queue: replay it

> **Kafka deep dive.** Reading a message doesn't delete it. A consumer group's progress is just a
> number per partition, and you can move that number.

## Run it

```bash
make load ID=K7
```

An "analytics" consumer group reads 15,000 order events and builds its read model. Then we discover a bug in the
analytics code. We stop the group, **reset its offsets to the beginning**, start it again and rebuild everything
from the same events.

| Step | Result |
|---|---|
| first pass | 15,000 events processed, lag 0 |
| reset offsets to earliest | lag is **15,000** again: the events never left |
| second pass | the same 15,000 messages processed again |

## Why this works

A queue (RabbitMQ, SQS, the Postgres queue in 6.4) **removes** a message once a consumer acknowledges it. Kafka
**keeps** every message until the retention limit (24 h here; often 7 days; forever for some topics) and
records, per consumer group, an offset per partition:

```
 partition 0:  [0][1][2][3][4][5][6][7][8][9] …           messages stay
                                       ▲
                      analytics committed offset = 8      just a number, stored in __consumer_offsets
```

So:

- **Replay** = move the offset back. Same messages, same order per partition.
- **Skip** = move it forward (to `latest`, or to a timestamp).
- **New consumers** start wherever you tell them. `auto.offset.reset=earliest` reads all of history, `latest`
  only new messages.
- **Many groups read the same data independently** (K9). Nobody's reading slows anyone else's.

## Doing it by hand

The group must be stopped first. Kafka refuses to move offsets of an active group.

```bash
docker exec qd-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group <group> --topic lab-6p --reset-offsets --to-earliest --dry-run      # show what would happen
#  … --to-earliest --execute
#  … --to-datetime 2026-10-11T09:00:00.000 --execute     replay from just before the bad deploy
#  … --shift-by -1000 --execute
```

Our lab does the same through the AdminClient
([`KafkaLabController.resetOffsets`](../../services/notification-service/src/main/java/dev/quickdrop/notification/kafkalab/KafkaLabController.java)).

## Replay is only safe if consumers are idempotent

Replaying `notifications` would text every customer again. Before you replay a topic, ask what the consumer
*does*:

| Consumer | Replay safe? |
|---|---|
| builds a read model or search index (upserts by id) | yes, that's the point |
| aggregates ("orders per hour", `count += 1`) | only if you rebuild from scratch (truncate first) |
| sends SMS, charges cards, calls vendors | **no**, not without dedupe (K3) |

Retention is the other limit: you can only replay what's still there. Event-sourced systems keep the topic forever
or archive it (tiered storage, S3) so that new services can be bootstrapped from all of history.

## Discussion

1. A bug in the rider-incentive service paid riders wrong amounts for 3 hours. How would you use replay to fix the data, and what must you check first?
2. What's the difference between `auto.offset.reset=earliest` and resetting a group's offsets to earliest?
3. Why might you key a topic so it can be compacted (K10) if new services need to bootstrap from it?
