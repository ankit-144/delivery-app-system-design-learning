# K10 — A compacted topic: the latest value per key

> **Kafka deep dive.** Some topics aren't a history of events but a table of current state: the
> latest status of each rider, each store's opening hours. Compaction keeps the last message per key and
> throws away the rest.

## Run it

```bash
make load ID=K10       # ~2.5 minutes: compaction runs in the background
```

1,000 riders send 100 status updates each (100,000 messages) to `rider-status`, a topic with
`cleanup.policy=compact`. Then each rider sends one more.

| Moment | Records in the topic | Distinct riders |
|---|---|---|
| right after 100,000 updates | 100,000 (+ leftovers from earlier runs) | 1,000 |
| 30 s later | **4,825** | 1,000 |
| one more update per rider | 5,825 | 1,000 |
| 30 s later | **2,000** | 1,000 |

A consumer reading `rider-status` from the beginning ends up with every rider's latest status, whatever the
topic's age, after reading ~2,000 records instead of 100,000.

## How compaction works

A partition's log is a series of **segment files**. New messages go to the **active segment**. The log cleaner
works on closed segments only:

```
 before:  [seg 1: r1=A r2=B r1=C r3=D …] [seg 2: … r1=Z …] [active: r2=Q r3=R]
 after:   [seg 1+2 compacted: r1=Z, plus anything not superseded]  [active: r2=Q r3=R]   (untouched)
```

For every key, only the newest value survives in closed segments. Offsets don't change: a compacted log has gaps,
and consumers skip over them.

**Our first attempt showed no compaction at all.** All 100,000 messages landed in one active segment, and with no
more writes it never rolled. That's why the topic is configured with 1 MB segments that roll after 10 s
(`kafka-init` in docker-compose.yml), and why the extra update per rider matters: it closes the segment that held
the leftovers. Production topics use defaults like 1 GB / 7 days, so compaction there is *eventual*: hours, not
seconds.

## Using it

- **Deletes are tombstones**: a message with the key and a `null` value. Compaction removes older values, and after
  `delete.retention.ms` the tombstone too.
- **Bootstrapping state**: a service that restarts reads the compacted topic from the start and has the full,
  current table in memory, without querying another service's database. Kafka Streams' state stores and Kafka's own
  `__consumer_offsets` work this way.
- **Not a database**: no queries, no secondary indexes, only "latest value per key, eventually".

## Discussion

1. Why are the riders' older updates still readable for a while after you send them?
2. A rider deletes their account. What do you publish to `rider-status`, and when does their data actually disappear?
3. Could `notifications` be compacted? What would you lose?
