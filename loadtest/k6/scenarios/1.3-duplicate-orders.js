// Scenario 1.3 — The customer taps "Pay" twice (or the app retries after a timeout).
//
// Every iteration sends the same order twice at the same moment with the same Idempotency-Key.
// Correct outcome: one order, and the second request gets that same order back (HTTP 200,
// Idempotent-Replay: true).
//
//   make load ID=1.3 MODE=broken    # header ignored: two orders, charged twice
//   make load ID=1.3 MODE=fixed     # key claimed in the database, the copy is a replay
//
// Knobs: RATE (20 pairs/s), DURATION (30s).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import {
  BASE_URL, applyMode, env, envInt, outcome, printVerdict, randomCustomer, randomInt, resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    double_tap: {
      executor: 'constant-arrival-rate',
      rate: envInt('RATE', 20),
      timeUnit: '1s',
      duration: env('DURATION', '30s'),
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
};

const COMMON = {
  'per-line-delay-ms': '0',
  'notification-mode': 'none',
  'inventory-reservation': 'atomic',
  'inventory-lock-order': 'sorted',
  'rider-assignment': 'conditional',
  'rider-locator': 'db-nearby',
};

const MODES = {
  broken: { 'order-idempotency': 'off' },
  fixed: { 'order-idempotency': 'on' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
}

export default function () {
  const key = `tap-${exec.scenario.iterationInTest}-${Date.now()}`;
  const body = JSON.stringify({
    customerId: randomCustomer(1),
    storeId: STORE,
    items: [{ productId: randomInt(1, 500), quantity: randomInt(1, 3) }],
  });
  const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { name: 'POST /api/orders' } };
  const [first, second] = http.batch([
    ['POST', `${BASE_URL}/api/orders`, body, params],
    ['POST', `${BASE_URL}/api/orders`, body, params],
  ]);
  const a = outcome(first);
  const b = outcome(second);
  results.add(1, { result: a });
  results.add(1, { result: b });
  const sameOrder = first.status < 300 && second.status < 300 && first.json('id') === second.json('id');
  check(null, { 'both taps returned the same order': () => sameOrder });
}

export function teardown() {
  printVerdict('Scenario 1.3 — duplicate orders');
}
