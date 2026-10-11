// Kafka deep dive K1 — Partitions are the unit of parallelism.
//
// 30,000 messages on a topic with 1 partition and 30,000 on a topic with 6 partitions. Each topic gets a
// consumer group of 6 consumers; processing a message takes 2 ms.
//
//   make load ID=K1
//
// Needs Phase 6+.
import { sleep } from 'k6';
import { lab } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  for (const topic of ['lab-1p', 'lab-6p']) lab('truncate', { topic });
  for (const topic of ['lab-1p', 'lab-6p']) lab('produce', { topic, count: 30000, keys: 'random' });
  const groups = { 'lab-1p': `k1-one-partition-${Date.now()}`, 'lab-6p': `k1-six-partitions-${Date.now()}` };
  const start = Date.now();
  for (const [topic, group] of Object.entries(groups)) lab('consumers/start', { topic, group, count: 6, processingMs: 2 });
  const finished = {};
  for (let t = 0; t < 120 && Object.keys(finished).length < 2; t++) {
    sleep(1);
    for (const group of Object.values(groups)) {
      if (!finished[group] && lab('consumers', { group }, 'GET').processed >= 30000) finished[group] = (Date.now() - start) / 1000;
    }
    if (t === 10) {
      // Snapshot of who owns what, while everyone is busy.
      for (const group of Object.values(groups)) console.log(`${group} partitions per consumer: ${JSON.stringify(lab('consumers', { group }, 'GET').partitionsPerConsumer)}`);
    }
  }
  const lines = ['', '================ K1 — partitions are the unit of parallelism ================'];
  for (const group of Object.values(groups)) {
    const s = lab('consumers/stop', { group });
    const secs = finished[group] || (Date.now() - start) / 1000;
    lines.push(`  ${group.replace(/-\d+$/, '').padEnd(20)} ${finished[group] ? 'finished 30,000' : `processed ${s.processed}`} in ${secs.toFixed(1)} s (~${Math.round(s.processed / secs)}/s)`);
    lines.push(`  ${''.padEnd(20)} processed per consumer: ${JSON.stringify(s.processedPerConsumer)}`);
  }
  console.log(lines.join('\n'));
}
