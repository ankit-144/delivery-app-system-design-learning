// Kafka deep dive K8 — Batching and compression: throughput vs latency.
//
// The same 200,000 messages (300-byte JSON) produced with three producer configurations.
//
//   make load ID=K8
//
// Needs Phase 6+.
import { lab } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

const CONFIGS = [
  { name: 'no batching', lingerMs: 0, batchKb: 1, compression: 'none' },
  { name: 'default-ish', lingerMs: 5, batchKb: 16, compression: 'none' },
  { name: 'big batches', lingerMs: 20, batchKb: 256, compression: 'none' },
  { name: 'big + lz4', lingerMs: 20, batchKb: 256, compression: 'lz4' },
  { name: 'big + zstd', lingerMs: 20, batchKb: 256, compression: 'zstd' },
];

export default function () {
  const lines = ['', '================ K8 — producer batching and compression (200,000 × ~300 bytes) ================',
    `  ${'config'.padEnd(13)} ${'msgs/s'.padStart(9)} ${'avg batch'.padStart(10)} ${'records/req'.padStart(11)} ${'compressed'.padStart(10)} ${'MB sent'.padStart(8)} ${'queue ms'.padStart(9)} ${'request ms'.padStart(10)}`];
  for (const c of CONFIGS) {
    lab('truncate', { topic: 'lab-6p' });
    const r = lab('produce', { topic: 'lab-6p', count: 200000, keys: 'random', payloadBytes: 200, lingerMs: c.lingerMs, batchKb: c.batchKb, compression: c.compression });
    lines.push(`  ${c.name.padEnd(13)} ${String(r.messagesPerSecond).padStart(9)} ${String(Math.round(r['batch-size-avg'])).padStart(9)}B ${String(r['records-per-request-avg']).padStart(11)} ${String(r['compression-rate-avg']).padStart(10)} ${(r['outgoing-byte-total'] / 1e6).toFixed(1).padStart(8)} ${String(r['record-queue-time-avg']).padStart(9)} ${String(r['request-latency-avg']).padStart(10)}`);
  }
  lines.push('  compressed = compressed size / original size (1.0 = not compressed); MB sent = bytes the producer put on the network');
  lines.push('  queue ms = time a record waits in the producer before being sent; request ms = broker round trip per request');
  console.log(lines.join('\n'));
}
