# K11 — Exactly-once inside Kafka: transactions

> **Kafka deep dive.** A stream processor reads a topic, transforms, writes another topic, and commits
> its input offsets. Kafka transactions make the writes and the offset commit one atomic step.

## Run it

```bash
make load ID=K11 MODE=broken    # plain producer, then commitSync
make load ID=K11 MODE=fixed     # transactional producer, sendOffsetsToTransaction
```

A **copier** reads 20,000 messages from `lab-6p` and writes each to `lab-out`. Every 1,500 records it **crashes**:
its writes have reached the broker, its input offsets aren't committed. It restarts and resumes from the last
commit.

| Producer | Crashes | `lab-out` seen by a `read_committed` consumer | seen by `read_uncommitted` |
|---|---|---|---|
| plain | 14 | **21,507** records (1,507 duplicates) | 21,507 |
| transactional | 14 | **20,000**, exactly once | 21,299 |

The last column is the interesting one: the duplicates **are physically in the log** in both runs. In the
transactional run they're marked *aborted*, and a `read_committed` consumer skips them.

## What a transaction does

[`LabCopier`](../../services/notification-service/src/main/java/dev/quickdrop/notification/kafkalab/LabCopier.java):

```java
producer.initTransactions();                     // registers transactional.id "lab-copier", fences older instances
while (running) {
    records = consumer.poll(…);
    producer.beginTransaction();
    for (record : records) producer.send(new ProducerRecord<>("lab-out", record.key(), record.value()));
    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());   // the input offsets join the transaction
    producer.commitTransaction();                // writes + offsets become visible together, or not at all
}
```

- The **transaction coordinator** (a broker) writes commit or abort markers into every partition the transaction
  touched, including `__consumer_offsets`.
- `isolation.level=read_committed` consumers only read up to the last stable offset and drop aborted records.
  The default is `read_uncommitted`, which sees everything, aborted writes included.
- **Fencing**: a restarted copier with the same `transactional.id` bumps an epoch, and the old instance (a zombie
  that was only paused, not dead) gets `ProducerFencedException` on its next write instead of writing duplicates.

## Where exactly-once ends

Exactly-once covers **Kafka → Kafka**: consume, produce, commit offsets. The moment the processing has a side effect
outside Kafka (an SMS, a database row, an HTTP call), a crash between the side effect and the commit repeats it.
That's K3's problem, and the answer there is still an idempotent consumer. Kafka Streams turns this on with one
setting (`processing.guarantee=exactly_once_v2`) because its whole world is Kafka topics and state stores.

The cost: commits add latency (markers on every partition), consumers in `read_committed` wait for open
transactions, and a long transaction holds back everyone's last stable offset.

## Discussion

1. Why do the aborted records stay in the log instead of being deleted?
2. A downstream team reads `lab-out` with the default isolation level. What do they see in the transactional run?
3. The copier also updates a Postgres table for each message. Is the pipeline still exactly-once?
