// Scenario 3.2 — A Redis lock whose holder freezes past the TTL.
//
// inventory-reservation=redis-lock: SET NX PX on lock:stock:{store}, naive read-check-write inside.
// 5% of lock holders freeze for 3 s between reading and writing stock (a stop-the-world GC pause, a VM
// being descheduled). The lock's TTL is 2 s. It expires under them, someone else gets it, and when the
// frozen holder wakes up it writes a stock level computed from a stale read.
//
//   make load ID=3.2 MODE=no-pause    # nobody freezes: the Redis lock is correct
//   make load ID=3.2 MODE=broken      # 5% freeze for 3 s > TTL 2 s: oversold
//   make load ID=3.2 MODE=fixed       # same freezes, fencing tokens on: the late writes are refused
//
// Needs Phase 3.   Knobs: STOCK (300), RATE (40/s), DURATION (45s).
import { Counter } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, poissonArrivals, poissonPause, printVerdict, promValue,
  randomCustomer, resetFixtures, waitForScrape,
} from '../lib/quickdrop.js';

const STORE = 1;
const PRODUCT = 42;
const STOCK = envInt('STOCK', 300);
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m',
  scenarios: { orders: poissonArrivals(envInt('RATE', 40), env('DURATION', '45s'), 300) },
};

const COMMON = {
  'notification-mode': 'none',
  'rider-assignment': 'conditional',
  'inventory-reservation': 'redis-lock',
  'redis-lock-ttl-ms': '2000',
  'redis-lock-wait-ms': '10000',
  'lock-holder-pause-ms': '3000',
};

const MODES = {
  'no-pause': { 'lock-holder-pause-percent': '0', 'redis-lock-fencing': 'off' },
  broken: { 'lock-holder-pause-percent': '5', 'redis-lock-fencing': 'off' },
  fixed: { 'lock-holder-pause-percent': '5', 'redis-lock-fencing': 'on' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ stock: [{ storeId: STORE, productId: PRODUCT, quantity: STOCK }] });
}

export default function () {
  poissonPause();
  const res = placeOrder(randomCustomer(1), STORE, [{ productId: PRODUCT, quantity: 1 }], { timeout: '30s' });
  results.add(1, { result: checkOrder(res, ['placed', 'placed_pending_rider', 'out_of_stock', 'lock_lost']) });
}

export function teardown() {
  waitForScrape();
  const window = '90s';
  printVerdict('Scenario 3.2 — Redis lock expiry', {
    'holders that froze': Math.round(promValue(`sum(increase(quickdrop_redis_lock_holder_pauses_total[${window}]))`) || 0),
    'locks that expired before release': Math.round(promValue(`sum(increase(quickdrop_redis_lock_total{result="expired_before_release"}[${window}]))`) || 0),
    'writes refused by fencing (lock_lost)': Math.round(promValue(`sum(increase(quickdrop_api_errors_total{error="lock_lost"}[${window}]))`) || 0),
  });
}
