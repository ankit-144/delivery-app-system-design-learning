// Kafka deep dive K5 — A rebalance storm.
//
// Each consumer fetches up to 500 records per poll and spends 20 ms on each: 10 s between polls. But
// max.poll.interval.ms is 5 s: if a consumer doesn't poll within 5 s, the group assumes it's dead and gives its
// partitions to someone else — who starts again from the last commit.
//
//   make load ID=K5 MODE=broken    # max.poll.records=500: every batch outlives the poll interval
//   make load ID=K5 MODE=fixed     # max.poll.records=50: 1 s per batch, well inside the interval
//
// Needs Phase 6+.   Knobs: DURATION (60s).
import { sleep } from 'k6';
import { env, lab } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

export default function () {
  const fixed = __ENV.MODE === 'fixed';
  const group = `k5-${fixed ? 'small' : 'big'}-batches-${Date.now()}`;
  lab('truncate', { topic: 'lab-6p' });
  lab('produce', { topic: 'lab-6p', count: 20000, keys: 'random' });
  lab('consumers/start', {
    topic: 'lab-6p', group, count: 3, processingMs: 20, maxPollIntervalMs: 5000, maxPollRecords: fixed ? 50 : 500,
  });
  const seconds = parseInt(env('DURATION', '60s'), 10);
  sleep(seconds);
  const s = lab('consumers/stop', { group });
  console.log(['', `================ K5 — max.poll.records ${fixed ? 50 : 500}, 20 ms per record, max.poll.interval.ms 5000 ================`,
    `  unique messages processed in ${seconds} s: ${s.uniqueMessages}   (~${Math.round(s.uniqueMessages / seconds)}/s)`,
    `  processed more than once: ${s.duplicates}   rebalances: ${s.rebalances}   commits rejected (kicked out of the group): ${s.commitFailures}`].join('\n'));
}
