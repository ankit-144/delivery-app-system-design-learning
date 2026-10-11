// Scenario 3.1 — The synchronized fix from 1.1, on three instances.
//
// Same flash sale as 1.1: 200 units of product 42, ~100 customers per second. In 1.1 the JVM lock
// (`synchronized`) was correct. Now nginx spreads requests over core-1, core-2 and core-3, and each
// JVM has its own lock.
//
//   make load ID=3.1 MODE=broken       # synchronized: oversells again
//   make load ID=3.1 MODE=redis-lock   # the same idea with the lock in Redis, shared by every instance
//   make load ID=3.1 MODE=fixed        # atomic UPDATE: never needed a lock outside the database
//
// Needs Phase 3: make up PHASE=3.   Knobs: STOCK (200), RATE (100/s), DURATION (30s).
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, liveCoreUrls, placeOrder, poissonArrivals, poissonPause, printVerdict,
  randomCustomer, resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const PRODUCT = 42;
const STOCK = envInt('STOCK', 200);
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m',
  scenarios: { flash_sale: poissonArrivals(envInt('RATE', 100), env('DURATION', '30s'), 300) },
};

const COMMON = {
  'notification-mode': 'none',
  'rider-assignment': 'conditional',
  'inventory-lock-order': 'sorted',
};

const MODES = {
  broken: { 'inventory-reservation': 'synchronized' },
  'redis-lock': { 'inventory-reservation': 'redis-lock' },
  fixed: { 'inventory-reservation': 'atomic' },
};

export function setup() {
  const instances = liveCoreUrls().length;
  if (instances < 2) console.warn(`Only ${instances} core instance is running: start Phase 3 with "make up PHASE=3".`);
  applyMode(MODES, COMMON);
  resetFixtures({ stock: [{ storeId: STORE, productId: PRODUCT, quantity: STOCK }] });
  return { instances };
}

export default function () {
  poissonPause();
  const res = placeOrder(randomCustomer(1), STORE, [{ productId: PRODUCT, quantity: 1 }]);
  results.add(1, { result: checkOrder(res, ['placed', 'placed_pending_rider', 'out_of_stock']) });
}

export function teardown(data) {
  printVerdict('Scenario 3.1 — JVM lock on three instances', { instances: data.instances, stock: STOCK });
}
