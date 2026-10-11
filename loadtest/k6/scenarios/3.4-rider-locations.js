// Scenario 3.4 — Rider positions kept in each instance's memory.
//
// 2,000 riders around stores 1-2 each ping their location about every 30 s (phones save battery);
// nginx spreads the pings over three instances. For 15 s only pings arrive (the shift starts), then
// orders begin.
//
//   make load ID=3.4 MODE=broken    # rider-locator=memory: each instance knows the riders that pinged *it*
//   make load ID=3.4 MODE=fixed     # rider-locator=redis-geo: one shared GEO set, GEOSEARCH to match
//   make load ID=3.4 MODE=db        # db-nearby, for comparison (every ping is a Postgres UPDATE)
//
// Needs Phase 3.   Knobs: DURATION (orders, 40s).
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, CORE_URLS, applyMode, env, outcome, placeOrder, printVerdict, promValue, randomCustomer, randomInt,
  resetFixtures, waitForScrape,
} from '../lib/quickdrop.js';
import { STORES } from '../lib/world.js';

const WARMUP = '15s';
const results = new Counter('quickdrop_order_results');

export const options = {
  setupTimeout: '6m',
  scenarios: {
    pings: { executor: 'constant-arrival-rate', exec: 'ping', rate: 66, timeUnit: '1s', duration: `${15 + parseInt(env('DURATION', '40s'), 10)}s`, preAllocatedVUs: 20, maxVUs: 200 },
    orders: { executor: 'constant-arrival-rate', exec: 'order', rate: 30, timeUnit: '1s', duration: env('DURATION', '40s'), startTime: WARMUP, preAllocatedVUs: 30, maxVUs: 300 },
  },
};

const MODES = {
  broken: { 'rider-locator': 'memory' },
  fixed: { 'rider-locator': 'redis-geo' },
  db: { 'rider-locator': 'db-nearby' },
};

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional',
    'auto-deliver-after-ms': '10000',
  });
  // Only the 2,000 riders that ping are online: the extra fleet from scenario 2.6 stays offline.
  resetFixtures({ restockAll: 100000, riders: { storeId: 1, available: 2000 } });
  if (__ENV.MODE === 'fixed') {
    console.log(`geo sync: ${http.post(`${CORE_URLS[0]}/admin/riders/geo-sync`).body}`);
  }
}

/** Seeded riders g with g % 6 in {0, 1} live around stores 1 and 2 (Bengaluru). */
export function ping() {
  const rider = 6 * randomInt(1, 999) + randomInt(0, 1);
  const store = STORES[1 + (rider % 6)];
  const body = JSON.stringify({ lat: store.lat + (Math.random() - 0.5) * 0.054, lon: store.lon + (Math.random() - 0.5) * 0.054 });
  http.post(`${BASE_URL}/api/riders/${rider}/location`, body, { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/riders/{id}/location' } });
}

export function order() {
  const res = placeOrder(randomCustomer(1), randomInt(1, 2), [{ productId: randomInt(1, 500), quantity: 1 }]);
  const result = outcome(res);
  results.add(1, { result: result === 'placed' && res.json('riderId') === null ? 'no_rider' : result });
}

export function teardown() {
  waitForScrape();
  const known = CORE_URLS.map((url) => {
    const res = http.get(`${url}/admin/riders/memory`, { timeout: '2s' });
    return res.status === 200 ? res.json('knownRiders') : '-';
  });
  printVerdict('Scenario 3.4 — rider locations', {
    'riders known per instance (memory locator)': known.join(' / '),
    'average distance to the assigned rider (km)': (promValue('sum(rate(quickdrop_rider_assignment_distance_km_sum[45s])) / sum(rate(quickdrop_rider_assignment_distance_km_count[45s]))') || 0).toFixed(2),
    'p95 distance (km)': (promValue('histogram_quantile(0.95, sum by (le) (rate(quickdrop_rider_assignment_distance_km_bucket[45s])))') || 0).toFixed(2),
    'orders without a rider': `${Math.round(promValue('sum(increase(quickdrop_rider_assignments_total{result="no_rider_available"}[45s]))') || 0)}`
      + ` of ${Math.round(promValue('sum(increase(quickdrop_rider_assignments_total[45s]))') || 0)} attempts`,
  });
}
