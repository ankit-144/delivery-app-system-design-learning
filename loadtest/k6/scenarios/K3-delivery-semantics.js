// Kafka deep dive K3 — At-least-once, at-most-once, and the idempotent consumer.
//
// 20,000 messages; each one "sends an SMS" (the vendor counts duplicate messageIds). Every 3,000 records a
// consumer crashes at the worst possible moment for its commit strategy, then rejoins.
//
//   make load ID=K3 MODE=broken    # process, then commit: a crash re-delivers the batch → duplicate SMS
//   make load ID=K3 MODE=before    # commit, then process: a crash skips what was committed → lost SMS
//   make load ID=K3 MODE=fixed     # process, then commit, and skip messageIds already processed
//
// Needs Phase 6+.
import http from 'k6/http';
import { sleep } from 'k6';
import { VENDOR_URL, lab, labWaitForLag, resetFixtures, setVendor } from '../lib/quickdrop.js';

export const options = { setupTimeout: '5m', scenarios: { run: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } } };

const COMMIT = { broken: 'after', before: 'before', fixed: 'after-dedupe' };

export function setup() {
  resetFixtures({});   // also resets the vendor's duplicate counter
  setVendor({ 'latency-ms': 5, 'jitter-ms': 0 });   // at the default 100 ms, 6 consumers would need 6 minutes
}

export function teardown() {
  setVendor({ 'latency-ms': 100, 'jitter-ms': 20 });
}

export default function () {
  const commit = COMMIT[__ENV.MODE || 'broken'];
  const group = `k3-${commit}-${Date.now()}`;
  lab('truncate', { topic: 'lab-6p' });
  lab('produce', { topic: 'lab-6p', count: 20000, keys: 'random' });
  lab('consumers/start', { topic: 'lab-6p', group, count: 6, commit, crashEvery: 3000, sendSms: true, maxPollRecords: 200 });
  labWaitForLag(group, 300, 'lab-6p');
  sleep(3);
  const s = lab('consumers/stop', { group });
  const vendor = http.get(`${VENDOR_URL}/v1/stats`).json();
  console.log(['', `================ K3 — commit ${commit} ================`,
    `  produced: 20000   unique messages processed: ${s.uniqueMessages}   LOST: ${20000 - s.uniqueMessages}`,
    `  processed again after a crash: ${s.duplicates}   skipped as duplicates: ${s.duplicatesSkipped}   crashes: ${s.crashes}`,
    `  vendor: ${vendor.accepted} SMS accepted, ${vendor.duplicates} DUPLICATE SMS (customers texted twice)`].join('\n'));
}
