// Kafka deep dive K4 — One message nobody can process.
//
// Orders flow through Kafka (kafka-direct). A malformed message lands on the notifications topic.
//
//   make load ID=K4 MODE=broken   # poison-handling=retry-forever: its partition is stuck; orders behind it get no SMS
//   make load ID=K4 MODE=skip     # skip after 3 tries: everything flows; the bad message is silently gone
//   make load ID=K4 MODE=fixed    # dlq: after 3 tries it moves to notifications.DLT; nothing blocked, nothing lost
//
// Needs Phase 6+.   Knobs: DURATION (30s).
import { sleep } from 'k6';
import {
  applyMode, env, flushNotificationBacklog, lab, reconcileSms, resetFixtures, setNotify, setVendor, waitForNotificationsToDrain,
} from '../lib/quickdrop.js';
import { stream, streams } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const s = streams(['order']);
const POISON = { broken: 'retry-forever', skip: 'skip', fixed: 'dlq' };

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '6m',
  scenarios: {
    orders: stream('order', 10, env('DURATION', '30s')),
    poison: { executor: 'shared-iterations', exec: 'poison', vus: 1, iterations: 1, startTime: '3s' },
  },
};

const MODES = { broken: {}, skip: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'kafka-direct', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' });
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync', 'poison-handling': POISON[__ENV.MODE || 'broken'] });
  setVendor({ 'latency-ms': '10', 'jitter-ms': '0' });
  return { dltBefore: lab('count', { topic: 'notifications.DLT' }, 'GET').records };
}

export function poison() {
  console.log(`poison message: ${JSON.stringify(lab('poison', { topic: 'notifications' }))}`);
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function teardown(data) {
  sleep(10);
  const stuck = lab('lag', { group: 'notification-service' }, 'GET');
  console.log(`lag per partition while the poison is there: ${JSON.stringify(stuck.perPartition)}`);
  console.log(`reconciliation: ${JSON.stringify(reconcileSms())}`);
  console.log(`messages in notifications.DLT: ${lab('count', { topic: 'notifications.DLT' }, 'GET').records - data.dltBefore} new`);
  // Unblock for the next run: switch to skip, so the stuck partition moves on.
  setNotify({ 'poison-handling': 'skip' });
  waitForNotificationsToDrain(120);
}
