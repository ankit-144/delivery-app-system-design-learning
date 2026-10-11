// Scenario 3.7 — What the load balancer does when an instance is slow or dies.
//
//   make load ID=3.7 MODE=slow          # core-2 answers 300 ms slower than the others (round-robin keeps feeding it)
//   make lb ALGO=least-conn && make load ID=3.7 MODE=slow     # ...least-connections routes around it
//   make load ID=3.7 MODE=crash         # core-3 dies mid-traffic (like kill -9 or an OOM kill)
//   make load ID=3.7 MODE=graceful      # core-3 shuts down properly (like a deploy)
//   make lb ALGO=round-robin            # put nginx back
//
// Docker restarts core-3 after it exits; watch it come back on the Redis & scale-out dashboard.
// Needs Phase 3.   Knobs: DURATION (60s).
import http from 'k6/http';
import { applyMode, env, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse']);

export const options = {
  setupTimeout: '6m',
  scenarios: {
    orders: stream('order', 40, DURATION),
    browsing: stream('browse', 80, DURATION),
    chaos: { executor: 'shared-iterations', exec: 'chaos', vus: 1, iterations: 1, startTime: '15s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { slow: {}, crash: {}, graceful: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' });
  resetFixtures({ restockAll: 100000 });
  if (__ENV.MODE === 'slow') {
    const res = http.put('http://core-2:8080/admin/toggles/request-delay-ms?value=300');
    console.log(`core-2 is now 300 ms slower: ${res.status}`);
  }
}

export function chaos() {
  const mode = __ENV.MODE || 'slow';
  if (mode === 'crash' || mode === 'graceful') {
    const res = http.post(`http://core-3:8080/admin/chaos/exit?mode=${mode}`);
    console.log(`core-3 told to exit (${mode}): ${res.status}`);
  }
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function browse() {
  const res = browseReq('30s');
  s.record('browse', res, res.status === 200);
}

export function teardown() {
  http.put('http://core-2:8080/admin/toggles/request-delay-ms?value=0');
}

export function handleSummary(data) {
  const failed = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.passes : 0;
  return s.summary(data, `Scenario 3.7 — load balancer (${__ENV.MODE || 'slow'})`, [
    `failed requests (5xx or connection errors): ${failed}`,
    'Redis & scale-out -> "Requests per second by core instance"; Overview -> 5xx error ratio.',
  ]);
}
