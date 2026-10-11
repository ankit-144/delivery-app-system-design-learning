// Scenario 1.4 — Deadlocks on multi-item carts.
//
// Half the customers order [milk, bread], the other half [bread, milk]. Each transaction locks
// the stock rows in the order the items were listed. A holds milk and waits for bread; B holds
// bread and waits for milk. Postgres notices after deadlock_timeout (1 s) and kills one of them.
//
//   make load ID=1.4 MODE=broken          # SELECT FOR UPDATE, rows locked in cart order
//   make load ID=1.4 MODE=broken-atomic   # even plain UPDATEs deadlock — they take row locks too
//   make load ID=1.4 MODE=fixed           # rows locked in product-id order: no cycles possible
//
// Knobs: RATE (30/s), DURATION (30s), LINE_DELAY (per-line-delay-ms, default 10).
//
// per-line-delay-ms stands in for per-item work done while the row is locked (pricing, promos).
// Without it a warm laptop locks both rows within ~0.3 ms and the cycle almost never forms.
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, printVerdict, randomCustomer, resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const MILK = 11;
const BREAD = 12;
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    carts: {
      executor: 'constant-arrival-rate',
      rate: envInt('RATE', 30),
      timeUnit: '1s',
      duration: env('DURATION', '30s'),
      preAllocatedVUs: 60,
      maxVUs: 400,
    },
  },
};

const COMMON = {
  'notification-mode': 'none',
  'rider-assignment': 'conditional',
  'rider-locator': 'db-nearby',
  'order-idempotency': 'off',
  'per-line-delay-ms': env('LINE_DELAY', '10'),
};

const MODES = {
  broken: { 'inventory-reservation': 'pessimistic', 'inventory-lock-order': 'as-given' },
  'broken-atomic': { 'inventory-reservation': 'atomic', 'inventory-lock-order': 'as-given' },
  fixed: { 'inventory-reservation': 'pessimistic', 'inventory-lock-order': 'sorted' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
}

export default function () {
  const items = Math.random() < 0.5
    ? [{ productId: MILK, quantity: 1 }, { productId: BREAD, quantity: 1 }]
    : [{ productId: BREAD, quantity: 1 }, { productId: MILK, quantity: 1 }];
  const res = placeOrder(randomCustomer(1), STORE, items);
  results.add(1, { result: checkOrder(res, ['placed', 'placed_pending_rider']) });
}

export function teardown() {
  printVerdict('Scenario 1.4 — deadlocks', { hint: 'Look at Postgres -> Deadlocks and Overview -> API errors (deadlock)' });
}
