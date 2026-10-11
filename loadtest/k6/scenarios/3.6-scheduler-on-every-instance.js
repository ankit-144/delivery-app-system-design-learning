// Scenario 3.6 — A scheduled job that runs on every instance.
//
// Riders are scarce (50 online near store 1), so many orders wait in PENDING_RIDER. Every 2 s the
// pending-rider job retries them — on core-1, core-2 and core-3, all at once, on the same orders.
//
//   make load ID=3.6 MODE=broken        # scheduler-lock=none: two instances give one order two riders
//   make load ID=3.6 MODE=redis         # a Redis lock: one instance runs the job per round
//   make load ID=3.6 MODE=fixed         # FOR UPDATE SKIP LOCKED: all instances run, never on the same order
//
// Needs Phase 3.   Knobs: RIDERS (50), RATE (20/s), DURATION (60s).
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, printVerdict, randomCustomer, randomInt, resetFixtures,
} from '../lib/quickdrop.js';

const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m',
  scenarios: {
    orders: { executor: 'constant-arrival-rate', rate: envInt('RATE', 20), timeUnit: '1s', duration: env('DURATION', '60s'), preAllocatedVUs: 20, maxVUs: 200 },
  },
};

const MODES = {
  broken: { 'scheduler-lock': 'none' },
  redis: { 'scheduler-lock': 'redis' },
  fixed: { 'scheduler-lock': 'skip-locked' },
};

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional',
    'rider-locator': 'db-nearby', 'auto-deliver-after-ms': '5000', 'pending-rider-job': 'on',
  });
  resetFixtures({ restockAll: 100000, riders: { storeId: 1, available: envInt('RIDERS', 50) } });
}

export default function () {
  const res = placeOrder(randomCustomer(1), 1, [{ productId: randomInt(1, 500), quantity: 1 }]);
  results.add(1, { result: checkOrder(res, ['placed']) });
}

export function teardown() {
  printVerdict('Scenario 3.6 — scheduled job on every instance', {
    hint: `rider_ghost_busy riders (of ${envInt('RIDERS', 50)} online) are stuck BUSY for an order that has another rider: they never come back.`,
  });
}
