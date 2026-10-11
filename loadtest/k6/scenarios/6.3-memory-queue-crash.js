// Scenario 6.3 — An in-memory queue, and the restart that empties it.
//
// notification-service answers core at once (202) and sends later. The vendor takes 500 ms; there are
// 4 senders per instance, so ~16 SMS/s go out while 30 orders/s come in: a backlog builds. 30 s in,
// notification-1 crashes (and Docker restarts it).
//
//   make load ID=6.3 MODE=broken    # delivery-mode=async-memory: the backlog was in notification-1's heap
//   make load ID=6.3 MODE=fixed     # delivery-mode=db-queue: the backlog was in Postgres, workers carry on
//
// The summary reconciles orders against SMS the vendor received. Needs Phase 6.   Knobs: DURATION (45s).
import http from 'k6/http';
import { sleep } from 'k6';
import {
  applyMode, env, reconcileSms, resetFixtures, setNotify, setVendor, waitForNotificationsToDrain, flushNotificationBacklog } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '45s');
const s = streams(['order']);

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '6m',
  scenarios: {
    orders: stream('order', 30, DURATION),
    crash: { executor: 'shared-iterations', exec: 'crash', vus: 1, iterations: 1, startTime: '30s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'http', 'notify-timeout-ms': '2000', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' });
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': __ENV.MODE === 'fixed' ? 'db-queue' : 'async-memory', workers: '4' });
  setVendor({ 'latency-ms': '500', 'jitter-ms': '20' });
}

export function crash() {
  const res = http.post('http://notification-1:8080/admin/chaos/exit?mode=crash');
  console.log(`notification-1 crashed (${res.status}); Docker restarts it`);
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function teardown() {
  // Speed the vendor up so the backlog drains quickly, then compare orders with SMS received.
  setVendor({ 'latency-ms': '20', 'jitter-ms': '0' });
  sleep(20);
  waitForNotificationsToDrain(240);
  sleep(5);
  console.log(`reconciliation: ${JSON.stringify(reconcileSms())}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.3 — in-memory queue vs crash');
}
