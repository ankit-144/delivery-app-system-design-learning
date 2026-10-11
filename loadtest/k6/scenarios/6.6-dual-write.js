// Scenario 6.6 — The dual write: an order in Postgres, an event in Kafka, and no transaction across both.
//
//   make load ID=6.6 MODE=broken      # kafka-direct: commit, then publish. 5% "crash" in between → orders with no SMS
//   make load ID=6.6 MODE=in-tx       # kafka-in-tx: publish, then commit. 5% roll back after → SMS for orders that don't exist
//   make load ID=6.6 MODE=fixed       # outbox: the event is a row in the order's own transaction; a relay publishes it
//
// The summary reconciles orders against SMS the vendor received. Needs Phase 6.   Knobs: DURATION (30s).
import { sleep } from 'k6';
import {
  applyMode, env, reconcileSms, resetFixtures, setNotify, setVendor, waitForNotificationsToDrain, flushNotificationBacklog } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const s = streams(['order']);

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '6m',
  scenarios: { orders: stream('order', 20, env('DURATION', '30s')) },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = { 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' };
const MODES = {
  broken: { 'notification-mode': 'kafka-direct', 'crash-after-commit-percent': '5' },
  'in-tx': { 'notification-mode': 'kafka-in-tx', 'rollback-after-publish-percent': '5' },
  fixed: { 'notification-mode': 'outbox' },
};

export function setup() {
  applyMode(MODES, COMMON);
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync' });
  setVendor({ 'latency-ms': '20', 'jitter-ms': '5' });
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function teardown() {
  sleep(5);
  waitForNotificationsToDrain(180);
  sleep(5);
  console.log(`reconciliation: ${JSON.stringify(reconcileSms())}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.6 — dual write vs outbox (order ok % includes deliberate rollbacks in in-tx mode)');
}
