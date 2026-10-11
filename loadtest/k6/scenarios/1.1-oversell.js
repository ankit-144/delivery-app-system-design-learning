// Scenario 1.1 — Overselling the last units.
//
// Flash sale: product 42 at store 1 has STOCK units. RATE customers per second try to buy one.
// Correct outcome: exactly STOCK orders succeed, everyone else gets out_of_stock.
//
//   make load ID=1.1 MODE=broken          # naive read-check-write
//   make load ID=1.1 MODE=sync-in-tx      # synchronized inside the transaction (still broken)
//   make load ID=1.1 MODE=synchronized    # JVM lock around the transaction (works on 1 instance)
//   make load ID=1.1 MODE=pessimistic     # SELECT ... FOR UPDATE
//   make load ID=1.1 MODE=optimistic      # version column + retry
//   make load ID=1.1 MODE=fixed           # atomic conditional UPDATE
//
// Knobs: STOCK (200), RATE (100/s), DURATION (30s), RACE_WINDOW (ms, default 0).
//
// Arrivals are random (Poisson), like real customers, so some requests land within a millisecond
// of each other and the race happens with no help. RACE_WINDOW pauses between the read and the
// write in the naive strategies to make it bigger: production has a small window but huge traffic,
// and GC pauses or slow queries at peak stretch it.
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, envInt, env, placeOrder, poissonArrivals, poissonPause, printVerdict, randomCustomer,
  resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const PRODUCT = 42;
const STOCK = envInt('STOCK', 200);

const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    // Random (Poisson) arrivals, like real customers reacting to the same push notification.
    flash_sale: poissonArrivals(envInt('RATE', 100), env('DURATION', '30s'), 300),
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const COMMON = {
  'per-line-delay-ms': '0',
  'notification-mode': 'none',        // keep the vendor out of this experiment
  'rider-assignment': 'conditional',
  'rider-locator': 'db-nearby',
  'order-idempotency': 'off',
  'inventory-lock-order': 'sorted',
  'race-window-ms': env('RACE_WINDOW', '0'),
};

const MODES = {
  broken: { 'inventory-reservation': 'naive' },
  'sync-in-tx': { 'inventory-reservation': 'synchronized-in-tx' },
  synchronized: { 'inventory-reservation': 'synchronized' },
  pessimistic: { 'inventory-reservation': 'pessimistic' },
  optimistic: { 'inventory-reservation': 'optimistic' },
  fixed: { 'inventory-reservation': 'atomic' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ stock: [{ storeId: STORE, productId: PRODUCT, quantity: STOCK }] });
}

export default function () {
  poissonPause();
  const res = placeOrder(randomCustomer(1), STORE, [{ productId: PRODUCT, quantity: 1 }]);
  results.add(1, { result: checkOrder(res, ['placed', 'placed_pending_rider', 'out_of_stock']) });
}

export function teardown() {
  printVerdict('Scenario 1.1 — oversell', { stock: `${STOCK} units of product ${PRODUCT} at store ${STORE}` });
}
