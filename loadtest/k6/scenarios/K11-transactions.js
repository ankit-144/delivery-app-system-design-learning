// Kafka deep dive K11 — Exactly-once inside Kafka: read-process-write with transactions.
//
// A "copier" reads lab-6p and writes every message to lab-out. Every 1,500 records it crashes before
// committing its input offsets, restarts, and resumes from the last commit.
//
//   make load ID=K11 MODE=broken   # plain producer + commitSync: the batch since the last commit is written twice
//   make load ID=K11 MODE=fixed    # transactional producer: writes and offsets commit together, or not at all
//
// Needs Phase 6+.
import { sleep } from 'k6';
import { lab, labWaitForLag } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  const transactional = __ENV.MODE === 'fixed';
  for (const topic of ['lab-6p', 'lab-out']) lab('truncate', { topic });
  lab('produce', { topic: 'lab-6p', count: 20000, keys: 'random' });
  const started = lab('copier/start', { transactional, crashEvery: 1500 });
  labWaitForLag(started.group, 240, 'lab-6p');
  sleep(2);
  const stopped = lab('copier/stop');
  const committed = lab('count', { topic: 'lab-out', isolation: 'read_committed' }, 'GET');
  const uncommitted = lab('count', { topic: 'lab-out', isolation: 'read_uncommitted' }, 'GET');
  console.log(['', `================ K11 — copier, ${transactional ? 'transactional' : 'plain'} producer ================`,
    `  crashes: ${stopped.crashes}`,
    `  lab-out as a read_committed consumer sees:   ${committed.records} records, ${committed.uniqueMessageIds} unique (input had 20000)`,
    `  lab-out as a read_uncommitted consumer sees: ${uncommitted.records} records, ${uncommitted.uniqueMessageIds} unique`].join('\n'));
}
