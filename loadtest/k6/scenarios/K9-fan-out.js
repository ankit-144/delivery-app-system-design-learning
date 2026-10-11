// Kafka deep dive K9 — One topic, many independent consumers.
//
// Order events feed two teams: analytics (fast) and rider incentives (slow, 5 ms per event). Each is its own
// consumer group with its own offsets. Halfway through, the incentives service goes down for 15 s.
//
//   make load ID=K9
//
// Needs Phase 6+.
import { sleep } from 'k6';
import { lab, labWaitForLag } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  const t = Date.now();
  const analytics = `k9-analytics-${t}`;
  const incentives = `k9-incentives-${t}`;
  lab('truncate', { topic: 'lab-6p' });
  lab('produce', { topic: 'lab-6p', count: 18000, keys: 'order', eventsPerKey: 3 });
  lab('consumers/start', { topic: 'lab-6p', group: analytics, count: 3 });
  lab('consumers/start', { topic: 'lab-6p', group: incentives, count: 3, processingMs: 5 });
  sleep(5);
  const down = lab('consumers/stop', { group: incentives });   // "the incentives service goes down"
  const lag = (group) => lab('lag', { group, topic: 'lab-6p' }, 'GET').lag;
  const lagDown = [];
  for (let i = 1; i <= 3; i++) {
    sleep(5);
    lagDown.push(`after ${i * 5} s: analytics lag ${lag(analytics)} / incentives lag ${lag(incentives)}`);
  }
  lab('consumers/start', { topic: 'lab-6p', group: incentives, count: 3, processingMs: 5 });   // back up: resumes from its commits
  labWaitForLag(incentives, 180, 'lab-6p');
  labWaitForLag(analytics, 60, 'lab-6p');
  const a = lab('consumers/stop', { group: analytics });
  const i = lab('consumers/stop', { group: incentives });
  console.log(['', '================ K9 — fan-out: two consumer groups, one topic ================',
    `  incentives processed ${down.processed} events, then went down for 15 s:`,
    ...lagDown.map((l) => `    ${l}`),
    `  analytics: ${a.processed} of 18000 processed, never waited for incentives`,
    `  incentives: back up, resumed from its own committed offsets and processed the other ${i.processed} (${down.processed} + ${i.processed} = ${down.processed + i.processed})`].join('\n'));
}
