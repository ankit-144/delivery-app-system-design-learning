// Kafka deep dive K2 — Ordering exists only within a partition.
//
// 10,000 orders × 3 events each (PLACED → RIDER_ASSIGNED → DELIVERED), 6 partitions, 6 consumers.
//
//   make load ID=K2 MODE=broken    # no key: events of one order spread over partitions
//   make load ID=K2 MODE=fixed     # key = order id: all events of an order in one partition, in order
//
// Needs Phase 6+.
import { sleep } from 'k6';
import { lab, labWaitForLag } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  const keys = __ENV.MODE === 'fixed' ? 'order' : 'none';
  const group = `k2-${keys}-${Date.now()}`;
  lab('truncate', { topic: 'lab-6p' });
  lab('produce', { topic: 'lab-6p', count: 30000, keys, eventsPerKey: 3 });
  lab('consumers/start', { topic: 'lab-6p', group, count: 6, processingMs: 1 });
  labWaitForLag(group, 180, 'lab-6p');
  sleep(2);
  const s = lab('consumers/stop', { group });
  console.log(['', `================ K2 — ordering (key: ${keys === 'order' ? 'order id' : 'none'}) ================`,
    `  events processed: ${s.processed}`,
    `  events processed out of order (e.g. DELIVERED before RIDER_ASSIGNED): ${s.outOfOrder}`].join('\n'));
}
