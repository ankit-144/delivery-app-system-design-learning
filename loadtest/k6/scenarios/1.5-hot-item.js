// Scenario 1.5 — Optimistic locking on a hot item.
//
// Plenty of stock, but every customer wants the same product. Which strategy holds up?
//
//   make load ID=1.5 MODE=optimistic    # read version, write if unchanged, retry on conflict
//   make load ID=1.5 MODE=pessimistic   # wait in line for the row lock
//   make load ID=1.5 MODE=fixed         # atomic UPDATE: shortest lock, no retries
//
// Knobs: RATE (150/s), DURATION (45s), RETRIES (optimistic-max-retries, default 5),
//        LINE_DELAY (per-line-delay-ms, default 5: work done while the hot row is locked).
//
// Each order holds the row ~LINE_DELAY + 1.5 ms, so the row serves at most ~155 orders/s whatever the strategy.
// The question is what each strategy does with the queue: wait in line (pessimistic, atomic) or
// retry and waste work (optimistic).
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, printVerdict, randomCustomer, resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const HOT = 42;
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    hot_item: {
      executor: 'constant-arrival-rate',
      rate: envInt('RATE', 150),
      timeUnit: '1s',
      duration: env('DURATION', '45s'),
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
};

const COMMON = {
  'per-line-delay-ms': env('LINE_DELAY', '5'),
  'notification-mode': 'none',
  'rider-assignment': 'conditional',
  'rider-locator': 'db-nearby',
  'order-idempotency': 'off',
  'inventory-lock-order': 'sorted',
  'optimistic-max-retries': env('RETRIES', '5'),
  'auto-deliver-after-ms': '5000',
};

const MODES = {
  broken: { 'inventory-reservation': 'optimistic' },
  optimistic: { 'inventory-reservation': 'optimistic' },
  pessimistic: { 'inventory-reservation': 'pessimistic' },
  fixed: { 'inventory-reservation': 'atomic' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
}

export default function () {
  const res = placeOrder(randomCustomer(1), STORE, [{ productId: HOT, quantity: 1 }]);
  results.add(1, { result: checkOrder(res, ['placed', 'placed_pending_rider']) });
}

export function teardown() {
  printVerdict('Scenario 1.5 — hot item', { hint: 'Compare p99 and quickdrop_inventory_conflicts_total across modes' });
}
