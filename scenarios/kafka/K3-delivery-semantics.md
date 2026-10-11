# K3 — At-most-once, at-least-once, and the idempotent consumer

> **Kafka deep dive.** A consumer does two things per message: process it and commit its offset.
> No ordering of those two survives a crash. Pick which failure you can live with, then design it away.

## Run it

```bash
make load ID=K3 MODE=broken    # process, then commit
make load ID=K3 MODE=before    # commit, then process
make load ID=K3 MODE=fixed     # process, then commit, and skip messageIds already handled
```

20,000 messages on `lab-6p`, 6 consumers. Processing a message = sending an SMS to the vendor mock, which counts
duplicate `messageId`s. Every 3,000 records a consumer **crashes at the worst possible moment** for its commit
strategy (no commit, no clean shutdown), then rejoins the group as a new member.

| Strategy | Crashes | Processed again | **Lost** | **Customers texted twice** |
|---|---|---|---|---|
| process, then commit (at-least-once) | 6 | 551 | 0 | **551** |
| commit, then process (at-most-once) | 6 | 0 | **559** | 0 |
| process, then commit + dedupe (effectively once) | 6 | 827 (skipped) | 0 | **0** |

## What a crash does to each

A consumer polls a batch (up to 200 records here), works through it, and commits "I'm done up to offset N". The
next owner of the partition resumes from the **last committed offset**, nothing else.

```
 process, then commit:    poll 200 → process 1..120 → CRASH        (nothing committed)
                          next owner resumes at 1 → records 1..120 processed AGAIN → duplicate SMS

 commit, then process:    poll 200 → commit 200 → process 1..80 → CRASH
                          next owner resumes at 201 → records 81..200 never processed → LOST
```

**at-most-once** is what `enable.auto.commit=true` gives you by accident: offsets get committed on a timer in the
background, whether or not your code finished. **at-least-once** is the default you should want. A duplicate can be
detected; a loss can't.

## The fix: make processing idempotent

You can't stop Kafka from redelivering, so make a redelivery harmless:

```
 processed_messages(message_id PRIMARY KEY)

 on message:
   if message_id already in processed_messages → skip, commit
   else → do the work, insert message_id, commit
```

Our lab keeps the set in memory (`after-dedupe` in
[`LabConsumers.process`](../../services/notification-service/src/main/java/dev/quickdrop/notification/kafkalab/LabConsumers.java)).
In production it has to survive the crash too:

- **The work is a DB write**: put the dedupe insert in the **same transaction** as the work. Both or neither.
- **The work is an external call** (SMS, payment): send an **idempotency key** and let the vendor dedupe (the same idea as 1.3). Or
  record "sending" before the call and reconcile anything left in that state (notification-service's sending
  lease, 6.3).
- **The work is naturally idempotent**: `SET status = 'DELIVERED'` is safe to repeat. `balance = balance + 100`
  isn't.

The 827 skips are more than the 551 duplicates in the broken run because crashes land at different points each time.
The number varies from run to run; the 0 doesn't.

## "Exactly-once" in Kafka

Kafka does offer exactly-once (idempotent producer + transactions, K11), but only for **Kafka-to-Kafka** pipelines:
read a topic, write a topic, commit offsets, all atomically. Once your consumer touches anything outside Kafka (a
database, an SMS vendor), you're back to at-least-once plus idempotence. "Exactly-once delivery" doesn't exist end
to end. "Effectively once processing" does.

## Discussion

1. Which messages in QuickDrop can tolerate at-most-once? (Think about what losing each one costs.)
2. The dedupe table grows forever. How long do you need to keep message IDs?
3. A consumer crashes after the vendor accepted the SMS but before it inserted the message ID. What happens on redelivery, and how does an idempotency key at the vendor fix it?
