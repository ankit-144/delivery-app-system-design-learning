// Kafka deep dive K10 — A compacted topic: the latest value per key.
//
// 1,000 riders report their status 100 times each (100,000 messages) to rider-status, a topic with
// cleanup.policy=compact. The log cleaner removes every message that has a newer one with the same key,
// but only in closed segments: the segment being written to (the active one) is never compacted.
//
//   make load ID=K10
//
// Needs Phase 6+. Takes ~2 minutes (compaction runs in the background).
import { sleep } from 'k6';
import { lab } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

function count(label, lines) {
  const c = lab('count', { topic: 'rider-status' }, 'GET');
  lines.push(`  ${label.padEnd(46)} ${String(c.records).padStart(7)} records, ${c.distinctKeys} distinct riders`);
}

export default function () {
  const lines = ['', '================ K10 — compacted topic rider-status (1 MB segments, roll after 10 s) ================'];
  count('before:', lines);
  lab('produce', { topic: 'rider-status', count: 100000, keys: 'rider', keySpace: 1000, eventsPerKey: 1 });
  count('right after 100,000 updates (100 per rider):', lines);
  sleep(30);
  count('30 s later (closed segments compacted):', lines);
  sleep(15);
  lab('produce', { topic: 'rider-status', count: 1000, keys: 'rider', keySpace: 1000, eventsPerKey: 1 });
  count('one more update per rider (rolls the segment):', lines);
  sleep(30);
  count('30 s later:', lines);
  sleep(30);
  count('60 s later:', lines);
  lines.push('  What remains: the latest value per rider in compacted segments, plus whatever sits in each');
  lines.push('  partition\'s active segment. A consumer reading from the start still ends with every rider\'s latest status.');
  console.log(lines.join('\n'));
}
