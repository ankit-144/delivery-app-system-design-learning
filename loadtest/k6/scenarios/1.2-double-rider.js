// Scenario 1.2 — Two orders get the same rider.
//
// RIDERS riders are online near store 1 and deliveries are quick, so there are usually free riders —
// and every order goes for the same one: the nearest.
//
//   make load ID=1.2 MODE=broken    # "is the rider available? yes -> mark busy"
//   make load ID=1.2 MODE=fixed     # UPDATE ... WHERE status = 'AVAILABLE'
//
// Knobs: RIDERS (200), RATE (50/s), DURATION (30s), RACE_WINDOW (ms, default 0).
//
// Arrivals are random (Poisson), like real customers, so some requests land within a millisecond
// of each other and the race happens with no help. RACE_WINDOW pauses between the read and the
// write in the naive strategies to make it bigger: production has a small window but huge traffic,
// and GC pauses or slow queries at peak stretch it.
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, poissonArrivals, poissonPause, printVerdict, randomCustomer, randomInt,
  resetFixtures,
} from '../lib/quickdrop.js';

const STORE = 1;
const RIDERS = envInt('RIDERS', 200);
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    // Random (Poisson) arrivals: customers don't coordinate, so some arrive within milliseconds of each other.
    lunch_rush: poissonArrivals(envInt('RATE', 50), env('DURATION', '30s')),
  },
};

const COMMON = {
  'per-line-delay-ms': '0',
  'notification-mode': 'none',
  'inventory-reservation': 'atomic',
  'inventory-lock-order': 'sorted',
  'rider-locator': 'db-nearby',
  'order-idempotency': 'off',
  'auto-deliver-after-ms': '3000',   // riders come back fast, so the fight never stops
  'pending-rider-job': 'on',
  'race-window-ms': env('RACE_WINDOW', '0'),
};

const MODES = {
  broken: { 'rider-assignment': 'naive' },
  fixed: { 'rider-assignment': 'conditional' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000, riders: { storeId: STORE, available: RIDERS } });
}

export default function () {
  poissonPause();
  const res = placeOrder(randomCustomer(1), STORE, [{ productId: randomInt(1, 500), quantity: 1 }]);
  results.add(1, { result: checkOrder(res, ['placed']) });
}

export function teardown() {
  printVerdict('Scenario 1.2 — double-booked riders', { riders: `${RIDERS} online near store ${STORE}` });
}
