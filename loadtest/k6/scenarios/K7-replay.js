// Kafka deep dive K7 — Kafka is a log, not a queue: replay it.
//
// An "analytics" consumer group builds a read model from 15,000 order events. Then the analytics code turns
// out to have a bug — so we fix it, reset the group's offsets to the beginning, and rebuild from scratch.
// The events are still there: consuming a message doesn't delete it.
//
//   make load ID=K7
//
// Needs Phase 6+.
import { sleep } from 'k6';
import { lab, labWaitForLag } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  const group = `k7-analytics-${Date.now()}`;
  lab('truncate', { topic: 'lab-6p' });
  lab('produce', { topic: 'lab-6p', count: 15000, keys: 'order', eventsPerKey: 3 });
  lab('consumers/start', { topic: 'lab-6p', group, count: 3 });
  labWaitForLag(group, 120, 'lab-6p');
  const first = lab('consumers/stop', { group });
  const lagAfterFirst = lab('lag', { group }, 'GET').lag;
  const reset = lab('reset-offsets', { group, topic: 'lab-6p', to: 'earliest' });
  const lagAfterReset = lab('lag', { group }, 'GET').lag;
  lab('consumers/start', { topic: 'lab-6p', group, count: 3 });
  labWaitForLag(group, 120, 'lab-6p');
  sleep(1);
  const second = lab('consumers/stop', { group });
  console.log(['', '================ K7 — replay ================',
    `  first pass:  processed ${first.processed} events, lag afterwards ${lagAfterFirst}`,
    `  reset offsets to earliest (${reset.partitions} partitions): lag is now ${lagAfterReset}`,
    `  second pass: processed ${second.processed} events again — the same ${second.uniqueMessages} messages, rebuilt from the log`].join('\n'));
}
