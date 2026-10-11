# K4 — One message nobody can process

> **Kafka deep dive.** A consumer commits offsets in order. If it can never get past one message, it
> never gets past that message's whole partition.

## Run it

```bash
make load ID=K4 MODE=broken    # poison-handling=retry-forever
make load ID=K4 MODE=skip      # 3 tries, then move on
make load ID=K4 MODE=fixed     # 3 tries, then dead-letter topic
```

Orders flow through Kafka (`notification-mode=kafka-direct`) at 10/s for 30 s. Three seconds in, a **malformed
message** (truncated JSON) lands on the real `notifications` topic, partition 3. Afterwards the script reconciles
orders against the SMS the vendor received (as in 6.6).

| Handling | Lag on partition 3 afterwards | Orders **without** an SMS | Poison message ends up |
|---|---|---|---|
| retry forever | **43** (and growing) | **42 of 301** | still at the head of partition 3 |
| skip after 3 tries | 0 | 0 | **nowhere**: a log line is all that's left |
| dead-letter topic | 0 | 0 | `notifications.DLT`, with the error in its headers |

Every other partition kept flowing in the broken run. Only the customers who hash to partition 3 stopped getting
SMS, which is exactly why this is hard to spot. **Kafka & notifications → Consumer lag per partition** shows one
flat-then-climbing line among five at zero.

## Why one message blocks a partition

```
 partition 3:  … 85781  85782  [85783 POISON]  85784  85785  85786 …
                           committed ▲          ▲ everything from here waits
```

A consumer's position in a partition is **one number**: "everything before offset N is done". There's no way to
say "done except 85783". So when 85783 throws, the consumer can retry it, or give up on it and commit past it.
Spring Kafka's
[`DefaultErrorHandler`](../../services/notification-service/src/main/java/dev/quickdrop/notification/KafkaErrorHandling.java)
retries with a back-off, then calls a **recoverer**. What the recoverer does is the whole decision:

- **Retry forever** is right for *transient* errors (vendor down, DB failover): wait, it'll work. It's a disaster
  for *permanent* ones (bad data never gets better).
- **Skip** unblocks the partition and loses the message. Fine for metrics, not for "your order is confirmed".
- **Dead-letter topic (DLT)**: publish the failed record, with the exception in headers, to `notifications.DLT`.
  Then commit past it. Nothing is blocked and nothing is lost.

## Working with a DLT

```bash
docker exec qd-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic notifications.DLT --from-beginning --property print.headers=true --timeout-ms 5000
```

Headers like `kafka_dlt-exception-message` and `kafka_dlt-original-offset` tell you what failed and where it came
from. A DLT is only useful if someone **looks at it**:

- **Alert on DLT growth** (8.2). A DLT nobody watches is a slower `skip`.
- **Fix, then replay**: once the bug or bad data is fixed, republish DLT messages to the original topic.
- **Separate retryable from non-retryable**: a parse error goes straight to the DLT; a vendor timeout gets retries
  first. Spring's `addNotRetryableExceptions(...)` does this. Bigger systems use retry topics with growing delays
  (`notifications.retry-1m`, `notifications.retry-10m`) so slow retries don't block the main partition either.

## Discussion

1. Why did only 42 of the 301 orders lose their SMS, not all of them?
2. When is "retry forever" the right choice?
3. A bug deploys that makes *every* message fail. What happens to the DLT, and what should you do before replaying it?
