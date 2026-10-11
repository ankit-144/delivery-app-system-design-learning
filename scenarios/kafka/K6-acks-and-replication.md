# K6 — What does an acknowledged write promise?

> **Kafka deep dive.** "Kafka acknowledged it" means different things depending on `acks`. With
> `acks=1` it means *one broker has it*, and that broker can die.

## Run it

```bash
make load ID=K6 MODE=broken    # acks=1
make load ID=K6 MODE=fixed     # acks=all
make kafka-cluster-down        # afterwards: frees ~1.6 GB
```

This lab starts its **own 3-broker cluster** (profile `kafka-cluster`: a KRaft controller plus brokers `kafka-a/b/c`),
separate from the single-node Kafka the rest of QuickDrop uses. Topic `acks-lab`: 1 partition, replication factor 3,
`min.insync.replicas=2`. It's a shell script ([`scripts/kafka-acks-lab.sh`](../../scripts/kafka-acks-lab.sh)), not
k6, because it has to kill containers.

**Experiment 1.** Produce 20,000 numbered messages/s for 20 s. At t=5 s both followers stall for 3 s (`docker
pause`: think GC pause, disk stall, network blip). At t=8 s, while they're behind, the leader is killed with `kill
-9`. Afterwards the script reads the topic back and compares it with every acknowledgement the producer received
([`AcksLab`](../../services/notification-service/src/main/java/dev/quickdrop/notification/kafkalab/AcksLab.java)).

| | `acks=1` | `acks=all` |
|---|---|---|
| sent / acknowledged | 400,000 / 400,000 | 400,000 / 400,000 |
| producer errors | none | none |
| acknowledgements per second during the stall | **20,000** (as if nothing happened) | **~0** (waiting for the followers) |
| **acknowledged but lost** | **59,860** | **0** |

**Experiment 2.** Kill two of the three brokers, then write 1,000 messages/s for 10 s.

| | `acks=1` | `acks=all` |
|---|---|---|
| acknowledged | 9,999 of 10,000 | **0** of 10,000 (`NotEnoughReplicas`, then timeout) |
| copies of each acknowledged message | **1** | — |

## Why 59,860 acknowledged messages vanished

```
 acks=1:   producer ──▶ leader writes to its log ──▶ "ack"          followers copy it… eventually
 acks=all: producer ──▶ leader writes ──▶ every in-sync replica has it ──▶ "ack"
```

While the followers were stalled, the `acks=1` leader kept acknowledging: 3 s × 20,000/s ≈ 60,000 messages that
existed **only on the leader**. Then the leader died. The controller elected a follower from the ISR (the set of
replicas considered caught up: the followers were still in it, because 3 s is within `replica.lag.time.max.ms`). The
new leader had never seen those 60,000 messages. When the old leader came back, it **truncated its log** to match
the new leader, deleting the only copies. The producer got a success callback for every one of them. No error was
logged anywhere.

With `acks=all`, the leader can't acknowledge until the in-sync followers have the data. During the stall the
producer simply waited (and buffered). After the failover it retried, and the idempotent producer made sure retries
didn't create duplicates.

## min.insync.replicas: durability vs availability

`acks=all` alone isn't enough. If the ISR shrinks to just the leader, "all in-sync replicas" is one broker, and
you're back to `acks=1`. `min.insync.replicas=2` says: refuse writes unless at least 2 replicas have them.
Experiment 2 is that rule in action: with one broker left, Kafka **refused every write** rather than accept data
it couldn't protect.

| Setting | Survives a broker loss without losing acked data | Still accepts writes with 1 of 3 brokers left |
|---|---|---|
| `acks=1` | no | yes |
| `acks=all`, `min.insync.replicas=1` | only if the ISR hadn't shrunk | yes |
| `acks=all`, `min.insync.replicas=2`, RF=3 | **yes** | **no** (that's the point) |

The production default for important data is the last row: RF=3, `min.insync.replicas=2`, `acks=all` (the producer
default since Kafka 3.0), `unclean.leader.election.enable=false` (the default: never elect a replica that wasn't in
sync, even if that means the partition stays offline).

## What it costs

- **Latency**: every `acks=all` produce waits for the slowest in-sync follower. Cross-zone replication adds
  milliseconds to every write.
- **Availability**: with RF=3 and min ISR 2, losing 2 brokers stops writes to their partitions.
- **The failover itself**: in both runs nothing was acknowledged for ~10 s. The dead leader had to be detected
  (`broker.session.timeout.ms`, 6 s here), a new one elected, and producers had to refresh metadata. Size
  `delivery.timeout.ms` and `buffer.memory` to ride that out.

## Discussion

1. The `acks=1` producer got 400,000 success callbacks. How would anyone ever find out that 59,860 were lost?
2. Which QuickDrop topics could live with `acks=1`? (Metrics? Rider GPS pings? Order events?)
3. Why does Kafka refuse writes in experiment 2 instead of accepting them on the one broker left?
