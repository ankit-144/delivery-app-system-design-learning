# K8 — Batching and compression: the producer's knobs

> **Kafka deep dive.** A producer doesn't send messages one by one. It groups them into batches per
> partition. How long it waits to fill a batch, how big a batch can get, and whether it compresses it decide
> throughput, latency, CPU and network.

## Run it

```bash
make load ID=K8
```

The same 200,000 messages (~300 bytes of JSON each) produced with five configurations, `acks=all`, to a
6-partition topic. The producer runs in notification-service, which has **1 CPU**.

| Config | `linger.ms` | `batch.size` | Compression | msgs/s | Avg batch | MB on the wire | Waiting in the producer |
|---|---|---|---|---|---|---|---|
| no batching | 0 | 1 KB | none | 67,245 | 768 B | 78.2 | **532 ms** |
| default-ish | 5 | 16 KB | none | 225,084 | 14 KB | 71.1 | 5 ms |
| big batches | 20 | 256 KB | none | **337,865** | 160 KB | 70.9 | 7 ms |
| big + lz4 | 20 | 256 KB | lz4 | 179,463 | 128 KB | 37.0 | 14 ms |
| big + zstd | 20 | 256 KB | zstd | 161,214 | 100 KB | **23.0** | 20 ms |

## What it shows

**"No batching" is slower *and* has higher latency.** You'd expect `linger.ms=0` and tiny batches to give the
lowest latency. Under load it's the opposite: every request carries 12 records, the producer can only have 5
requests in flight per broker, so records queue up behind each other: **532 ms** waiting in the producer's buffer.
Batching amortises the per-request cost (headers, a round trip, a disk write on the broker): 5× the throughput, and
each record waits a few milliseconds instead of half a second.

**Compression trades CPU for bytes.** zstd sent **3.4× fewer bytes** (JSON with repeated field names and words
compresses well), and the broker stores them compressed too. But on a 1-CPU producer, compressing costs more
than it saves, so throughput halved. On a laptop the network is free (loopback). In production it isn't:

- cross-zone traffic is billed per GB in the cloud, and replication sends every byte 2 more times
- broker disks and retention are sized in bytes
- consumers decompress, so their CPU pays too

Rule of thumb: turn on `lz4` or `zstd` for anything high-volume. Larger batches compress better (more repetition
per batch), which is another reason to batch.

## The knobs

| Setting | Default (kafka-clients 3.9) | Effect |
|---|---|---|
| `linger.ms` | 0 (5 from Kafka 4.0) | max time to wait for a batch to fill. Higher = bigger batches, a little more latency at low traffic. Even at 0, records that arrive while a request is in flight are batched |
| `batch.size` | 16 KB | max bytes per partition batch. A full batch is sent at once, without waiting for `linger.ms` |
| `compression.type` | none | `lz4` (fast), `zstd` (smallest), `snappy`, `gzip` (slow) |
| `buffer.memory` | 32 MB | when full, `send()` **blocks** for up to `max.block.ms` (60 s), then throws |
| `max.in.flight.requests.per.connection` | 5 | requests awaiting acks per broker. ≤ 5 keeps ordering with idempotence on (K2) |

The `buffer.memory` row is the one that bites in production. A slow broker or network makes the buffer fill up,
and then `send()`, which everyone assumes is async, blocks your request thread for up to a minute. core sets
`max.block.ms=5000` (its `application.yml`) so a Kafka outage fails orders fast instead of hanging them.

## Discussion

1. Why did "no batching" have *higher* latency than batching?
2. Our promos go through `publishAsync` and our OTPs through a synchronous `publish`. Which knobs would you tune differently for each?
3. When would you choose `lz4` over `zstd`?
